package com.emilio.streambox.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.mapper.MovieMapper;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para representar una respuesta paginada de películas.
 *
 * <p>
 * Contiene las películas de la página actual junto con la información
 * necesaria para conocer el estado de la paginación.
 * </p>
 */
@Getter
@Setter
public class MoviePageResponse {

    /**
     * Películas correspondientes a la página actual.
     */
    private List<MovieResponse> content;

    /**
     * Número de la página actual.
     */
    private int page;

    /**
     * Número de elementos solicitados por página.
     */
    private int size;

    /**
     * Número total de películas que cumplen los filtros.
     */
    private long totalElements;

    /**
     * Número total de páginas disponibles.
     */
    private int totalPages;

    /**
     * Indica si existe una página posterior a la actual.
     */
    private boolean hasNext;

    /**
     * Indica si existe una página anterior a la actual.
     */
    private boolean hasPrevious;

    /**
     * Convierte una página de entidades {@link Movie} en una respuesta
     * paginada de películas.
     *
     * @param moviePage página de películas obtenida desde el repositorio
     * @return DTO con las películas y la información de paginación
     */
    public static MoviePageResponse from(Page<Movie> moviePage) {

        MoviePageResponse response = new MoviePageResponse();

        response.setContent(
                MovieMapper.toResponseList(moviePage.getContent()));

        response.setPage(moviePage.getNumber());
        response.setSize(moviePage.getSize());
        response.setTotalElements(moviePage.getTotalElements());
        response.setTotalPages(moviePage.getTotalPages());
        response.setHasNext(moviePage.hasNext());
        response.setHasPrevious(moviePage.hasPrevious());

        return response;
    }
}