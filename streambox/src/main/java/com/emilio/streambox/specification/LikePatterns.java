package com.emilio.streambox.specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

/**
 * Búsqueda "contiene este texto, sin distinguir mayúsculas" compartida por los
 * filtros de título de películas ({@link MovieSpecification}) y series
 * ({@link SeriesSpecification}).
 *
 * <p>
 * Se extrajo de {@code MovieSpecification} al añadir las series: la búsqueda
 * por título tiene dos detalles delicados (escapar los comodines y
 * minusculizar en la base de datos) que ya causaron un bug, y tenerlos en un
 * solo sitio garantiza que películas y series se comporten igual.
 * </p>
 *
 * <ul>
 * <li><b>Comodines literales.</b> Los {@code %} y {@code _} que escriba el
 * usuario se escapan: buscar {@code "100%"} encuentra títulos que contienen
 * exactamente {@code 100%}, no cualquier título.</li>
 * <li><b>{@code lower()} a ambos lados, en la base de datos.</b> El texto
 * buscado NO se pasa por {@code String.toLowerCase} de Java: la función
 * {@code lower()} de PostgreSQL no coincide con la de Java en algunos casos
 * ({@code İ}, que Java convierte en {@code i} + U+0307 y PostgreSQL en
 * {@code i}, y la sigma mayúscula final de palabra, que Java convierte en
 * {@code ς} y PostgreSQL en {@code σ}). Si cada lado se minusculizase con un
 * motor distinto, un título con esas letras no se encontraría buscándolo
 * entero. {@code lower()} no altera la barra de escape ni los comodines, así
 * que el escapado se mantiene intacto.</li>
 * </ul>
 *
 * <p>
 * Es de paquete porque solo la usan las {@code Specification}.
 * </p>
 */
final class LikePatterns {

    /** Carácter de escape usado en las búsquedas {@code LIKE}. */
    private static final char LIKE_ESCAPE = '\\';

    private LikePatterns() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Construye el predicado "{@code field} contiene {@code text}" sin
     * distinguir mayúsculas y tratando los comodines como texto literal.
     *
     * @param criteriaBuilder constructor de la consulta
     * @param field           columna de texto sobre la que se busca
     * @param text            texto introducido por el usuario
     * @return predicado {@code lower(field) LIKE lower('%texto%') ESCAPE '\'}
     */
    static Predicate containsIgnoreCase(CriteriaBuilder criteriaBuilder, Expression<String> field, String text) {

        String pattern = "%" + escape(text) + "%";

        return criteriaBuilder.like(
                criteriaBuilder.lower(field),
                criteriaBuilder.lower(criteriaBuilder.literal(pattern)),
                LIKE_ESCAPE);
    }

    /**
     * Escapa los caracteres con significado especial en {@code LIKE}.
     *
     * @param text texto introducido por el usuario
     * @return texto con {@code \}, {@code %} y {@code _} escapados
     */
    private static String escape(String text) {

        return text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
