package com.emilio.streambox.mapper;

import java.util.List;
import java.util.Map;

import com.emilio.streambox.dto.ContinueWatchingItemResponse;
import com.emilio.streambox.dto.ContinueWatchingType;
import com.emilio.streambox.dto.EpisodeProgressResponse;
import com.emilio.streambox.dto.WatchProgressResponse;
import com.emilio.streambox.repository.ContinueWatchingItem;
import com.emilio.streambox.repository.WatchProgress;

/**
 * Convierte los valores de la capa de datos del progreso de visionado
 * ({@link WatchProgress}, {@link ContinueWatchingItem}) en los DTOs de la API.
 *
 * <p>
 * No hay entidades: los valores ya vienen completos de
 * {@code WatchProgressRepository} (una consulta, sin carga diferida), así que
 * convertirlos no lanza ninguna sentencia más.
 * </p>
 */
public final class WatchProgressMapper {

    private WatchProgressMapper() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Progreso de un título, para reanudarlo.
     *
     * @param progress progreso guardado
     * @return DTO con {@code finished} ya calculado
     */
    public static WatchProgressResponse toResponse(WatchProgress progress) {

        return new WatchProgressResponse(progress.positionSeconds(), progress.durationSeconds(),
                progress.finished(), progress.updatedAt());
    }

    /**
     * Progreso de los episodios de una serie, en el orden del mapa (temporada
     * y número, como lo devuelve el repositorio).
     *
     * @param byEpisode id del episodio → progreso
     * @return un elemento por episodio con progreso
     */
    public static List<EpisodeProgressResponse> toEpisodeResponses(Map<Long, WatchProgress> byEpisode) {

        return byEpisode.entrySet().stream()
                .map(entry -> new EpisodeProgressResponse(entry.getKey(), entry.getValue().positionSeconds(),
                        entry.getValue().durationSeconds(), entry.getValue().finished(),
                        entry.getValue().updatedAt()))
                .toList();
    }

    /**
     * Un elemento de «Seguir viendo». El {@code switch} sobre la interfaz
     * sellada obliga a tratar los dos tipos: si algún día hubiera un tercero,
     * esto dejaría de compilar en vez de olvidarlo.
     *
     * @param item película o episodio a medio ver
     * @return su tarjeta
     */
    public static ContinueWatchingItemResponse toResponse(ContinueWatchingItem item) {

        WatchProgress progress = item.progress();
        return switch (item) {
            case ContinueWatchingItem.MovieItem movie -> new ContinueWatchingItemResponse(
                    ContinueWatchingType.MOVIE, movie.movieId(), movie.title(), movie.imageUrl(),
                    null, null, null, null,
                    progress.positionSeconds(), progress.durationSeconds(), progress.updatedAt());
            case ContinueWatchingItem.EpisodeItem episode -> new ContinueWatchingItemResponse(
                    ContinueWatchingType.EPISODE, episode.episodeId(), episode.episodeTitle(),
                    episode.seriesImageUrl(), episode.seasonNumber(), episode.episodeNumber(),
                    episode.seriesId(), episode.seriesTitle(),
                    progress.positionSeconds(), progress.durationSeconds(), progress.updatedAt());
        };
    }

    /**
     * La fila «Seguir viendo» completa, en el mismo orden.
     *
     * @param items elementos del repositorio
     * @return sus tarjetas
     */
    public static List<ContinueWatchingItemResponse> toResponseList(List<ContinueWatchingItem> items) {

        return items.stream().map(WatchProgressMapper::toResponse).toList();
    }
}
