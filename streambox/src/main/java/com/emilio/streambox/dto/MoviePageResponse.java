package com.emilio.streambox.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * DTO con una página de películas y los datos necesarios para paginar.
 *
 * <p>
 * Se usa en lugar de serializar directamente un {@link Page} de Spring Data
 * para controlar el formato del contrato JSON de la API.
 * </p>
 *
 * @param content       películas de la página actual
 * @param page          número de la página actual (empieza en 0)
 * @param size          tamaño de página solicitado
 * @param totalElements número total de películas que cumplen la búsqueda
 * @param totalPages    número total de páginas
 * @param hasNext       {@code true} si existe una página siguiente
 * @param hasPrevious   {@code true} si existe una página anterior
 */
public record MoviePageResponse(
        List<MovieResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {

    /**
     * Construye la respuesta a partir de una página de Spring Data ya
     * convertida a DTOs.
     *
     * @param moviePage página de películas ya transformadas a {@link MovieResponse}
     * @return respuesta paginada lista para devolver al cliente
     */
    public static MoviePageResponse from(Page<MovieResponse> moviePage) {

        return new MoviePageResponse(
                moviePage.getContent(),
                moviePage.getNumber(),
                moviePage.getSize(),
                moviePage.getTotalElements(),
                moviePage.getTotalPages(),
                moviePage.hasNext(),
                moviePage.hasPrevious());
    }
}
