package com.emilio.streambox.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
 * reanudar el reproductor, para la fila «Seguir viendo» y para el historial y
 * las cifras del perfil ({@link #findWatchHistory}, {@link #getWatchStats}).
 *
 * <h2>Modelo</h2>
 * <ul>
 *   <li>Una fila por (usuario, título) que se <b>sobrescribe</b> en cada
 *       guardado ({@link #saveMovieProgress}, {@link #saveEpisodeProgress}):
 *       no se guardan las sesiones, solo el último punto. El «historial» del
 *       perfil son estas mismas filas ordenadas por fecha.</li>
 *   <li>Posición y duración en segundos enteros del vídeo reproducido (ver
 *       {@link WatchProgress}); la base de datos exige
 *       {@code 0 <= posición <= duración <= 86400} y {@code duración >= 1}.</li>
 *   <li>Borrar el usuario, la película, el episodio o la serie borra su
 *       progreso (cascadas de la {@code V14}).</li>
 *   <li>{@code finished_at} ({@code V15}): instante en que el título se
 *       terminó <b>por primera vez</b>. El <i>upsert</i> lo pone al guardar
 *       una posición terminada si era nulo y no lo toca al guardar una menor,
 *       así que volver a ver algo no lo «desmarca» del historial ni de las
 *       cifras ({@link WatchProgress#watched()}). «Seguir viendo» no lo usa:
 *       sigue decidiendo por la posición actual
 *       ({@link WatchProgress#finished()}).</li>
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

    /** Condición SQL de «no terminado» sobre el alias {@code p} (ver {@link #notFinished(String)}). */
    private static final String NOT_FINISHED = notFinished("p");

    /**
     * «Seguir viendo»: películas a medio ver y una tarjeta por serie, del más
     * reciente al más antiguo, en una sola consulta (el criterio de las series
     * y el porqué de cada paso están en {@link #findContinueWatching}).
     *
     * <ul>
     *   <li>{@code t}: los progresos del usuario en episodios con vídeo,
     *       numerados por serie del más reciente al más antiguo
     *       ({@code rn = 1} es el <b>último episodio tocado</b>).</li>
     *   <li>{@code lt}: ese último tocado y, solo si está terminado, el id del
     *       <b>siguiente con vídeo</b> ({@code next_id}), con una subconsulta
     *       correlacionada que recorre el índice único
     *       {@code uk_episodes_series_season_episode} desde su posición y se
     *       detiene en el primero (el {@code CASE} evita la búsqueda si no está
     *       terminado). Nulo si no está terminado o si no hay siguiente.</li>
     *   <li>Fuera: los terminados sin siguiente. La tarjeta es el siguiente
     *       ({@code COALESCE}) o el propio episodio; el progreso del siguiente
     *       ({@code np}) solo se usa si está a medias.</li>
     *   <li>{@code progress_finished_at}: el {@code finished_at} del progreso
     *       de la tarjeta, solo para que el {@link WatchProgress} sea fiel a la
     *       fila (una tarjeta puede estar vista y a medias: se está volviendo a
     *       ver). No filtra ni ordena nada.</li>
     * </ul>
     */
    private static final String CONTINUE_WATCHING_SQL = """
            SELECT kind, item_id, title, image_url, season_number, episode_number,
                   series_id, series_title, position_seconds, duration_seconds, progress_updated_at,
                   progress_finished_at, up_next, updated_at
              FROM (
                    SELECT CAST('MOVIE' AS VARCHAR(7)) AS kind,
                           m.id AS item_id, m.title AS title, m.image_url AS image_url,
                           CAST(NULL AS INTEGER) AS season_number, CAST(NULL AS INTEGER) AS episode_number,
                           CAST(NULL AS BIGINT) AS series_id, CAST(NULL AS VARCHAR(150)) AS series_title,
                           p.position_seconds AS position_seconds, p.duration_seconds AS duration_seconds,
                           p.updated_at AS progress_updated_at, p.finished_at AS progress_finished_at,
                           FALSE AS up_next, p.updated_at AS updated_at
                      FROM user_movie_progress p
                      JOIN movies m ON m.id = p.movie_id
                     WHERE p.user_id = :userId
                       AND m.video_url IS NOT NULL
                       AND %1$s
                    UNION ALL
                    SELECT CAST('EPISODE' AS VARCHAR(7)),
                           c.id, c.title, s.image_url, c.season_number, c.episode_number,
                           s.id, s.title,
                           CASE WHEN lt.next_id IS NULL THEN lt.position_seconds
                                WHEN %2$s THEN np.position_seconds END,
                           CASE WHEN lt.next_id IS NULL THEN lt.duration_seconds
                                WHEN %2$s THEN np.duration_seconds END,
                           CASE WHEN lt.next_id IS NULL THEN lt.updated_at
                                WHEN %2$s THEN np.updated_at END,
                           CASE WHEN lt.next_id IS NULL THEN lt.finished_at
                                WHEN %2$s THEN np.finished_at END,
                           lt.next_id IS NOT NULL, lt.updated_at
                      FROM (
                            SELECT t.episode_id, t.series_id, t.position_seconds, t.duration_seconds,
                                   t.updated_at, t.finished_at,
                                   CASE WHEN NOT (%4$s) THEN (
                                        SELECT n.id
                                          FROM episodes n
                                         WHERE n.series_id = t.series_id
                                           AND n.video_url IS NOT NULL
                                           AND (n.season_number > t.season_number
                                                OR (n.season_number = t.season_number
                                                    AND n.episode_number > t.episode_number))
                                         ORDER BY n.season_number, n.episode_number
                                         FETCH FIRST 1 ROW ONLY
                                   ) END AS next_id
                              FROM (
                                    SELECT p.episode_id, p.position_seconds, p.duration_seconds, p.updated_at,
                                           p.finished_at, e.series_id, e.season_number, e.episode_number,
                                           ROW_NUMBER() OVER (PARTITION BY e.series_id
                                                              ORDER BY p.updated_at DESC, p.episode_id DESC) AS rn
                                      FROM user_episode_progress p
                                      JOIN episodes e ON e.id = p.episode_id
                                     WHERE p.user_id = :userId
                                       AND e.video_url IS NOT NULL
                                   ) t
                             WHERE t.rn = 1
                           ) lt
                      JOIN episodes c ON c.id = COALESCE(lt.next_id, lt.episode_id)
                      JOIN series s ON s.id = lt.series_id
                      LEFT JOIN user_episode_progress np
                             ON np.user_id = :userId AND np.episode_id = lt.next_id
                     WHERE lt.next_id IS NOT NULL OR %3$s
                   ) items
             ORDER BY updated_at DESC, kind DESC, item_id DESC
             FETCH FIRST :limit ROWS ONLY
            """.formatted(NOT_FINISHED, notFinished("np"), notFinished("lt"), notFinished("t"));

    /**
     * Historial del perfil: una entrada por película con progreso y una por
     * serie (su último episodio tocado), terminadas o no y con o sin vídeo, del
     * más reciente al más antiguo (el criterio está en {@link #findWatchHistory}).
     *
     * <ul>
     *   <li>Primera rama: las películas del usuario, tal cual.</li>
     *   <li>{@code t}: los progresos del usuario en episodios (todos, sin mirar
     *       el vídeo), numerados por serie del más reciente al más antiguo con
     *       el mismo desempate que «Seguir viendo»; se queda {@code rn = 1}.</li>
     *   <li>El orden final y el desempate son los de «Seguir viendo»: fecha
     *       descendente, películas antes ({@code 'MOVIE' > 'EPISODE'}) e id
     *       descendente.</li>
     * </ul>
     */
    private static final String WATCH_HISTORY_SQL = """
            SELECT kind, item_id, title, image_url, season_number, episode_number,
                   series_id, series_title, position_seconds, duration_seconds, updated_at, finished_at
              FROM (
                    SELECT CAST('MOVIE' AS VARCHAR(7)) AS kind,
                           m.id AS item_id, m.title AS title, m.image_url AS image_url,
                           CAST(NULL AS INTEGER) AS season_number, CAST(NULL AS INTEGER) AS episode_number,
                           CAST(NULL AS BIGINT) AS series_id, CAST(NULL AS VARCHAR(150)) AS series_title,
                           p.position_seconds AS position_seconds, p.duration_seconds AS duration_seconds,
                           p.updated_at AS updated_at, p.finished_at AS finished_at
                      FROM user_movie_progress p
                      JOIN movies m ON m.id = p.movie_id
                     WHERE p.user_id = :userId
                    UNION ALL
                    SELECT CAST('EPISODE' AS VARCHAR(7)),
                           t.episode_id, t.episode_title, s.image_url, t.season_number, t.episode_number,
                           s.id, s.title,
                           t.position_seconds, t.duration_seconds, t.updated_at, t.finished_at
                      FROM (
                            SELECT p.episode_id, p.position_seconds, p.duration_seconds, p.updated_at,
                                   p.finished_at,
                                   e.series_id, e.season_number, e.episode_number, e.title AS episode_title,
                                   ROW_NUMBER() OVER (PARTITION BY e.series_id
                                                      ORDER BY p.updated_at DESC, p.episode_id DESC) AS rn
                              FROM user_episode_progress p
                              JOIN episodes e ON e.id = p.episode_id
                             WHERE p.user_id = :userId
                           ) t
                      JOIN series s ON s.id = t.series_id
                     WHERE t.rn = 1
                   ) items
             ORDER BY updated_at DESC, kind DESC, item_id DESC
             FETCH FIRST :limit ROWS ONLY
            """;

    /**
     * Cifras del perfil en una sola consulta: un agregado por tabla (cada uno
     * devuelve siempre una fila, también sin progreso) unidos con
     * {@code CROSS JOIN}.
     *
     * <ul>
     *   <li>Vistos: {@code COUNT(p.finished_at)}, que solo cuenta los no
     *       nulos: lo terminado <b>alguna vez</b> ({@code V15}), aunque ahora
     *       se esté volviendo a ver.</li>
     *   <li>Segundos: duración si se ha visto alguna vez, posición si no. La
     *       suma de una columna {@code INTEGER} ya es {@code BIGINT} en
     *       PostgreSQL y en H2; el {@code CAST} lo deja escrito y fija el tipo
     *       del {@code COALESCE} (sin filas, {@code SUM} es nulo).</li>
     * </ul>
     */
    private static final String WATCH_STATS_SQL = """
            SELECT mv.finished AS finished_movies, ep.finished AS finished_episodes,
                   mv.watched + ep.watched AS watched_seconds
              FROM (
                    SELECT COUNT(p.finished_at) AS finished,
                           CAST(COALESCE(SUM(CASE WHEN p.finished_at IS NULL THEN p.position_seconds
                                                  ELSE p.duration_seconds END), 0) AS BIGINT) AS watched
                      FROM user_movie_progress p
                     WHERE p.user_id = :userId
                   ) mv
             CROSS JOIN (
                    SELECT COUNT(p.finished_at) AS finished,
                           CAST(COALESCE(SUM(CASE WHEN p.finished_at IS NULL THEN p.position_seconds
                                                  ELSE p.duration_seconds END), 0) AS BIGINT) AS watched
                      FROM user_episode_progress p
                     WHERE p.user_id = :userId
                   ) ep
            """;

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
     * <p><b>{@code finished_at} («visto alguna vez», {@code V15})</b></p>
     * <ul>
     *   <li>{@code :finishedAt} es {@code updatedAt} si la posición que llega
     *       está terminada ({@link WatchProgress#isFinished(int, int)}) y
     *       {@code NULL} si no.</li>
     *   <li>{@code INSERT}: se guarda tal cual.</li>
     *   <li>{@code UPDATE}: si la fila no tenía {@code finished_at}, recibe
     *       {@code :finishedAt} (es decir, {@code COALESCE(finished_at,
     *       :finishedAt)}): se marca al terminar y una posición a medias no lo
     *       cambia. Si ya lo tenía, <b>no se mueve</b>: es el instante de la
     *       primera vez, aunque se vuelva a terminar.</li>
     *   <li>Única excepción: si el {@code finished_at} guardado es
     *       <b>posterior</b> al nuevo {@code updated_at}, se recorta a él. Pasa
     *       cuando dos guardados se cruzan (dos pestañas: cada uno toma su
     *       instante antes de su transacción y puede confirmar último el de
     *       instante más antiguo) o si el reloj del servidor retrocede. Sin el
     *       recorte, el guardado fallaría con
     *       {@code ck_*_progress_finished_at_not_after_updated_at}; con él, el
     *       título sigue visto y la fecha es la más temprana que conserva la
     *       fila.</li>
     *   <li>La carrera del <i>upsert</i> sigue siendo correcta: quien pierde el
     *       {@code INSERT} vuelve al {@code UPDATE}, que aplica las reglas de
     *       arriba sobre la fila que confirmó el otro.</li>
     * </ul>
     *
     * @param table       tabla de progreso (constante de esta clase, nunca un
     *                    dato del usuario: se concatena en el SQL)
     * @param titleColumn columna del título en esa tabla (ídem)
     * @return {@code true} si ha insertado; {@code false} si ha actualizado
     */
    private boolean upsert(String table, String titleColumn, long userId, long titleId, int positionSeconds,
            int durationSeconds, Instant updatedAt) {
        OffsetDateTime at = utc(updatedAt);
        // Se pasa con tipo explícito (Types.TIMESTAMP_WITH_TIMEZONE): un NULL sin tipo obligaría a Spring a
        // preguntárselo al driver, que en PostgreSQL es un viaje más a la base de datos en cada guardado
        OffsetDateTime finishedAt = WatchProgress.isFinished(positionSeconds, durationSeconds) ? at : null;
        for (int attempt = 1; attempt <= MAX_UPSERT_ATTEMPTS; attempt++) {
            int updated = jdbc.sql("UPDATE " + table
                    + " SET position_seconds = :position, duration_seconds = :duration, updated_at = :updatedAt,"
                    + " finished_at = CASE WHEN finished_at IS NULL THEN :finishedAt"
                    + " WHEN finished_at > :updatedAt THEN :updatedAt"
                    + " ELSE finished_at END"
                    + " WHERE user_id = :userId AND " + titleColumn + " = :titleId")
                    .param("position", positionSeconds)
                    .param("duration", durationSeconds)
                    .param("updatedAt", at)
                    .param("finishedAt", finishedAt, Types.TIMESTAMP_WITH_TIMEZONE)
                    .param("userId", userId)
                    .param("titleId", titleId)
                    .update();
            if (updated == 1) {
                return false;
            }

            int inserted = jdbc.sql("INSERT INTO " + table
                    + " (user_id, " + titleColumn + ", position_seconds, duration_seconds, updated_at, finished_at)"
                    + " VALUES (:userId, :titleId, :position, :duration, :updatedAt, :finishedAt)"
                    + " ON CONFLICT DO NOTHING")
                    .param("userId", userId)
                    .param("titleId", titleId)
                    .param("position", positionSeconds)
                    .param("duration", durationSeconds)
                    .param("updatedAt", at)
                    .param("finishedAt", finishedAt, Types.TIMESTAMP_WITH_TIMEZONE)
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
     *         «Seguir viendo»). Puede estar terminado
     *         ({@link WatchProgress#finished()}) y, aunque no lo esté, haberse
     *         visto alguna vez ({@link WatchProgress#watched()})
     */
    public Optional<WatchProgress> findMovieProgress(long userId, long movieId) {
        return jdbc.sql("SELECT position_seconds, duration_seconds, updated_at, finished_at FROM user_movie_progress"
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
     *         terminado ({@link WatchProgress#finished()}) y, aunque no lo
     *         esté, haberse visto alguna vez ({@link WatchProgress#watched()})
     */
    public Optional<WatchProgress> findEpisodeProgress(long userId, long episodeId) {
        return jdbc.sql("SELECT position_seconds, duration_seconds, updated_at, finished_at FROM user_episode_progress"
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
     *         los episodios con progreso guardado (terminados incluidos; cada
     *         uno dice también si se ha visto alguna vez,
     *         {@link WatchProgress#watched()})
     */
    public Map<Long, WatchProgress> findEpisodeProgressBySeries(long userId, long seriesId) {
        Map<Long, WatchProgress> result = new LinkedHashMap<>();
        jdbc.sql("SELECT p.episode_id, p.position_seconds, p.duration_seconds, p.updated_at, p.finished_at"
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
     * La fila «Seguir viendo»: las {@code limit} tarjetas más recientes del
     * usuario, <b>mezclando películas y series</b>, en una sola consulta
     * ({@code UNION ALL}) que ya trae los datos de cada tarjeta: sin N+1 ni
     * colecciones perezosas.
     *
     * <p><b>Películas</b></p>
     * <p>
     * Las que tienen vídeo y están <b>a medias</b>: posición por debajo del
     * {@value WatchProgress#FINISHED_PERCENT} % de la duración (ver
     * {@link WatchProgress#FINISHED_PERCENT}).
     * </p>
     *
     * <p><b>Series: una tarjeta por serie (criterio de QA-4)</b></p>
     * <ol>
     *   <li>Se toma el <b>último episodio tocado</b>: el de {@code updated_at}
     *       más reciente (desempate: {@code episode_id} mayor) entre los
     *       episodios <b>con vídeo</b> de la serie en los que el usuario tiene
     *       progreso, <b>terminados o no</b>.</li>
     *   <li>Si está a medias, la tarjeta es él, con su progreso
     *       ({@code upNext = false}).</li>
     *   <li>Si está terminado, la tarjeta es el <b>siguiente episodio con
     *       vídeo</b> en orden de temporada y número (puede ser de la temporada
     *       siguiente; los que no tienen vídeo se saltan), con
     *       {@code upNext = true} y su propio progreso solo si lo tiene a
     *       medias (si no lo empezó, o lo tiene terminado, progreso nulo).</li>
     *   <li>Si está terminado y no hay siguiente con vídeo, la serie no sale:
     *       el usuario está al día.</li>
     *   <li>La tarjeta se ordena por la fecha del episodio del paso 1, también
     *       cuando muestra el siguiente: es lo último que hizo el usuario.</li>
     * </ol>
     * <p>
     * <b>Por qué así</b> (y no «el episodio a medias más reciente», que era el
     * criterio anterior): es el de las plataformas grandes y el mismo que la
     * página de la serie ({@code seriesWatchTarget} en
     * {@code frontend/src/lib/playback.ts}). Con el anterior, quien dejaba el
     * 1x01 a medias y luego veía entero el 1x02 encontraba en el inicio «T1:E1»
     * y en la serie «Ver T1:E3»: dos respuestas distintas a «¿por dónde voy?».
     * Un episodio <b>sin vídeo</b> no cuenta como «tocado»: si lo contara, la
     * tarjeta prometería un «Reanudar» imposible o escondería la serie.
     * </p>
     *
     * <p><b>Qué no entra nunca</b></p>
     * <ul>
     *   <li>Títulos sin vídeo ({@code video_url} es opcional desde la
     *       {@code V6}): si el administrador lo quita, la tarjeta se oculta (la
     *       fila de progreso se conserva y vuelve a salir si el vídeo vuelve).</li>
     *   <li>Las series sin episodios están ocultas para los usuarios, pero aquí
     *       no hace falta filtrarlas: un progreso de episodio implica que su
     *       serie tiene al menos ese episodio.</li>
     * </ul>
     *
     * <p><b>Orden y coste</b></p>
     * <p>
     * Por {@code updated_at} descendente (el de la película o el del último
     * episodio tocado); los empates (mismo instante) se deshacen por tipo
     * (películas antes) y por id descendente de la tarjeta, para que dos
     * peticiones seguidas den siempre el mismo orden. Los progresos del usuario
     * se leen por la PK o por {@code idx_*_progress_user_updated_at}; el
     * siguiente episodio, con una sola búsqueda por serie terminada en el índice
     * único {@code uk_episodes_series_season_episode} (que ya está ordenado por
     * serie, temporada y número); y su progreso, por la PK. No hace falta un
     * índice nuevo.
     * </p>
     *
     * @param userId identificador del usuario
     * @param limit  número máximo de elementos (positivo; el servicio fija el
     *               tope que pinte la fila)
     * @return elementos del más reciente al más antiguo; vacía si no hay nada
     *         que seguir viendo
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
    // Historial y cifras del perfil
    // ------------------------------------------------------------------

    /**
     * El historial de visionado del perfil («Vistos recientemente»): las
     * {@code limit} entradas más recientes del usuario, <b>mezclando películas
     * y series</b>, en una sola consulta ({@code UNION ALL}) que ya trae los
     * datos de cada entrada: sin N+1 ni colecciones perezosas.
     *
     * <p><b>Qué entra</b></p>
     * <ul>
     *   <li><b>Una entrada por película</b> con progreso guardado.</li>
     *   <li><b>Una entrada por serie</b>: su <b>último episodio tocado</b>, el de
     *       {@code updated_at} más reciente (desempate: {@code episode_id}
     *       mayor), igual que el paso 1 de {@link #findContinueWatching}. Con
     *       su propio progreso y sin «siguiente episodio»: el historial cuenta
     *       lo que se vio, no lo que toca ver. Una serie con diez episodios
     *       vistos ocupa un solo hueco y no tapa al resto.</li>
     *   <li><b>Terminados o no</b>: cada entrada dice si se ha visto alguna vez
     *       ({@link WatchHistoryItem#watched()}, {@code finished_at}) y si la
     *       posición actual está al final ({@link WatchHistoryItem#finished()},
     *       regla del {@value WatchProgress#FINISHED_PERCENT} %).</li>
     *   <li><b>Con o sin vídeo</b>: a diferencia de «Seguir viendo» (que no
     *       puede prometer un «Reanudar» imposible), lo visto sigue siendo
     *       verdad aunque el administrador quite el vídeo. Por la misma razón,
     *       un episodio sin vídeo <b>sí</b> cuenta como último tocado aquí.</li>
     *   <li>Lo borrado no sale: borrar el título (cascada de la {@code V14}) o
     *       «Quitar de Seguir viendo» borra su fila de progreso, que es la única
     *       fuente (no hay tabla de historial aparte).</li>
     * </ul>
     *
     * <p><b>Orden y coste</b></p>
     * <p>
     * Por {@code updated_at} descendente; los empates se deshacen como en
     * «Seguir viendo» (películas antes, luego id descendente de la película o
     * del episodio), así que dos peticiones seguidas dan siempre el mismo
     * orden. Lee todos los progresos del usuario por la PK (que empieza por
     * {@code user_id}) o por {@code idx_*_progress_user_updated_at}, y ordena en
     * memoria: el {@code ROW_NUMBER} por serie necesita ver todos sus episodios
     * antes de cortar. Son las filas de <b>un</b> usuario (una por título que
     * ha empezado), así que no hace falta un índice nuevo.
     * </p>
     *
     * @param userId identificador del usuario
     * @param limit  número máximo de entradas (positivo; el servicio fija el
     *               tope que pinte el perfil)
     * @return entradas de la más reciente a la más antigua; vacía si el usuario
     *         no tiene progreso
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException si
     *         {@code limit} no es positivo (error de programación, como en
     *         {@link #findContinueWatching})
     */
    public List<WatchHistoryItem> findWatchHistory(long userId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser positivo: " + limit);
        }
        return jdbc.sql(WATCH_HISTORY_SQL)
                .param("userId", userId)
                .param("limit", limit)
                .query((rs, row) -> watchHistoryItem(rs))
                .list();
    }

    /**
     * Las cifras de visionado del perfil, en una sola consulta.
     *
     * <ul>
     *   <li><b>Terminados</b>: películas y episodios <b>vistos alguna vez</b>
     *       ({@code finished_at} no nulo, {@code V15}): los que llegaron al
     *       {@value WatchProgress#FINISHED_PERCENT} % al menos una vez, aunque
     *       ahora se estén volviendo a ver. Cada episodio cuenta por separado,
     *       aunque sean de la misma serie.</li>
     *   <li><b>Tiempo visto</b> (estimación): la suma, en películas y
     *       episodios, de la <b>duración</b> si el título se ha visto alguna vez
     *       y de la <b>posición</b> si no. Solo se guarda el último punto de
     *       cada título, así que no cuenta lo vuelto a ver ni descuenta lo
     *       saltado: es «hasta dónde llegó», no «cuánto rato estuvo». Visto suma
     *       la duración entera (y no la posición) porque quien llega a los
     *       créditos y cierra lo ha visto entero, y volver a empezarlo no le
     *       quita lo que ya vio. Se suma en {@code BIGINT}: no se desborda
     *       aunque el usuario tenga miles de títulos de 24 h.</li>
     *   <li>Cuenta <b>todo</b> el progreso, con o sin vídeo (igual que
     *       {@link #findWatchHistory}).</li>
     * </ul>
     *
     * @param userId identificador del usuario
     * @return las cifras; todo ceros ({@link WatchStats#EMPTY}) si no tiene
     *         progreso (o si el usuario no existe)
     */
    public WatchStats getWatchStats(long userId) {
        return jdbc.sql(WATCH_STATS_SQL)
                .param("userId", userId)
                .query((rs, row) -> new WatchStats(rs.getLong("finished_movies"), rs.getLong("finished_episodes"),
                        rs.getLong("watched_seconds")))
                .single();
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
     * Ojo para «Quitar de Seguir viendo» en la tarjeta de una serie: si el
     * usuario tenía progreso en otros episodios de la misma serie, el más
     * reciente de ellos pasaría a ser el «último tocado» y la serie seguiría
     * en la fila (con él o con su siguiente). Para quitar la serie entera, usa
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
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), instantOrNull(rs, "finished_at"));
    }

    /** Lee una columna {@code TIMESTAMP WITH TIME ZONE} que puede ser nula. */
    private static Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /**
     * Construye el elemento de «Seguir viendo» de la fila actual según su tipo.
     * El progreso de la tarjeta va en {@code position_seconds},
     * {@code duration_seconds} y {@code progress_updated_at}, que son nulos a
     * la vez cuando la tarjeta es un siguiente episodio sin progreso a medias;
     * {@code updated_at} es siempre la fecha que ordena la fila.
     */
    private static ContinueWatchingItem continueWatchingItem(ResultSet rs) throws SQLException {
        OffsetDateTime progressUpdatedAt = rs.getObject("progress_updated_at", OffsetDateTime.class);
        WatchProgress progress = progressUpdatedAt == null ? null
                : new WatchProgress(rs.getInt("position_seconds"), rs.getInt("duration_seconds"),
                        progressUpdatedAt.toInstant(), instantOrNull(rs, "progress_finished_at"));
        Instant updatedAt = rs.getObject("updated_at", OffsetDateTime.class).toInstant();
        String kind = rs.getString("kind");
        return switch (kind) {
            case "MOVIE" -> new ContinueWatchingItem.MovieItem(rs.getLong("item_id"), rs.getString("title"),
                    rs.getString("image_url"), progress);
            case "EPISODE" -> new ContinueWatchingItem.EpisodeItem(rs.getLong("item_id"),
                    rs.getInt("season_number"), rs.getInt("episode_number"), rs.getString("title"),
                    rs.getLong("series_id"), rs.getString("series_title"), rs.getString("image_url"), progress,
                    rs.getBoolean("up_next"), updatedAt);
            default -> throw new IllegalStateException("Tipo de «Seguir viendo» desconocido: " + kind);
        };
    }

    /**
     * Construye la entrada del historial de la fila actual según su tipo (el
     * progreso nunca es nulo: cada entrada sale de una fila de progreso).
     */
    private static WatchHistoryItem watchHistoryItem(ResultSet rs) throws SQLException {
        WatchProgress progress = progress(rs);
        String kind = rs.getString("kind");
        return switch (kind) {
            case "MOVIE" -> new WatchHistoryItem.MovieEntry(rs.getLong("item_id"), rs.getString("title"),
                    rs.getString("image_url"), progress);
            case "EPISODE" -> new WatchHistoryItem.EpisodeEntry(rs.getLong("item_id"),
                    rs.getInt("season_number"), rs.getInt("episode_number"), rs.getString("title"),
                    rs.getLong("series_id"), rs.getString("series_title"), rs.getString("image_url"), progress);
            default -> throw new IllegalStateException("Tipo de entrada del historial desconocido: " + kind);
        };
    }

    /**
     * Condición SQL de «no terminado» sobre el alias indicado: la negación
     * exacta de {@link WatchProgress#isFinished(int, int)}, en aritmética
     * entera (sin desbordamiento: como mucho 86400 × 100). Si las columnas son
     * nulas (un {@code LEFT JOIN} sin fila) el resultado es desconocido, que en
     * un {@code WHEN} o un {@code WHERE} cuenta como falso.
     *
     * @param alias alias de una tabla de progreso en la consulta (constante de
     *              esta clase, nunca un dato del usuario: se concatena en el SQL)
     * @return la condición, lista para insertar en el SQL
     */
    private static String notFinished(String alias) {
        return alias + ".position_seconds * 100 < " + alias + ".duration_seconds * " + WatchProgress.FINISHED_PERCENT;
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
