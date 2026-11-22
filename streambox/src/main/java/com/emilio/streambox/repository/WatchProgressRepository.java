package com.emilio.streambox.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceso a datos del <b>progreso de visionado</b> (tablas
 * {@code user_movie_progress} y {@code user_episode_progress}, migración
 * {@code V14}): por dónde va cada usuario en cada película o episodio, para
 * reanudar el reproductor y para la fila «Seguir viendo».
 *
 * <h2>Modelo</h2>
 * <ul>
 *   <li>Una fila por (usuario, título) que se <b>sobrescribe</b> en cada
 *       guardado ({@link #saveMovieProgress}, {@link #saveEpisodeProgress}):
 *       no hay historial.</li>
 *   <li>Posición y duración en segundos enteros del vídeo reproducido (ver
 *       {@link WatchProgress}); la base de datos exige
 *       {@code 0 <= posición <= duración <= 86400} y {@code duración >= 1}.</li>
 *   <li>Borrar el usuario, la película, el episodio o la serie borra su
 *       progreso (cascadas de la {@code V14}).</li>
 * </ul>
 *
 * <h2>Por qué SQL con {@link JdbcClient} y no una entidad JPA</h2>
 * <p>
 * Igual que los «me gusta» y las reservas de nombre
 * ({@link UsernameReservationRepository}): nadie necesita cargar un progreso
 * como objeto gestionado por Hibernate. Las escrituras son un <i>upsert</i>
 * que tiene que ser seguro con dos guardados simultáneos, y la única lectura
 * compleja («Seguir viendo») junta dos tablas con un {@code UNION ALL} y
 * devuelve una proyección, algo que una entidad no aporta. Tampoco hace falta
 * que Hibernate valide estas tablas: no hay entidad que pueda desajustarse.
 * {@link JdbcClient} usa la conexión de la transacción en curso, así que un
 * guardado hecho dentro de un servicio {@code @Transactional} se confirma o se
 * deshace con él.
 * </p>
 *
 * <h2>Upsert portable: UPDATE y, si no había fila, INSERT ... ON CONFLICT DO NOTHING</h2>
 * <p>
 * Lo natural en PostgreSQL sería {@code INSERT ... ON CONFLICT (...) DO
 * UPDATE}, pero <b>H2 no lo admite</b> ni en modo PostgreSQL (solo
 * {@code ON CONFLICT DO NOTHING}; comprobado con H2 2.4.240, la de los tests).
 * {@code MERGE} sí funciona en los dos, pero en PostgreSQL <b>no es seguro con
 * concurrencia</b>: si dos guardados de una fila que aún no existe se cruzan
 * (dos pestañas, o el guardado periódico y el de la pausa), los dos ven «no
 * existe», los dos insertan y uno falla con {@code 23505}. Por eso cada
 * guardado hace:
 * </p>
 * <ol>
 *   <li>{@code UPDATE}: el caso normal (el reproductor guarda cada pocos
 *       segundos sobre una fila que ya existe), <b>una sola sentencia</b>.</li>
 *   <li>Si no actualizó nada, {@code INSERT ... ON CONFLICT DO NOTHING}. Si
 *       otra transacción está insertando la misma fila, PostgreSQL espera a
 *       que termine y, si confirma, no inserta nada (ni falla).</li>
 *   <li>Si tampoco insertó (perdió esa carrera), vuelve al paso 1, que ahora
 *       sí ve la fila confirmada por la otra (nivel {@code READ COMMITTED}, el
 *       de PostgreSQL y H2 por defecto: cada sentencia ve lo último
 *       confirmado). Gana el último en escribir, que es lo que se quiere.</li>
 * </ol>
 *
 * <h2>Errores que debe traducir el servicio</h2>
 * <ul>
 *   <li><b>Clave foránea</b> ({@code 23503}; {@code 23506} en H2) en un
 *       guardado: el título (o la cuenta) se borró, o nunca existió. El nombre
 *       de la restricción ({@link #MOVIE_PROGRESS_USER_FK},
 *       {@link #EPISODE_PROGRESS_USER_FK}) distingue la del usuario, como en
 *       {@code LikeService}.</li>
 *   <li><b>{@code CHECK}</b> ({@code 23514}; {@code 23513} en H2): posición o
 *       duración fuera de rango. No debería llegar nunca: el servicio valida
 *       antes con las mismas reglas ({@link WatchProgress#MAX_DURATION_SECONDS})
 *       y responde 400.</li>
 * </ul>
 * <p>
 * En PostgreSQL cualquiera de las dos deja la transacción abortada: el
 * servicio no debe hacer más consultas en ella tras capturarla.
 * </p>
 *
 * <h2>«Ahora» lo pasa el llamador</h2>
 * <p>
 * {@code updated_at} es el instante del {@code Clock} de la aplicación (que los
 * tests controlan), nunca {@code now()} de la base de datos.
 * </p>
 */
@Repository
public class WatchProgressRepository {

    /** Clave foránea del progreso de películas hacia {@code users} (para traducir su violación). */
    public static final String MOVIE_PROGRESS_USER_FK = "fk_movie_progress_user";

    /** Clave foránea del progreso de películas hacia {@code movies}. */
    public static final String MOVIE_PROGRESS_MOVIE_FK = "fk_movie_progress_movie";

    /** Clave foránea del progreso de episodios hacia {@code users}. */
    public static final String EPISODE_PROGRESS_USER_FK = "fk_episode_progress_user";

    /** Clave foránea del progreso de episodios hacia {@code episodes}. */
    public static final String EPISODE_PROGRESS_EPISODE_FK = "fk_episode_progress_episode";

    /**
     * Vueltas del <i>upsert</i> antes de rendirse. La segunda solo hace falta
     * si se pierde la carrera del {@code INSERT}; una tercera, si además la
     * fila ganadora se borra («Quitar de Seguir viendo») justo entre medias.
     * Una cuarta seguida no debería ocurrir nunca.
     */
    private static final int MAX_UPSERT_ATTEMPTS = 3;

    /**
     * Condición SQL de «no terminado» sobre el alias {@code p}: la negación
     * exacta de {@link WatchProgress#isFinished(int, int)}, en aritmética
     * entera (sin desbordamiento: como mucho 86400 × 100).
     */
    private static final String NOT_FINISHED =
            "p.position_seconds * 100 < p.duration_seconds * " + WatchProgress.FINISHED_PERCENT;

    /**
     * «Seguir viendo»: películas y episodios a medio ver, del más reciente al
     * más antiguo, en una sola consulta (ver {@link #findContinueWatching}).
     */
    private static final String CONTINUE_WATCHING_SQL = """
            SELECT kind, item_id, title, image_url, season_number, episode_number,
                   series_id, series_title, position_seconds, duration_seconds, updated_at
              FROM (
                    SELECT CAST('MOVIE' AS VARCHAR(7)) AS kind,
                           m.id AS item_id, m.title AS title, m.image_url AS image_url,
                           CAST(NULL AS INTEGER) AS season_number, CAST(NULL AS INTEGER) AS episode_number,
                           CAST(NULL AS BIGINT) AS series_id, CAST(NULL AS VARCHAR(150)) AS series_title,
                           p.position_seconds, p.duration_seconds, p.updated_at
                      FROM user_movie_progress p
                      JOIN movies m ON m.id = p.movie_id
                     WHERE p.user_id = :userId
                       AND m.video_url IS NOT NULL
                       AND %1$s
                    UNION ALL
                    SELECT CAST('EPISODE' AS VARCHAR(7)),
                           le.episode_id, le.title, s.image_url, le.season_number, le.episode_number,
                           s.id, s.title,
                           le.position_seconds, le.duration_seconds, le.updated_at
                      FROM (
                            SELECT p.episode_id, p.position_seconds, p.duration_seconds, p.updated_at,
                                   e.series_id, e.season_number, e.episode_number, e.title,
                                   ROW_NUMBER() OVER (PARTITION BY e.series_id
                                                      ORDER BY p.updated_at DESC, p.episode_id DESC) AS rn
                              FROM user_episode_progress p
                              JOIN episodes e ON e.id = p.episode_id
                             WHERE p.user_id = :userId
                               AND e.video_url IS NOT NULL
                               AND %1$s
                           ) le
                      JOIN series s ON s.id = le.series_id
                     WHERE le.rn = 1
                   ) items
             ORDER BY updated_at DESC, kind DESC, item_id DESC
             FETCH FIRST :limit ROWS ONLY
            """.formatted(NOT_FINISHED);

    private final JdbcClient jdbc;

    /**
     * Crea el repositorio.
     *
     * @param jdbc cliente JDBC de Spring (autoconfigurado sobre el
     *             {@code DataSource} de la aplicación)
     */
    public WatchProgressRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // Guardar (upsert)
    // ------------------------------------------------------------------

    /**
     * Guarda por dónde va el usuario en una película, creando la fila o
     * sobrescribiéndola. Seguro con guardados simultáneos (ver la clase).
     *
     * @param userId          identificador del usuario (el del token)
     * @param movieId         identificador de la película
     * @param positionSeconds segundo por el que va ({@code 0..durationSeconds})
     * @param durationSeconds duración del vídeo reproducido
     *                        ({@code 1..}{@value WatchProgress#MAX_DURATION_SECONDS})
     * @param updatedAt       instante del guardado (del {@code Clock} del servicio)
     * @return {@code true} si la fila es nueva (primer guardado de esa
     *         película); {@code false} si ya existía y se ha sobrescrito
     * @throws org.springframework.dao.DataIntegrityViolationException si la
     *         película o el usuario no existen (clave foránea) o los valores
     *         incumplen un {@code CHECK}
     */
    @Transactional
    public boolean saveMovieProgress(long userId, long movieId, int positionSeconds, int durationSeconds,
            Instant updatedAt) {
        return upsert("user_movie_progress", "movie_id", userId, movieId, positionSeconds, durationSeconds,
                updatedAt);
    }

    /**
     * Guarda por dónde va el usuario en un episodio, creando la fila o
     * sobrescribiéndola. Seguro con guardados simultáneos (ver la clase).
     *
     * @param userId          identificador del usuario (el del token)
     * @param episodeId       identificador del episodio
     * @param positionSeconds segundo por el que va ({@code 0..durationSeconds})
     * @param durationSeconds duración del vídeo reproducido
     *                        ({@code 1..}{@value WatchProgress#MAX_DURATION_SECONDS})
     * @param updatedAt       instante del guardado (del {@code Clock} del servicio)
     * @return {@code true} si la fila es nueva; {@code false} si se ha sobrescrito
     * @throws org.springframework.dao.DataIntegrityViolationException si el
     *         episodio o el usuario no existen (clave foránea) o los valores
     *         incumplen un {@code CHECK}
     */
    @Transactional
    public boolean saveEpisodeProgress(long userId, long episodeId, int positionSeconds, int durationSeconds,
            Instant updatedAt) {
        return upsert("user_episode_progress", "episode_id", userId, episodeId, positionSeconds, durationSeconds,
                updatedAt);
    }

    /**
     * {@code UPDATE} y, si no había fila, {@code INSERT ... ON CONFLICT DO
     * NOTHING}, repetido si se pierde la carrera (ver la clase).
     *
     * @param table       tabla de progreso (constante de esta clase, nunca un
     *                    dato del usuario: se concatena en el SQL)
     * @param titleColumn columna del título en esa tabla (ídem)
     * @return {@code true} si ha insertado; {@code false} si ha actualizado
     */
    private boolean upsert(String table, String titleColumn, long userId, long titleId, int positionSeconds,
            int durationSeconds, Instant updatedAt) {
        OffsetDateTime at = utc(updatedAt);
        for (int attempt = 1; attempt <= MAX_UPSERT_ATTEMPTS; attempt++) {
            int updated = jdbc.sql("UPDATE " + table
                    + " SET position_seconds = :position, duration_seconds = :duration, updated_at = :updatedAt"
                    + " WHERE user_id = :userId AND " + titleColumn + " = :titleId")
                    .param("position", positionSeconds)
                    .param("duration", durationSeconds)
                    .param("updatedAt", at)
                    .param("userId", userId)
                    .param("titleId", titleId)
                    .update();
            if (updated == 1) {
                return false;
            }

            int inserted = jdbc.sql("INSERT INTO " + table
                    + " (user_id, " + titleColumn + ", position_seconds, duration_seconds, updated_at)"
                    + " VALUES (:userId, :titleId, :position, :duration, :updatedAt) ON CONFLICT DO NOTHING")
                    .param("userId", userId)
                    .param("titleId", titleId)
                    .param("position", positionSeconds)
                    .param("duration", durationSeconds)
                    .param("updatedAt", at)
                    .update();
            if (inserted == 1) {
                return true;
            }
        }
        throw new IllegalStateException("No se ha podido guardar el progreso en " + table + " tras "
                + MAX_UPSERT_ATTEMPTS + " intentos");
    }

    // ------------------------------------------------------------------
    // Leer
    // ------------------------------------------------------------------

    /**
     * Progreso del usuario en una película (para reanudarla).
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     * @return el progreso guardado, o vacío si nunca la empezó (o la quitó de
     *         «Seguir viendo»). Puede estar terminado: compruébalo con
     *         {@link WatchProgress#finished()}
     */
    public Optional<WatchProgress> findMovieProgress(long userId, long movieId) {
        return jdbc.sql("SELECT position_seconds, duration_seconds, updated_at FROM user_movie_progress"
                + " WHERE user_id = :userId AND movie_id = :movieId")
                .param("userId", userId)
                .param("movieId", movieId)
                .query((rs, row) -> progress(rs))
                .optional();
    }

    /**
     * Progreso del usuario en un episodio (para reanudarlo).
     *
     * @param userId    identificador del usuario
     * @param episodeId identificador del episodio
     * @return el progreso guardado, o vacío si nunca lo empezó. Puede estar
     *         terminado: compruébalo con {@link WatchProgress#finished()}
     */
    public Optional<WatchProgress> findEpisodeProgress(long userId, long episodeId) {
        return jdbc.sql("SELECT position_seconds, duration_seconds, updated_at FROM user_episode_progress"
                + " WHERE user_id = :userId AND episode_id = :episodeId")
                .param("userId", userId)
                .param("episodeId", episodeId)
                .query((rs, row) -> progress(rs))
                .optional();
    }

    /**
     * Progreso del usuario en <b>todos</b> los episodios de una serie que ha
     * empezado, en una sola consulta: para pintar la barra de cada episodio en
     * la página de la serie sin una consulta por episodio (N+1).
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     * @return id del episodio → progreso, en orden de temporada y número; solo
     *         los episodios con progreso guardado (terminados incluidos)
     */
    public Map<Long, WatchProgress> findEpisodeProgressBySeries(long userId, long seriesId) {
        Map<Long, WatchProgress> result = new LinkedHashMap<>();
        jdbc.sql("SELECT p.episode_id, p.position_seconds, p.duration_seconds, p.updated_at"
                + " FROM user_episode_progress p"
                + " JOIN episodes e ON e.id = p.episode_id"
                + " WHERE p.user_id = :userId AND e.series_id = :seriesId"
                + " ORDER BY e.season_number, e.episode_number")
                .param("userId", userId)
                .param("seriesId", seriesId)
                .query((RowCallbackHandler) rs -> result.put(rs.getLong("episode_id"), progress(rs)));
        return result;
    }

    /**
     * La fila «Seguir viendo»: los {@code limit} títulos a medio ver más
     * recientes del usuario, <b>mezclando películas y episodios</b>, en una
     * sola consulta ({@code UNION ALL}) que ya trae los datos de la tarjeta:
     * sin N+1 ni colecciones perezosas.
     *
     * <p><b>Qué entra</b></p>
     * <ul>
     *   <li>Solo lo <b>no terminado</b>: posición por debajo del
     *       {@value WatchProgress#FINISHED_PERCENT} % de la duración (ver
     *       {@link WatchProgress#FINISHED_PERCENT}).</li>
     *   <li>Solo títulos que <b>tienen vídeo</b> ({@code video_url} no nulo; es
     *       opcional desde la {@code V6}). Si el administrador quita el vídeo
     *       de algo que el usuario estaba viendo, la tarjeta prometería un
     *       «Reanudar» imposible: se oculta (la fila se conserva y vuelve a
     *       salir si el vídeo vuelve).</li>
     *   <li><b>Una tarjeta por serie</b>, como Netflix: de cada serie, solo el
     *       episodio no terminado guardado más recientemente. Si no, quien va
     *       por el 1x03 tras dejar el 1x01 a medias vería la serie dos veces.
     *       (No calcula el «siguiente episodio» de uno terminado: eso es lógica
     *       del servicio.)</li>
     *   <li>Las series sin episodios están ocultas para los usuarios, pero aquí
     *       no hace falta filtrarlas: un progreso de episodio implica que su
     *       serie tiene al menos ese episodio.</li>
     * </ul>
     *
     * <p><b>Orden</b></p>
     * <p>
     * Por {@code updated_at} descendente; los empates (mismo instante) se
     * deshacen por tipo (películas antes) y por id descendente, para que la
     * paginación o dos peticiones seguidas den siempre el mismo orden. Cada
     * lado lee las filas del usuario por el índice
     * {@code idx_*_progress_user_updated_at}.
     * </p>
     *
     * @param userId identificador del usuario
     * @param limit  número máximo de elementos (positivo; el servicio fija el
     *               tope que pinte la fila)
     * @return elementos del más reciente al más antiguo; vacía si no hay nada
     *         a medio ver
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException si
     *         {@code limit} no es positivo (es la {@link IllegalArgumentException}
     *         original, traducida por {@code @Repository}; es un error de
     *         programación, nunca de los datos del usuario)
     */
    public List<ContinueWatchingItem> findContinueWatching(long userId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser positivo: " + limit);
        }
        return jdbc.sql(CONTINUE_WATCHING_SQL)
                .param("userId", userId)
                .param("limit", limit)
                .query((rs, row) -> continueWatchingItem(rs))
                .list();
    }

    // ------------------------------------------------------------------
    // Borrar («Quitar de Seguir viendo»)
    // ------------------------------------------------------------------

    /**
     * Borra el progreso del usuario en una película. Idempotente: si no había
     * (o la película no existe) no borra nada y no falla.
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     * @return filas borradas (0 o 1)
     */
    @Transactional
    public int deleteMovieProgress(long userId, long movieId) {
        return jdbc.sql("DELETE FROM user_movie_progress WHERE user_id = :userId AND movie_id = :movieId")
                .param("userId", userId)
                .param("movieId", movieId)
                .update();
    }

    /**
     * Borra el progreso del usuario en un episodio. Idempotente.
     *
     * <p>
     * Ojo para «Quitar de Seguir viendo» en la tarjeta de un episodio: si el
     * usuario tenía otro episodio de la misma serie a medias, ese pasaría a
     * ocupar la tarjeta. Para quitar la serie entera, usa
     * {@link #deleteSeriesProgress}.
     * </p>
     *
     * @param userId    identificador del usuario
     * @param episodeId identificador del episodio
     * @return filas borradas (0 o 1)
     */
    @Transactional
    public int deleteEpisodeProgress(long userId, long episodeId) {
        return jdbc.sql("DELETE FROM user_episode_progress WHERE user_id = :userId AND episode_id = :episodeId")
                .param("userId", userId)
                .param("episodeId", episodeId)
                .update();
    }

    /**
     * Borra el progreso del usuario en <b>todos</b> los episodios de una serie
     * (lo que espera «Quitar de Seguir viendo» en la tarjeta de una serie: que
     * desaparezca, no que salga otro episodio suyo). Idempotente.
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     * @return filas borradas (una por episodio con progreso)
     */
    @Transactional
    public int deleteSeriesProgress(long userId, long seriesId) {
        return jdbc.sql("DELETE FROM user_episode_progress WHERE user_id = :userId"
                + " AND episode_id IN (SELECT e.id FROM episodes e WHERE e.series_id = :seriesId)")
                .param("userId", userId)
                .param("seriesId", seriesId)
                .update();
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Lee posición, duración y fecha de la fila actual. */
    private static WatchProgress progress(ResultSet rs) throws SQLException {
        return new WatchProgress(rs.getInt("position_seconds"), rs.getInt("duration_seconds"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /** Construye el elemento de «Seguir viendo» de la fila actual según su tipo. */
    private static ContinueWatchingItem continueWatchingItem(ResultSet rs) throws SQLException {
        WatchProgress progress = progress(rs);
        String kind = rs.getString("kind");
        return switch (kind) {
            case "MOVIE" -> new ContinueWatchingItem.MovieItem(rs.getLong("item_id"), rs.getString("title"),
                    rs.getString("image_url"), progress);
            case "EPISODE" -> new ContinueWatchingItem.EpisodeItem(rs.getLong("item_id"),
                    rs.getInt("season_number"), rs.getInt("episode_number"), rs.getString("title"),
                    rs.getLong("series_id"), rs.getString("series_title"), rs.getString("image_url"), progress);
            default -> throw new IllegalStateException("Tipo de «Seguir viendo» desconocido: " + kind);
        };
    }

    /**
     * Pasa un {@link Instant} a {@link OffsetDateTime} en UTC: el driver de
     * PostgreSQL no admite {@code Instant} como parámetro JDBC y los dos
     * motores aceptan {@code OffsetDateTime} para {@code TIMESTAMP WITH TIME ZONE}.
     */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "updatedAt"), ZoneOffset.UTC);
    }
}
