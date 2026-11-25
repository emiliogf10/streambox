package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Objects;

/**
 * Progreso de visionado de un usuario en <b>un</b> título (película o
 * episodio), tal como está guardado en {@code user_movie_progress} o
 * {@code user_episode_progress} (migración {@code V14}).
 *
 * <p>
 * Es un valor de la capa de datos, no un DTO de la API: el servicio lo usa
 * para construir los suyos. Lo devuelve {@link WatchProgressRepository} y
 * también lo lleva cada elemento de {@link ContinueWatchingItem}.
 * </p>
 *
 * <h2>Unidades</h2>
 * <p>
 * Todo en <b>segundos enteros</b> del vídeo reproducido. {@code durationSeconds}
 * es la duración que conoce el reproductor y <b>no</b> tiene por qué coincidir
 * con {@code movies.duration} / {@code episodes.duration}, que van en minutos y
 * las escribe el administrador a mano.
 * </p>
 *
 * <h2>«Terminado» y «visto» no son lo mismo (migración {@code V15})</h2>
 * <ul>
 *   <li>{@link #finished()}: la posición <b>actual</b> llega al
 *       {@value #FINISHED_PERCENT} %. Se pierde si el usuario vuelve a verlo y
 *       para antes. Decide «Seguir viendo» y si el reproductor empieza de
 *       cero.</li>
 *   <li>{@link #watched()}: lo terminó <b>alguna vez</b>
 *       ({@code finishedAt} no es nulo). No se pierde al volver a verlo. Es lo
 *       que cuentan el historial y las cifras del perfil.</li>
 * </ul>
 * <p>
 * Todo lo terminado está visto (la base de datos lo exige con
 * {@code ck_*_progress_finished_has_finished_at} y este {@code record} lo
 * comprueba); al revés no: visto y ahora a medias es justo volver a verlo.
 * </p>
 *
 * @param positionSeconds segundo por el que va ({@code 0..durationSeconds})
 * @param durationSeconds duración del vídeo reproducido
 *                        ({@code 1..}{@value #MAX_DURATION_SECONDS})
 * @param updatedAt       instante del último guardado
 * @param finishedAt      instante en que se terminó <b>por primera vez</b>
 *                        (columna {@code finished_at}), o {@code null} si
 *                        nunca se ha terminado. Nunca posterior a
 *                        {@code updatedAt}
 */
public record WatchProgress(int positionSeconds, int durationSeconds, Instant updatedAt, Instant finishedAt) {

    /**
     * Porcentaje de la duración a partir del cual un título se considera
     * <b>terminado</b>: {@code positionSeconds >= 95 % de durationSeconds}.
     *
     * <p>
     * Un título terminado <b>no sale en «Seguir viendo»</b> (su fila sigue en la
     * tabla). Se usa un porcentaje y no «a menos de N segundos del final»
     * porque los créditos crecen con la duración: en un episodio de 20 min el
     * 5 % final es 1 min y en una película de 2 h son 6 min, más o menos lo que
     * duran. Es el mismo criterio en Java ({@link #finished()},
     * {@link #isFinished(int, int)}) y en SQL
     * ({@link WatchProgressRepository#findContinueWatching}), que lo comparan
     * con aritmética entera ({@code position * 100 >= duration * 95}) para no
     * depender del redondeo de cada motor.
     * </p>
     *
     * <p><b>Cambiarlo exige una migración nueva</b></p>
     * <p>
     * El 95 <b>no</b> vive solo aquí: está escrito a mano en la migración
     * {@code V15__subtitles_and_finished_at.sql}, en el {@code CHECK}
     * {@code ck_movie_progress_finished_has_finished_at} y en
     * {@code ck_episode_progress_finished_has_finished_at}
     * ({@code finished_at IS NOT NULL OR position_seconds * 100 < duration_seconds * 95})
     * y en la aritmética del relleno de {@code finished_at} de esa misma
     * migración. La consulta de «Seguir viendo» sí usa esta constante, así que
     * se adapta sola; la base de datos, no. Como la {@code V15} ya está
     * aplicada, <b>no se edita</b> (Flyway rechazaría el checksum): cambiar
     * este valor exige una migración {@code V<N+1>} que haga dos cosas.
     * </p>
     * <ol>
     *   <li>Reemplazar los dos {@code CHECK} ({@code DROP CONSTRAINT} +
     *       {@code ADD CONSTRAINT} con el valor nuevo).</li>
     *   <li>Decidir qué hacer con {@code finished_at} de las filas cuya
     *       posición queda entre el valor antiguo y el nuevo, y hacerlo
     *       <b>antes</b> de añadir el {@code CHECK} nuevo.</li>
     * </ol>
     * <p>
     * Qué falla si solo se cambia la constante:
     * </p>
     * <ul>
     *   <li><b>Al subirlo</b> (por ejemplo, al 97 %): falla la <b>escritura</b>.
     *       Un guardado al 96 % de un título nunca terminado ya no es
     *       «terminado» en Java, así que {@code WatchProgressRepository} lo
     *       guarda con {@code finished_at} nulo; pero el {@code CHECK} de la
     *       {@code V15} sigue exigiéndolo a partir del 95 % y rechaza el
     *       {@code INSERT}/{@code UPDATE} (violación de restricción: un 500 al
     *       guardar el progreso). El constructor no falla: una fila existente
     *       al 96 % con {@code finished_at} queda como «vista pero no
     *       terminada», que es un estado válido. Lo que hay que decidir en la
     *       migración es si esas filas, marcadas como vistas con el criterio
     *       antiguo, lo siguen estando (lo razonable: no se puede saber si el
     *       usuario llegó más allá) o se les quita {@code finished_at}.</li>
     *   <li><b>Al bajarlo</b> (por ejemplo, al 90 %): falla la <b>lectura</b>.
     *       Las filas que ya existen entre el 90 % y el 95 % no tienen
     *       {@code finished_at} (la {@code V15} no se lo exigía), pero para
     *       Java ya están terminadas, así que este constructor lanza
     *       {@link IllegalArgumentException} al leerlas (500 en «Seguir viendo»,
     *       el historial, las cifras y la página del título). Guardar sí
     *       funciona, porque Java pone {@code finished_at}. La migración debe
     *       rellenar {@code finished_at = updated_at} en esas filas (igual que
     *       el relleno de la {@code V15}) y después cambiar el {@code CHECK};
     *       si solo hiciera el relleno, el {@code CHECK} quedaría más laxo que
     *       Java, sin romper nada pero sin proteger el intervalo nuevo.</li>
     * </ul>
     * <p>
     * Los tests que fijan el borde del 95 % en la base de datos
     * ({@code FlywaySchemaIntegrationTest}, {@code PostgresSchemaIntegrationTest},
     * {@code PostgresWatchedAtMigrationIntegrationTest},
     * {@code WatchProgressQueryContract}) también habría que revisarlos.
     * </p>
     */
    public static final int FINISHED_PERCENT = 95;

