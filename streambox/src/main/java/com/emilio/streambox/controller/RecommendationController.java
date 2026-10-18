package com.emilio.streambox.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.RecommendationsResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.RecommendationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Controlador REST de las recomendaciones personales
 * ({@code /api/users/me/recommendations}), la fila «Recomendadas para ti» del
 * inicio.
 *
 * <p>
 * No sabe cómo se calculan: llama a {@link RecommendationService}, que delega
 * la decisión en un {@code Recommender}. Cambiar el criterio (p. ej. por una
 * IA) no cambia ni esta ruta ni su respuesta. Usa el id del token y cae en
 * {@code anyRequest().authenticated()} de {@code SecurityConfig}.
 * </p>
 *
 * <p>
 * Lleva {@code @Validated} para que {@code @Min}/{@code @Max} del límite se
 * apliquen y un valor fuera de rango sea un 400 {@code VALIDATION_ERROR} con el
 * motivo en {@code validationErrors.limit}, como en las películas similares.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequestMapping("/api/users/me/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;

    /**
     * Crea el controlador de recomendaciones.
     *
     * @param recommendationService servicio de recomendaciones
     */
    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * Obtiene las recomendaciones del usuario.
     *
     * @param principal usuario autenticado
     * @param limit     máximo de películas y, por separado, de series (1 a 24, por defecto 12)
     * @return recomendaciones, ya ordenadas
     */
    @GetMapping
    @Operation(summary = "Obtiene mis recomendaciones", description = "Devuelve películas y series "
            + "recomendadas al usuario autenticado según los géneros de lo que le gusta: cada género pesa "
            + "tantos puntos como títulos con «me gusta» lo tienen y cada título suma los pesos de sus géneros. "
            + "No incluye lo que ya le gusta ni series sin episodios; a igual puntuación, primero lo añadido "
            + "más recientemente y después el id mayor. Si no tiene ningún «me gusta», basedOnLikes es false y "
            + "las dos listas van vacías.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Recomendaciones obtenidas correctamente (las "
                    + "listas pueden estar vacías)"),
            @ApiResponse(responseCode = "400", description = "El límite no es un número entero entre 1 y 24"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public RecommendationsResponse getRecommendations(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "" + RecommendationService.DEFAULT_LIMIT)
            @Parameter(description = "Número máximo de películas y, por separado, de series: de 1 a 24 "
                    + "(por defecto, 12)")
            @Min(value = RecommendationService.MIN_LIMIT, message = "El límite mínimo es 1")
            @Max(value = RecommendationService.MAX_LIMIT, message = "El límite máximo es 24") int limit) {

        return recommendationService.getRecommendations(principal.id(), limit);
    }
}
