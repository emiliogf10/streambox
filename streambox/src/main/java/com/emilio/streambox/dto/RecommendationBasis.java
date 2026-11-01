package com.emilio.streambox.dto;

/**
 * En qué se basan las recomendaciones de un usuario
 * ({@link RecommendationsResponse#basis()}).
 *
 * <p>
 * <b>Por qué un enum y no el antiguo {@code basedOnLikes}.</b> Desde que existen
 * los géneros favoritos («Tus gustos», V11) hay dos señales independientes, y el
 * inicio tiene que decir la verdad: «Porque te gustan Acción y Drama» solo es
 * cierto si hay géneros elegidos; «Porque te gustó…», solo si hay «me gusta».
 * Un booleano no distingue esos casos y dos booleanos permitirían combinaciones
 * que el cliente tendría que interpretar; un valor por caso, no.
 * </p>
 *
 * <p>
 * Solo cuentan las señales que <strong>pueden</strong> producir
 * recomendaciones, las mismas que ve el usuario: «me gusta» de películas o de
 * series con episodios (los de su sección «Te gusta») y géneros favoritos que
 * aún tienen algún título visible (los de {@code GET /api/users/me/genres}). Un
 * valor distinto de {@link #NONE} no garantiza que las listas traigan algo: si
 * ya le gusta todo lo de sus géneros, no queda nada nuevo que proponer.
 * </p>
 */
public enum RecommendationBasis {

    /** Ni géneros favoritos con títulos ni «me gusta» visibles: las dos listas van vacías. */
    NONE,

    /** Solo géneros favoritos (típicamente, una cuenta nueva que contestó la pregunta de bienvenida). */
    GENRES,

    /** Solo «me gusta» visibles. */
    LIKES,

    /** Géneros favoritos y «me gusta»: los dos suman en el peso de cada género. */
    GENRES_AND_LIKES;

    /**
     * Combina las dos señales en el valor correspondiente.
     *
     * @param hasFavoriteGenres si tiene algún género favorito con títulos visibles
     * @param hasLikes          si tiene algún «me gusta» visible
     * @return el valor que describe esa combinación
     */
    public static RecommendationBasis of(boolean hasFavoriteGenres, boolean hasLikes) {

        if (hasFavoriteGenres) {
            return hasLikes ? GENRES_AND_LIKES : GENRES;
        }
        return hasLikes ? LIKES : NONE;
    }
}
