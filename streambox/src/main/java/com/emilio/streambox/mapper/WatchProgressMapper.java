package com.emilio.streambox.mapper;

import java.util.List;
import java.util.Map;

import com.emilio.streambox.dto.ContinueWatchingItemResponse;
import com.emilio.streambox.dto.ContinueWatchingType;
import com.emilio.streambox.dto.EpisodeProgressResponse;
import com.emilio.streambox.dto.WatchHistoryItemResponse;
import com.emilio.streambox.dto.WatchProgressResponse;
import com.emilio.streambox.dto.WatchStatsResponse;
import com.emilio.streambox.repository.ContinueWatchingItem;
import com.emilio.streambox.repository.WatchHistoryItem;
import com.emilio.streambox.repository.WatchProgress;
import com.emilio.streambox.repository.WatchStats;

/**
 * Convierte los valores de la capa de datos del progreso de visionado
 * ({@link WatchProgress}, {@link ContinueWatchingItem}, {@link WatchHistoryItem},
 * {@link WatchStats}) en los DTOs de la API.
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
     * @return DTO con {@code finished} y {@code watched} ya calculados
     */
    public static WatchProgressResponse toResponse(WatchProgress progress) {

        return new WatchProgressResponse(progress.positionSeconds(), progress.durationSeconds(),
                progress.finished(), progress.watched(), progress.updatedAt());
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
                        entry.getValue().watched(), entry.getValue().updatedAt()))
                .toList();
    }

    /**
     * Un elemento de «Seguir viendo». El {@code switch} sobre la interfaz
     * sellada obliga a tratar los dos tipos: si algún día hubiera un tercero,
     * esto dejaría de compilar en vez de olvidarlo.
     *
     * <p>
     * El progreso de un episodio puede ser nulo: es la tarjeta «siguiente
     * episodio» ({@code upNext}) de uno que no se ha empezado. Entonces la
     * posición y la duración salen como {@code null} en el JSON (el cliente no
     * pinta barra). Una película siempre tiene progreso y nunca es
     * {@code upNext}. La fecha sale siempre de {@link ContinueWatchingItem#updatedAt()},
     * que en una serie es la del último episodio tocado y no la del progreso
     * de la tarjeta.
     * </p>
     *
     * @param item película a medio ver o tarjeta de una serie
     * @return su tarjeta
     */
    public static ContinueWatchingItemResponse toResponse(ContinueWatchingItem item) {

        return switch (item) {
            case ContinueWatchingItem.MovieItem movie -> new ContinueWatchingItemResponse(
                    ContinueWatchingType.MOVIE, movie.movieId(), movie.title(), movie.imageUrl(),
                    null, null, null, null,
                    movie.progress().positionSeconds(), movie.progress().durationSeconds(), false,
                    movie.updatedAt());
            case ContinueWatchingItem.EpisodeItem episode -> {
                WatchProgress progress = episode.progress();
                yield new ContinueWatchingItemResponse(
                        ContinueWatchingType.EPISODE, episode.episodeId(), episode.episodeTitle(),
                        episode.seriesImageUrl(), episode.seasonNumber(), episode.episodeNumber(),
                        episode.seriesId(), episode.seriesTitle(),
                        progress == null ? null : progress.positionSeconds(),
                        progress == null ? null : progress.durationSeconds(),
                        episode.upNext(), episode.updatedAt());
            }
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

    /**
     * Una entrada del historial de visionado. Como en «Seguir viendo», el
     * {@code switch} sobre la interfaz sellada obliga a tratar los dos tipos.
     *
     * <p>
     * A diferencia de la tarjeta de «Seguir viendo», el progreso nunca es nulo
     * (toda entrada sale de una fila de progreso) y {@code finished} y
     * {@code watched} se copian de {@link WatchHistoryItem#finished()} y
     * {@link WatchHistoryItem#watched()}, los únicos criterios en Java: el
     * mapper no los recalcula.
     * </p>
     *
     * @param item película o último episodio tocado de una serie
     * @return su entrada en la API (campos de serie a {@code null} en una película)
     */
    public static WatchHistoryItemResponse toHistoryResponse(WatchHistoryItem item) {

        WatchProgress progress = item.progress();
        return switch (item) {
            case WatchHistoryItem.MovieEntry movie -> new WatchHistoryItemResponse(
                    ContinueWatchingType.MOVIE, movie.movieId(), movie.title(), movie.imageUrl(),
                    null, null, null, null,
                    progress.positionSeconds(), progress.durationSeconds(), item.finished(), item.watched(),
                    item.updatedAt());
            case WatchHistoryItem.EpisodeEntry episode -> new WatchHistoryItemResponse(
                    ContinueWatchingType.EPISODE, episode.episodeId(), episode.episodeTitle(),
                    episode.seriesImageUrl(), episode.seasonNumber(), episode.episodeNumber(),
                    episode.seriesId(), episode.seriesTitle(),
                    progress.positionSeconds(), progress.durationSeconds(), item.finished(), item.watched(),
                    item.updatedAt());
        };
    }

    /**
     * El historial completo, en el mismo orden.
     *
     * @param items entradas del repositorio
     * @return sus entradas en la API
     */
    public static List<WatchHistoryItemResponse> toHistoryResponseList(List<WatchHistoryItem> items) {

        return items.stream().map(WatchProgressMapper::toHistoryResponse).toList();
    }

    /**
     * Las cifras de visionado del perfil.
     *
     * @param stats cifras del repositorio
     * @return el DTO con los mismos valores
     */
    public static WatchStatsResponse toStatsResponse(WatchStats stats) {

        return new WatchStatsResponse(stats.finishedMovies(), stats.finishedEpisodes(), stats.watchedSeconds());
    }
}
