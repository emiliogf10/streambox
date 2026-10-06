package com.emilio.streambox.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * DTO con una página de series y los datos necesarios para paginar.
 *
 * <p>
 * Tiene exactamente la misma forma que {@link MoviePageResponse}, para que el
 * frontend pagine películas y series con el mismo código. Es un tipo aparte
 * (y no un genérico {@code PageResponse<T>}) para no cambiar el nombre del
 * esquema de películas en el OpenAPI y para que cada endpoint documente con
 * precisión qué contiene {@code content}.
 * </p>
 *
 * @param content       series de la página actual
 * @param page          número de la página actual (empieza en 0)
 * @param size          tamaño de página solicitado
 * @param totalElements número total de series que cumplen la búsqueda
 * @param totalPages    número total de páginas
 * @param hasNext       {@code true} si existe una página siguiente
 * @param hasPrevious   {@code true} si existe una página anterior
 */
public record SeriesPageResponse(
        List<SeriesResponse> content,
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
     * @param seriesPage página de series ya transformadas a {@link SeriesResponse}
     * @return respuesta paginada lista para devolver al cliente
     */
    public static SeriesPageResponse from(Page<SeriesResponse> seriesPage) {

        return new SeriesPageResponse(
                seriesPage.getContent(),
                seriesPage.getNumber(),
                seriesPage.getSize(),
                seriesPage.getTotalElements(),
                seriesPage.getTotalPages(),
                seriesPage.hasNext(),
                seriesPage.hasPrevious());
    }
}
