package com.emilio.streambox.mapper;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.EpisodeResponse;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Series;

/**
 * Convierte entre la entidad {@link Episode} y los DTO de la API.
 *
 * <p>
 * {@link #toResponse(Episode)} no toca la serie del episodio
 * ({@code Episode.series} es {@code LAZY}), así que convertir una lista de
 * episodios no dispara ninguna consulta adicional.
 * </p>
 */
public final class EpisodeMapper {

    private EpisodeMapper() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Crea un episodio nuevo de la serie indicada.
     *
     * @param request datos recibidos del cliente
     * @param series  serie a la que pertenece (puede ser una referencia sin cargar)
     * @return entidad {@link Episode} sin guardar
     */
    public static Episode toEntity(EpisodeRequest request, Series series) {

        Episode episode = new Episode();
        episode.setSeries(series);
        copyFields(request, episode);
        return episode;
    }

    /**
     * Copia sobre un episodio existente los campos editables del DTO. No cambia
     * la serie, el identificador ni la fecha de creación.
     *
     * @param request datos nuevos recibidos del cliente
     * @param episode episodio que se actualiza
     */
    public static void updateEntity(EpisodeRequest request, Episode episode) {

        copyFields(request, episode);
    }

    /**
     * Convierte un episodio en el DTO que se devuelve al cliente.
     *
     * @param episode entidad a convertir
     * @return DTO del episodio
     */
    public static EpisodeResponse toResponse(Episode episode) {

        return new EpisodeResponse(
                episode.getId(),
                episode.getSeasonNumber(),
                episode.getEpisodeNumber(),
                episode.getTitle(),
                episode.getDescription(),
                episode.getDuration(),
                episode.getVideoUrl());
    }

    /**
     * Copia los campos editables.
     *
     * <p>
     * La sinopsis es opcional: una cadena en blanco se guarda como
     * {@code null}, para que "sin sinopsis" tenga una sola representación y el
     * cliente solo tenga que comprobar {@code null} (el contrato dice
     * "{@code description} null si no hay").
     * </p>
     */
    private static void copyFields(EpisodeRequest request, Episode episode) {

        episode.setSeasonNumber(request.seasonNumber());
        episode.setEpisodeNumber(request.episodeNumber());
        episode.setTitle(request.title());
        episode.setDescription(request.description() == null || request.description().isBlank()
                ? null
                : request.description());
        episode.setDuration(request.duration());
        episode.setVideoUrl(request.videoUrl());
    }
}
