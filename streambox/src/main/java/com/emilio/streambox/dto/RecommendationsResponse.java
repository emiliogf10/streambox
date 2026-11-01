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
 * <p>
 * {@code basis} sustituye al antiguo {@code basedOnLikes} (cambio de contrato
 * de los géneros favoritos): {@code basedOnLikes = true} equivale hoy a
 * {@code LIKES} o {@code GENRES_AND_LIKES}.
 * </p>
 *
 * @param basis  en qué se basan las recomendaciones; con {@code NONE} las dos
 *               listas van vacías y el cliente no debe mostrar la fila
 *               «Recomendadas para ti» (sino invitar a elegir gustos)
 * @param movies películas recomendadas, de más a menos recomendable
 * @param series series recomendadas (solo con episodios), en el mismo orden
 */
public record RecommendationsResponse(
        @Schema(description = "En qué se basan las recomendaciones: NONE (ni géneros favoritos con títulos ni "
                + "«me gusta» visibles; las dos listas van vacías), GENRES (solo los géneros favoritos), LIKES "
                + "(solo los «me gusta») o GENRES_AND_LIKES (los dos). Distinto de NONE no garantiza que haya "
                + "títulos: si ya le gusta todo lo de sus géneros, las listas pueden ir vacías.",
                example = "GENRES")
        RecommendationBasis basis,
        @Schema(description = "Películas recomendadas, de más a menos recomendable (como mucho limit)")
        List<MovieResponse> movies,
        @Schema(description = "Series recomendadas (solo con episodios), de más a menos recomendable "
                + "(como mucho limit)")
        List<SeriesResponse> series) {
}
