package com.emilio.streambox.mapper;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Movie;

/**
 * Convierte entre la entidad {@link Movie} y los DTO de la API.
 *
 * <p>
 * Se invoca desde los servicios, <strong>dentro de la transacción</strong>:
 * {@link #toResponse(Movie)} recorre los géneros de la película, que se cargan
 * de forma diferida, y eso solo es posible mientras la sesión de Hibernate
 * sigue abierta. Por eso los controladores nunca reciben entidades.
 * </p>
 */
public final class MovieMapper {

    private MovieMapper() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Crea una entidad nueva a partir de los datos recibidos.
     *
     * <p>
     * No asigna los géneros (el servicio los resuelve contra la base de
     * datos) ni la fecha de creación (la asigna Hibernate al guardar).
     * </p>
     *
     * @param request datos de la película recibidos del cliente
     * @return entidad {@link Movie} sin guardar y sin géneros
     */
    public static Movie toEntity(MovieRequest request) {

        Movie movie = new Movie();
        copyFields(request, movie);
        return movie;
    }

    /**
     * Copia sobre una película existente los campos editables del DTO.
     *
     * <p>
     * No modifica el identificador, la fecha de creación ni los géneros.
     * </p>
     *
     * @param request datos nuevos recibidos del cliente
     * @param movie   película que se actualiza
     */
    public static void updateEntity(MovieRequest request, Movie movie) {

        copyFields(request, movie);
    }

    /**
     * Convierte una película en el DTO que se devuelve al cliente.
     *
     * <p>
     * Los géneros se devuelven ordenados por nombre para que la respuesta sea
     * estable entre llamadas.
     * </p>
     *
     * @param movie entidad a convertir (con sesión de Hibernate abierta)
     * @return DTO con los datos de la película y sus géneros
     */
    public static MovieResponse toResponse(Movie movie) {

        Set<GenreResponse> genres = movie.getGenres().stream()
                .map(GenreMapper::toResponse)
                .sorted(Comparator.comparing(GenreResponse::name))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return new MovieResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getDescription(),
                movie.getDuration(),
                movie.getReleaseYear(),
                movie.getImageUrl(),
                movie.getVideoUrl(),
                movie.getCreatedAt(),
                genres);
    }

    /**
     * Convierte una lista de películas en una lista de DTO.
     *
     * @param movies películas a convertir (con sesión de Hibernate abierta)
     * @return lista de DTO en el mismo orden
     */
    public static List<MovieResponse> toResponseList(List<Movie> movies) {

        return movies.stream()
                .map(MovieMapper::toResponse)
                .toList();
    }

    private static void copyFields(MovieRequest request, Movie movie) {

        movie.setTitle(request.title());
        movie.setDescription(request.description());
        movie.setDuration(request.duration());
        movie.setReleaseYear(request.releaseYear());
        movie.setImageUrl(request.imageUrl());
        movie.setVideoUrl(request.videoUrl());
    }
}
