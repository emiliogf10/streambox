package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Objects;

/**
 * Un elemento de la fila «Seguir viendo»: una película a medio ver o la
 * tarjeta de una serie (el episodio por el que va o el siguiente que le toca),
 * con lo justo para pintarla y para su barra de progreso. Lo devuelve
 * {@link WatchProgressRepository#findContinueWatching(long, int)}, que mezcla
 * las dos clases en una sola lista ordenada.
 *
 * <p>
 * Es una interfaz <b>sellada</b> con dos {@code record}: el servicio la recorre
 * con un {@code switch} por tipo ({@code case MovieItem m -> ...}) y el
 * compilador avisa si algún día se añade una tercera clase y falta su caso. No
 * es un DTO de la API: los datos salen de una sola consulta (sin N+1) y el
 * servicio construye con ellos sus DTOs.
 * </p>
 *
 * <h2>Por qué el progreso puede ser nulo (y por qué no {@code Optional})</h2>
 * <p>
 * Cuando el usuario ha <b>terminado</b> el último episodio que tocó de una
 * serie, la tarjeta es el <b>siguiente</b> ({@link EpisodeItem#upNext()}), que
 * puede no haber empezado nunca: no hay posición ni duración que pintar. Ese es
 * el único caso de progreso nulo y lo garantiza el constructor de
 * {@link EpisodeItem}. Se usa {@code null} y no {@link java.util.Optional}
 * porque {@code Optional} no está pensado como campo de un {@code record} (no
 * es serializable y complica {@code equals}); quien lo lea decide con
 * {@code upNext} antes de tocar el progreso.
 * </p>
 */
public sealed interface ContinueWatchingItem
        permits ContinueWatchingItem.MovieItem, ContinueWatchingItem.EpisodeItem {

    /**
     * Progreso guardado del título de la tarjeta.
     *
     * @return posición, duración y fecha de su último guardado; <b>nulo solo</b>
     *         en un {@link EpisodeItem} con {@code upNext = true} cuyo episodio
     *         no tiene progreso a medias (nunca en una película)
     */
    WatchProgress progress();

    /**
     * Instante que ordena la fila: el último guardado del usuario en la
     * película o, en una serie, en el <b>último episodio que tocó</b> (aunque la
     * tarjeta muestre el siguiente). Por eso no siempre coincide con
     * {@code progress().updatedAt()}.
     *
     * @return instante del último guardado que sitúa la tarjeta en la fila
     */
    Instant updatedAt();

    /**
     * Película a medio ver.
     *
     * @param movieId  identificador de la película
     * @param title    título de la película
     * @param imageUrl portada de la película ({@code movies.image_url})
     * @param progress progreso guardado (nunca nulo ni terminado)
     */
    record MovieItem(long movieId, String title, String imageUrl, WatchProgress progress)
            implements ContinueWatchingItem {

        /** Rechaza valores nulos: todas las columnas de origen son {@code NOT NULL}. */
        public MovieItem {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(imageUrl, "imageUrl");
            Objects.requireNonNull(progress, "progress");
        }

        /**
         * En una película, la fecha que ordena es la de su propio progreso.
         *
         * @return {@code progress().updatedAt()}
         */
        @Override
        public Instant updatedAt() {
            return progress.updatedAt();
        }
    }

    /**
     * La tarjeta de una serie: un episodio, con los datos de su serie. Los
     * episodios no tienen imagen propia (ver {@code V3}): la tarjeta usa la de
     * la serie.
     *
     * <p>
     * Dos casos (ver {@link WatchProgressRepository#findContinueWatching}):
     * </p>
     * <ul>
     *   <li>{@code upNext = false}: el último episodio que tocó el usuario, a
     *       medias; {@code progress} es el suyo y su fecha es {@code updatedAt}.</li>
     *   <li>{@code upNext = true}: el usuario terminó el último que tocó y esta
     *       es el <b>siguiente</b> con vídeo. {@code progress} es el del
     *       siguiente si lo tiene a medias, o nulo si no lo empezó (o lo tiene
     *       terminado); {@code updatedAt} es la del episodio terminado.</li>
     * </ul>
     *
     * @param episodeId      identificador del episodio de la tarjeta
     * @param seasonNumber   temporada del episodio
     * @param episodeNumber  número del episodio dentro de la temporada
     * @param episodeTitle   título del episodio
     * @param seriesId       identificador de la serie
     * @param seriesTitle    título de la serie
     * @param seriesImageUrl portada de la serie ({@code series.image_url})
     * @param progress       progreso a medias del episodio de la tarjeta; nulo
     *                       solo si {@code upNext}
     * @param upNext         {@code true} si es el siguiente a uno terminado
     *                       («Siguiente episodio»), {@code false} si es el que
     *                       el usuario dejó a medias
     * @param updatedAt      último guardado del último episodio tocado de la
     *                       serie (ordena la fila)
     */
    record EpisodeItem(long episodeId, int seasonNumber, int episodeNumber, String episodeTitle,
            long seriesId, String seriesTitle, String seriesImageUrl, WatchProgress progress,
            boolean upNext, Instant updatedAt)
            implements ContinueWatchingItem {

        /**
         * Rechaza valores nulos (todas las columnas de origen son
         * {@code NOT NULL}) y combinaciones que la consulta no puede producir:
         * progreso nulo sin {@code upNext}, progreso terminado (lo terminado
         * nunca se pinta como «a medias»), o un episodio a medias cuya fecha no
         * es la que ordena la fila.
         */
        public EpisodeItem {
            Objects.requireNonNull(episodeTitle, "episodeTitle");
            Objects.requireNonNull(seriesTitle, "seriesTitle");
            Objects.requireNonNull(seriesImageUrl, "seriesImageUrl");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (progress == null) {
                if (!upNext) {
                    throw new IllegalArgumentException(
                            "Solo el siguiente episodio (upNext) puede no tener progreso");
                }
            } else {
                if (progress.finished()) {
                    throw new IllegalArgumentException("La tarjeta no puede llevar un progreso terminado");
                }
                if (!upNext && !progress.updatedAt().equals(updatedAt)) {
                    throw new IllegalArgumentException(
                            "Un episodio a medias se ordena por la fecha de su propio progreso");
                }
            }
        }
    }
}
