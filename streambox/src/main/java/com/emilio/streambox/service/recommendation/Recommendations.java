package com.emilio.streambox.service.recommendation;

import java.util.List;
import java.util.Objects;

import com.emilio.streambox.dto.RecommendationBasis;

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
 * @param basis     en qué se basan ({@link RecommendationBasis#NONE} si no hay
 *                  nada en lo que basarse: entonces las listas van vacías)
 * @param movieIds  ids de las películas recomendadas, de más a menos recomendable
 * @param seriesIds ids de las series recomendadas, de más a menos recomendable
 */
public record Recommendations(RecommendationBasis basis, List<Long> movieIds, List<Long> seriesIds) {

    /**
     * Copia las listas para que el resultado sea inmutable aunque la
     * implementación pase listas modificables y comprueba que es coherente.
     *
     * <p>
     * {@link List#copyOf} rechaza los {@code null} (lista o id): un recomendador
     * que los devuelva tiene un fallo de programación y es mejor que se note
     * aquí, en su origen. Por lo mismo, {@code NONE} con títulos es una
     * contradicción (el inicio diría «elige tus gustos» mientras muestra
     * recomendaciones): si una estrategia futura recomienda sin señales del
     * usuario (p. ej. lo más popular), necesita un valor nuevo en
     * {@link RecommendationBasis}, no reutilizar {@code NONE}.
     * </p>
     *
     * @throws NullPointerException     si {@code basis}, alguna lista o algún id es {@code null}
     * @throws IllegalArgumentException si {@code basis} es {@code NONE} y alguna lista no está vacía
     */
    public Recommendations {
        Objects.requireNonNull(basis, "basis");
        movieIds = List.copyOf(movieIds);
        seriesIds = List.copyOf(seriesIds);
        if (basis == RecommendationBasis.NONE && !(movieIds.isEmpty() && seriesIds.isEmpty())) {
            throw new IllegalArgumentException("Sin base (NONE) no puede haber títulos recomendados");
        }
    }

    /**
     * Resultado de un usuario sin géneros favoritos con títulos ni «me gusta»
     * visibles: nada en lo que basarse.
     *
     * @return recomendaciones vacías con {@code basis = NONE}
     */
    public static Recommendations none() {
        return new Recommendations(RecommendationBasis.NONE, List.of(), List.of());
    }
}
