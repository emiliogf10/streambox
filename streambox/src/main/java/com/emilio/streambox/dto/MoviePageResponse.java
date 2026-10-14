package com.emilio.streambox.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO con una página de películas y los datos necesarios para paginar.
 *
 * <p>
 * Se usa en lugar de serializar directamente un {@link Page} de Spring Data
 * para controlar el formato del contrato JSON de la API.
 * </p>
 *
 * <p>
 * <b>{@code approximate}.</b> La búsqueda por título tiene un segundo intento
 * tolerante a erratas (ver {@code MovieService#searchMovies}). La interfaz
 * necesita saber si lo que muestra son coincidencias de verdad o títulos
 * <em>parecidos</em> para poder decirlo ("No hay resultados para «interstelar»;
 * quizá buscabas..."). Se añadió como campo nuevo, sin quitar ni renombrar
 * nada, para no romper a los clientes existentes. El listado completo
 * ({@code GET /api/movies}) comparte este DTO y siempre lo devuelve a
 * {@code false}: un único esquema de página en el OpenAPI y el mismo código de
 * paginación en el frontend.
 * </p>
 *
 * @param content       películas de la página actual
 * @param page          número de la página actual (empieza en 0)
 * @param size          tamaño de página solicitado
 * @param totalElements número total de películas que cumplen la búsqueda
 * @param totalPages    número total de páginas
 * @param hasNext       {@code true} si existe una página siguiente
 * @param hasPrevious   {@code true} si existe una página anterior
 * @param approximate   {@code true} solo si los resultados son títulos
 *                      parecidos (segundo intento tolerante a erratas)
 */
public record MoviePageResponse(
        List<MovieResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious,
        @Schema(description = APPROXIMATE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
        boolean approximate) {

    /**
     * Texto OpenAPI del campo {@code approximate}, compartido con
     * {@link SeriesPageResponse} para que películas y series lo documenten igual.
     */
    static final String APPROXIMATE_DESCRIPTION = "true solo cuando la búsqueda por título no encontró "
            + "ningún título que contuviera el texto y se devuelven, en su lugar, títulos parecidos "
            + "(tolerancia a erratas: solo con 5 letras o dígitos o más). Entonces los resultados van "
            + "ordenados por parecido, del más parecido al menos, y no por el sort pedido. false en "
            + "cualquier otro caso, incluidos los listados sin búsqueda y las búsquedas sin resultados.";

    /**
     * Construye la respuesta a partir de una página de Spring Data ya
     * convertida a DTOs, con resultados exactos ({@code approximate = false}).
     *
     * @param moviePage página de películas ya transformadas a {@link MovieResponse}
     * @return respuesta paginada lista para devolver al cliente
     */
    public static MoviePageResponse from(Page<MovieResponse> moviePage) {

        return from(moviePage, false);
    }

    /**
     * Construye la respuesta indicando si los resultados son aproximados.
     *
     * @param moviePage   página de películas ya transformadas a {@link MovieResponse}
     * @param approximate {@code true} si vienen del segundo intento tolerante a erratas
     * @return respuesta paginada lista para devolver al cliente
     */
    public static MoviePageResponse from(Page<MovieResponse> moviePage, boolean approximate) {

        return new MoviePageResponse(
                moviePage.getContent(),
                moviePage.getNumber(),
                moviePage.getSize(),
                moviePage.getTotalElements(),
                moviePage.getTotalPages(),
                moviePage.hasNext(),
                moviePage.hasPrevious(),
                approximate);
    }
}
