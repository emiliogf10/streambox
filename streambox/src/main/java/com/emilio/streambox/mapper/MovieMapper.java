package com.emilio.streambox.mapper;

import java.util.List;

import com.emilio.streambox.dto.CreateMovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Movie;

/**
 * Clase encargada de realizar las conversiones entre la entidad
 * {@link Movie} y los DTO utilizados por la API.
 *
 * <p>Centraliza la transformación de los datos que se reciben
 * y devuelven mediante la API REST, evitando realizar estas
 * conversiones directamente en los controladores.</p>
 */
public class MovieMapper {

    /**
     * Constructor privado para evitar la creación de instancias
     * de esta clase.
     *
     * <p>Todos los métodos de esta clase son estáticos, por lo que
     * no es necesario crear una instancia.</p>
     */
    private MovieMapper() {
        // Evita instanciar la clase
    }

    /**
     * Convierte una petición de creación de película en una entidad
     * {@link Movie}.
     *
     * <p>Los campos gestionados automáticamente por la aplicación,
     * como el identificador y la fecha de creación, no se establecen
     * en este método.</p>
     *
     * @param request datos recibidos desde la API para crear la película
     * @return entidad {@link Movie} creada a partir de los datos recibidos
     */
    public static Movie toEntity(CreateMovieRequest request) {

        Movie movie = new Movie();

        movie.setTitle(request.getTitle());
        movie.setDescription(request.getDescription());
        movie.setDuration(request.getDuration());
        movie.setReleaseYear(request.getReleaseYear());
        movie.setGenre(request.getGenre());
        movie.setImageUrl(request.getImageUrl());
        movie.setVideoUrl(request.getVideoUrl());

        return movie;
    }

    /**
     * Convierte una entidad {@link Movie} en un {@link MovieResponse}
     * para devolverla mediante la API.
     *
     * @param movie entidad de película que se desea convertir
     * @return DTO con los datos de la película que pueden exponerse
     *         mediante la API
     */
    public static MovieResponse toResponse(Movie movie) {

        MovieResponse response = new MovieResponse();

        response.setId(movie.getId());
        response.setTitle(movie.getTitle());
        response.setDescription(movie.getDescription());
        response.setDuration(movie.getDuration());
        response.setReleaseYear(movie.getReleaseYear());
        response.setGenre(movie.getGenre());
        response.setImageUrl(movie.getImageUrl());
        response.setVideoUrl(movie.getVideoUrl());
        response.setCreatedAt(movie.getCreatedAt());

        return response;
    }

    /**
     * Convierte una lista de entidades {@link Movie} en una lista
     * de {@link MovieResponse}.
     *
     * @param movies lista de entidades de películas
     * @return lista de DTOs correspondientes a las películas
     */
    public static List<MovieResponse> toResponseList(List<Movie> movies) {

        return movies.stream()
                .map(MovieMapper::toResponse)
                .toList();
    }
}