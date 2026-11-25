package com.emilio.streambox.repository;

/**
 * Cifras de visionado de un usuario para su perfil: cuántas películas y
 * episodios ha terminado y cuánto tiempo ha visto en total. Lo devuelve
 * {@link WatchProgressRepository#getWatchStats(long)}.
 *
 * <p>
 * Cuenta <b>todo</b> el progreso guardado, tenga hoy vídeo el título o no (como
 * el historial: lo visto sigue siendo verdad aunque el administrador quite el
 * vídeo). Lo que se borra (el título, o el progreso con «Quitar de Seguir
 * viendo») deja de contar.
 * </p>
 *
 * <h2>«Terminadas» son las vistas alguna vez</h2>
 * <p>
 * Desde la migración {@code V15} cuentan los títulos <b>vistos</b>
 * ({@link WatchProgress#watched()}: {@code finished_at} no es nulo), no los que
 * tienen la posición actual al final: volver a ver una película y parar en el
 * minuto 2 ya no la descuenta. Los nombres de los campos no cambian (son los
 * de la API).
 * </p>
 *
 * <h2>{@code watchedSeconds} es una estimación</h2>
 * <p>
 * Solo se guarda el <b>último punto</b> de cada título, no las sesiones: un
 * título visto alguna vez suma su duración entera y uno que no, su posición.
 * Lo vuelto a ver, los saltos hacia delante y lo rebobinado no se reflejan. Por
 * eso es {@code long}: con el tope de 24 h por título, unos 25 000 títulos ya
 * desbordarían un {@code int}.
 * </p>
 *
 * @param finishedMovies   películas vistas alguna vez (llegaron al
 *                         {@link WatchProgress#FINISHED_PERCENT} % al menos una vez)
 * @param finishedEpisodes episodios vistos alguna vez (cada uno cuenta, sean de
 *                         la misma serie o no)
 * @param watchedSeconds   tiempo visto estimado, en segundos (ver arriba)
 */
public record WatchStats(long finishedMovies, long finishedEpisodes, long watchedSeconds) {

    /** Las cifras de un usuario sin ningún progreso. */
    public static final WatchStats EMPTY = new WatchStats(0, 0, 0);

    /**
     * Comprueba que ninguna cifra es negativa (lo que sale de la base de datos
     * siempre lo cumple: los {@code CHECK} de la {@code V14} lo garantizan).
     *
     * @throws IllegalArgumentException si alguna cifra es negativa
     */
    public WatchStats {
        if (finishedMovies < 0 || finishedEpisodes < 0 || watchedSeconds < 0) {
            throw new IllegalArgumentException("Cifras de visionado negativas: " + finishedMovies + ", "
                    + finishedEpisodes + ", " + watchedSeconds);
        }
    }
}
