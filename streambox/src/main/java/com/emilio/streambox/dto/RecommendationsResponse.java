package com.emilio.streambox.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Recomendaciones personales del usuario autenticado
 * ({@code GET /api/users/me/recommendations}).
 *
 * <p>
 * Películas y series van en dos listas separadas (y no mezcladas en una sola)
 * porque el inicio las pinta en filas distintas y porque cada tipo tiene su
 * propio DTO de listado. Cada lista tiene como mucho {@code limit} elementos y
 * llega ya ordenada de más a menos recomendable.
 * </p>
 *
 * @param basedOnLikes {@code true} si el usuario tiene algún «me gusta» visible
 *                     y las listas salen de ellos; {@code false} si no tiene
 *                     ninguno (las dos listas van vacías y el cliente no debe
 *                     mostrar la fila «Recomendadas para ti»)
 * @param movies       películas recomendadas, de más a menos recomendable
 * @param series       series recomendadas (solo con episodios), en el mismo orden
 */
public record RecommendationsResponse(
        @Schema(description = "true si las recomendaciones salen de los «me gusta» del usuario; false si no "
                + "tiene ninguno visible (las dos listas van vacías)")
        boolean basedOnLikes,
        @Schema(description = "Películas recomendadas, de más a menos recomendable (como mucho limit)")
        List<MovieResponse> movies,
        @Schema(description = "Series recomendadas (solo con episodios), de más a menos recomendable "
                + "(como mucho limit)")
        List<SeriesResponse> series) {
}
