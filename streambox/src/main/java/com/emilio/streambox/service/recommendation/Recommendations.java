package com.emilio.streambox.service.recommendation;

import java.util.List;

/**
 * Resultado de un {@link Recommender}: <strong>qué</strong> títulos recomendar
 * y en qué orden, solo con sus identificadores.
 *
 * <p>
 * <b>Por qué ids y no DTOs.</b> Decidir qué recomendar y cargar los títulos son
 * dos trabajos distintos. El recomendador solo decide (con SQL, con una IA o con
 * lo que sea); {@code RecommendationService} carga los títulos, los convierte a
 * DTO dentro de su transacción y descarta lo que ya no se puede mostrar (un
 * título borrado entretanto o una serie que se quedó sin episodios). Así una
 * implementación nueva no tiene que saber nada de JPA, de la carga diferida ni
 * de qué series son visibles, y no puede saltarse esas reglas por error.
 * </p>
 *
 * @param basedOnLikes {@code true} si la recomendación sale de los «me gusta»
 *                     del usuario; {@code false} si no tiene ninguno (las listas
 *                     van vacías)
 * @param movieIds     ids de las películas recomendadas, de más a menos recomendable
 * @param seriesIds    ids de las series recomendadas, de más a menos recomendable
 */
public record Recommendations(boolean basedOnLikes, List<Long> movieIds, List<Long> seriesIds) {

    /**
     * Copia las listas para que el resultado sea inmutable aunque la
     * implementación pase listas modificables. {@link List#copyOf} rechaza los
     * {@code null} (lista o id): un recomendador que los devuelva tiene un
     * fallo de programación y es mejor que se note aquí, en su origen.
     *
     * @throws NullPointerException si alguna lista o algún id es {@code null}
     */
    public Recommendations {
        movieIds = List.copyOf(movieIds);
        seriesIds = List.copyOf(seriesIds);
    }

    /**
     * Resultado de un usuario sin ningún «me gusta»: nada en lo que basarse.
     *
     * @return recomendaciones vacías con {@code basedOnLikes = false}
     */
    public static Recommendations withoutLikes() {
        return new Recommendations(false, List.of(), List.of());
    }
}