    /**
     * Duración máxima admitida: 24 h. La misma que el {@code CHECK}
     * {@code ck_*_duration} de la {@code V14}; el servicio debería validarla
     * antes (400) para no llegar a la violación de la base de datos.
     */
    public static final int MAX_DURATION_SECONDS = 86_400;

    /**
     * Comprueba que el valor es coherente (las mismas reglas que los
     * {@code CHECK} de la tabla): lo que sale de la base de datos siempre lo
     * cumple.
     *
     * @throws IllegalArgumentException si la posición o la duración están
     *         fuera de rango, si {@code finishedAt} es posterior a
     *         {@code updatedAt} o si está terminado sin {@code finishedAt}
     * @throws NullPointerException     si {@code updatedAt} es nulo
     */
    public WatchProgress {
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (durationSeconds < 1 || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("Duración fuera de rango: " + durationSeconds);
        }
        if (positionSeconds < 0 || positionSeconds > durationSeconds) {
            throw new IllegalArgumentException("Posición fuera de rango: " + positionSeconds);
        }
        if (finishedAt != null && finishedAt.isAfter(updatedAt)) {
            throw new IllegalArgumentException("Terminado (" + finishedAt + ") después del último guardado ("
                    + updatedAt + ")");
        }
        if (finishedAt == null && isFinished(positionSeconds, durationSeconds)) {
            throw new IllegalArgumentException("Un progreso terminado debe tener la fecha en que se terminó");
        }
    }

    /**
     * Indica si el título se ha terminado <b>alguna vez</b>, aunque ahora se
     * esté volviendo a ver y la posición actual no llegue al 95 % (ver la
     * clase).
     *
     * @return {@code true} si {@code finishedAt} no es nulo
     */
    public boolean watched() {
        return finishedAt != null;
    }

    /**
     * Indica si este progreso cuenta como título terminado
     * (ver {@link #FINISHED_PERCENT}).
     *
     * @return {@code true} si la posición alcanza el 95 % de la duración
     */
    public boolean finished() {
        return isFinished(positionSeconds, durationSeconds);
    }

    /**
     * Criterio de «terminado» para unos valores sueltos (por ejemplo, los que
     * acaba de enviar el reproductor), idéntico al de la consulta de «Seguir
     * viendo». Se calcula en {@code long} para que no haya desbordamiento.
     *
     * @param positionSeconds segundo por el que va
     * @param durationSeconds duración del vídeo (positiva)
     * @return {@code true} si {@code positionSeconds * 100 >= durationSeconds * 95}
     */
    public static boolean isFinished(int positionSeconds, int durationSeconds) {
        return (long) positionSeconds * 100 >= (long) durationSeconds * FINISHED_PERCENT;
    }
}
