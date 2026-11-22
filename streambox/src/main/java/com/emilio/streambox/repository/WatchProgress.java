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
 * @param positionSeconds segundo por el que va ({@code 0..durationSeconds})
 * @param durationSeconds duración del vídeo reproducido
 *                        ({@code 1..}{@value #MAX_DURATION_SECONDS})
 * @param updatedAt       instante del último guardado
 */
public record WatchProgress(int positionSeconds, int durationSeconds, Instant updatedAt) {

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
     *         fuera de rango
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
