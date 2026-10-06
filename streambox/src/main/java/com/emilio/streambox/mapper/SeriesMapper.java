package com.emilio.streambox.mapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.emilio.streambox.dto.EpisodeResponse;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.dto.SeasonResponse;
import com.emilio.streambox.dto.SeriesDetailResponse;
import com.emilio.streambox.dto.SeriesRequest;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Series;

/**
 * Convierte entre la entidad {@link Series} y los DTO de la API.
 *
 * <p>
 * Como {@link MovieMapper}, se invoca desde los servicios <strong>dentro de la
 * transacción</strong>, porque recorre los géneros (carga diferida).
 * </p>
 *
 * <p>
 * El mapper no consulta nada por su cuenta: los recuentos de temporadas y
 * episodios de un listado y los episodios del detalle se los pasa el servicio,
 * que los obtiene con una consulta por página o por serie. Así queda a la
 * vista, en el servicio, cuántas consultas cuesta cada operación.
 * </p>
 */
public final class SeriesMapper {

    /**
     * Orden de los episodios: temporada y número. La consulta
     * {@code findAllBySeriesIdOrdered} ya los devuelve así; se vuelve a
     * ordenar aquí para que el mapper no dependa de quién le pase la lista.
     */
    private static final Comparator<Episode> EPISODE_ORDER =
            Comparator.comparing(Episode::getSeasonNumber).thenComparing(Episode::getEpisodeNumber);

    private SeriesMapper() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Crea una serie nueva a partir de los datos recibidos, sin géneros (el
     * servicio los resuelve contra la base de datos) ni fecha de creación (la
     * asigna Hibernate al guardar).
     *
     * @param request datos de la serie recibidos del cliente
     * @return entidad {@link Series} sin guardar y sin géneros
     */
    public static Series toEntity(SeriesRequest request) {

        Series series = new Series();
        copyFields(request, series);
        return series;
    }

    /**
     * Copia sobre una serie existente los campos editables del DTO. No
     * modifica el identificador, la fecha de creación ni los géneros.
     *
     * @param request datos nuevos recibidos del cliente
     * @param series  serie que se actualiza
     */
    public static void updateEntity(SeriesRequest request, Series series) {

        copyFields(request, series);
    }

    /**
     * Convierte una serie en el DTO de los listados.
     *
     * @param series       entidad a convertir (con sesión de Hibernate abierta)
     * @param seasonCount  temporadas con episodios (0 si no tiene)
     * @param episodeCount episodios (0 si no tiene)
     * @return DTO de listado
     */
    public static SeriesResponse toResponse(Series series, long seasonCount, long episodeCount) {

        return new SeriesResponse(
                series.getId(),
                series.getTitle(),
                series.getDescription(),
                series.getReleaseYear(),
                series.getEndYear(),
                series.getImageUrl(),
                series.getCreatedAt(),
                sortedGenres(series),
                seasonCount,
                episodeCount);
    }

    /**
     * Convierte una serie y sus episodios en el DTO de detalle, agrupando los
     * episodios por temporada.
     *
     * <p>
     * Los recuentos se calculan aquí a partir de la lista (no hace falta otra
     * consulta): número de episodios y número de temporadas distintas.
     * </p>
     *
     * @param series   entidad a convertir (con sesión de Hibernate abierta)
     * @param episodes todos los episodios de la serie (vacía si no tiene)
     * @return DTO de detalle con las temporadas ordenadas por número y los
     *         episodios de cada una ordenados por número
     */
    public static SeriesDetailResponse toDetailResponse(Series series, List<Episode> episodes) {

        // TreeMap: las temporadas quedan ordenadas por número aunque no sean
        // consecutivas (1, 3...) y se recorren en ese orden.
        Map<Integer, List<EpisodeResponse>> bySeason = new TreeMap<>();
        episodes.stream()
                .sorted(EPISODE_ORDER)
                .forEach(episode -> bySeason
                        .computeIfAbsent(episode.getSeasonNumber(), season -> new ArrayList<>())
                        .add(EpisodeMapper.toResponse(episode)));

        List<SeasonResponse> seasons = bySeason.entrySet().stream()
                .map(entry -> new SeasonResponse(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();

        return new SeriesDetailResponse(
                series.getId(),
                series.getTitle(),
                series.getDescription(),
                series.getReleaseYear(),
                series.getEndYear(),
                series.getImageUrl(),
                series.getCreatedAt(),
                sortedGenres(series),
                seasons.size(),
                episodes.size(),
                seasons);
    }

    /**
     * Géneros ordenados por nombre (y por id si dos coincidieran), para que la
     * respuesta sea estable entre llamadas.
     */
    private static List<GenreResponse> sortedGenres(Series series) {

        return series.getGenres().stream()
                .map(GenreMapper::toResponse)
                .sorted(Comparator.comparing(GenreResponse::name).thenComparing(GenreResponse::id))
                .toList();
    }

    private static void copyFields(SeriesRequest request, Series series) {

        series.setTitle(request.title());
        series.setDescription(request.description());
        series.setReleaseYear(request.releaseYear());
        series.setEndYear(request.endYear());
        series.setImageUrl(request.imageUrl());
    }
}
