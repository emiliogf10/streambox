package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Objects;

/**
 * Una entrada del <b>historial de visionado</b> del perfil («Vistos
 * recientemente»): una película con progreso guardado o, por cada serie, el
 * <b>último episodio que tocó</b> el usuario, con su progreso tal cual (a
 * medias o terminado). Lo devuelve
 * {@link WatchProgressRepository#findWatchHistory(long, int)}, que mezcla las
 * dos clases en una sola lista ordenada.
 *
 * <p>
 * Igual que {@link ContinueWatchingItem}, es una interfaz <b>sellada</b> con dos
 * {@code record} para que el servicio la recorra con un {@code switch} por tipo
 * y el compilador avise si falta un caso. No es un DTO de la API.
 * </p>
 *
 * <h2>Por qué un tipo propio y no reutilizar {@link ContinueWatchingItem}</h2>
 * <ul>
 *   <li>Su significado es otro: «Seguir viendo» dice qué ver <b>ahora</b> (por
 *       eso rechaza en su constructor los progresos terminados y tiene el
 *       «siguiente episodio», {@code upNext}, con progreso opcional); el
 *       historial dice qué se <b>vio</b>, terminado o no, y siempre con el
 *       progreso real de la entrada. Reutilizarlo obligaría a relajar sus
 *       invariantes y a que {@code upNext} fuera siempre {@code false}.</li>
 *   <li>Los nombres de los {@code record} ({@link MovieEntry},
 *       {@link EpisodeEntry}) son distintos de {@code MovieItem} y
 *       {@code EpisodeItem} a propósito: un servicio que use los dos tipos
 *       puede importar los cuatro sin choques de nombres.</li>
 * </ul>
 *
 * <h2>«Terminado» y «visto» no son campos, se derivan</h2>
 * <p>
 * {@link #finished()} sale de {@link WatchProgress#finished()} (regla del
 * {@value WatchProgress#FINISHED_PERCENT} %) y {@link #watched()} de
 * {@link WatchProgress#watched()} ({@code finished_at}, {@code V15}): así hay
 * <b>un único</b> criterio en Java y no puede existir una entrada cuyo
 * indicador contradiga su progreso.
 * </p>
 */
public sealed interface WatchHistoryItem
        permits WatchHistoryItem.MovieEntry, WatchHistoryItem.EpisodeEntry {

    /**
     * Progreso guardado de la entrada (nunca nulo: toda entrada sale de una
     * fila de progreso).
     *
     * @return posición, duración y fecha del último guardado
     */
    WatchProgress progress();

    /**
     * Indica si la entrada cuenta como vista entera (ver
     * {@link WatchProgress#FINISHED_PERCENT}).
     *
     * @return {@code progress().finished()}
     */
    default boolean finished() {
        return progress().finished();
    }

    /**
     * Indica si la entrada se ha terminado <b>alguna vez</b> (migración
     * {@code V15}): no se pierde al volver a verla y parar antes del final.
     * Es lo que debe mostrar el historial como «Vista»; {@link #finished()}
     * solo dice si la posición actual llega al final.
     *
     * @return {@code progress().watched()}
     */
    default boolean watched() {
        return progress().watched();
    }

    /**
     * Instante que ordena el historial: el último guardado de la película o del
     * episodio de la entrada (en el historial siempre coinciden, a diferencia
     * de «Seguir viendo»).
     *
     * @return {@code progress().updatedAt()}
     */
    default Instant updatedAt() {
        return progress().updatedAt();
    }

    /**
     * Película con progreso guardado (a medias o terminada, con o sin vídeo).
     *
     * @param movieId  identificador de la película
     * @param title    título de la película
     * @param imageUrl portada de la película ({@code movies.image_url})
     * @param progress progreso guardado
     */
    record MovieEntry(long movieId, String title, String imageUrl, WatchProgress progress)
            implements WatchHistoryItem {

        /** Rechaza valores nulos: todas las columnas de origen son {@code NOT NULL}. */
        public MovieEntry {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(imageUrl, "imageUrl");
            Objects.requireNonNull(progress, "progress");
        }
    }

    /**
     * El último episodio que tocó el usuario de una serie (a medias o
     * terminado, con o sin vídeo), con los datos de su serie. Los episodios no
     * tienen imagen propia (ver {@code V3}): la entrada usa la de la serie.
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
    record EpisodeEntry(long episodeId, int seasonNumber, int episodeNumber, String episodeTitle,
            long seriesId, String seriesTitle, String seriesImageUrl, WatchProgress progress)
            implements WatchHistoryItem {

        /** Rechaza valores nulos: todas las columnas de origen son {@code NOT NULL}. */
        public EpisodeEntry {
            Objects.requireNonNull(episodeTitle, "episodeTitle");
            Objects.requireNonNull(seriesTitle, "seriesTitle");
            Objects.requireNonNull(seriesImageUrl, "seriesImageUrl");
            Objects.requireNonNull(progress, "progress");
        }
    }
}
