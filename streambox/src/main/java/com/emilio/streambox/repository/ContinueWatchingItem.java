package com.emilio.streambox.repository;

import java.util.Objects;

/**
 * Un elemento de la fila «Seguir viendo»: una película o un episodio a medio
 * ver, con lo justo para pintar su tarjeta y su barra de progreso. Lo devuelve
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
 */
public sealed interface ContinueWatchingItem
        permits ContinueWatchingItem.MovieItem, ContinueWatchingItem.EpisodeItem {

    /**
     * @return progreso guardado (posición, duración y fecha del último guardado)
     */
    WatchProgress progress();

    /**
     * Película a medio ver.
     *
     * @param movieId  identificador de la película
     * @param title    título de la película
     * @param imageUrl portada de la película ({@code movies.image_url})
     * @param progress progreso guardado
     */
    record MovieItem(long movieId, String title, String imageUrl, WatchProgress progress)
            implements ContinueWatchingItem {

        /** Rechaza valores nulos: todas las columnas de origen son {@code NOT NULL}. */
        public MovieItem {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(imageUrl, "imageUrl");
            Objects.requireNonNull(progress, "progress");
        }
    }

    /**
     * Episodio a medio ver, con los datos de su serie. Los episodios no tienen
     * imagen propia (ver {@code V3}): la tarjeta usa la de la serie.
     *
     * @param episodeId      identificador del episodio
     * @param seasonNumber   temporada del episodio
     * @param episodeNumber  número del episodio dentro de la temporada
     * @param episodeTitle   título del episodio
     * @param seriesId       identificador de la serie
     * @param seriesTitle    título de la serie
     * @param seriesImageUrl portada de la serie ({@code series.image_url})
     * @param progress       progreso guardado del episodio
     */
    record EpisodeItem(long episodeId, int seasonNumber, int episodeNumber, String episodeTitle,
            long seriesId, String seriesTitle, String seriesImageUrl, WatchProgress progress)
            implements ContinueWatchingItem {

        /** Rechaza valores nulos: todas las columnas de origen son {@code NOT NULL}. */
        public EpisodeItem {
            Objects.requireNonNull(episodeTitle, "episodeTitle");
            Objects.requireNonNull(seriesTitle, "seriesTitle");
            Objects.requireNonNull(seriesImageUrl, "seriesImageUrl");
            Objects.requireNonNull(progress, "progress");
        }
    }
}
