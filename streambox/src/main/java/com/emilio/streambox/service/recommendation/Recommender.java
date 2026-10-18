package com.emilio.streambox.service.recommendation;

/**
 * Estrategia que decide qué títulos recomendar a un usuario.
 *
 * <p>
 * <b>Por qué es una interfaz</b> (el resto de servicios del proyecto son clases
 * concretas). Es el único punto en el que ya se sabe que habrá una segunda
 * implementación: hoy la recomendación es una consulta SQL por géneros
 * ({@link GenreBasedRecommender}), pero el plan prevé sustituirla por una IA. Con
 * la interfaz, ese cambio es escribir otra clase que la implemente y marcarla
 * como la que se inyecta; ni el controlador, ni {@code RecommendationService},
 * ni el contrato de la API, ni el frontend cambian. Por eso guarda también
 * <em>cuándo</em> se dio cada «me gusta» ({@code created_at}): es el dato que
 * una IA necesitaría para pesar más lo reciente.
 * </p>
 *
 * <p>
 * <b>Contrato.</b> Devuelve solo identificadores (ver {@link Recommendations}),
 * como mucho {@code limit} por tipo, ya ordenados. Quien la llama
 * ({@code RecommendationService}) valida el límite antes, carga los títulos,
 * descarta los que no se pueden mostrar y, por si una implementación no cumple
 * el contrato, quita duplicados y recorta al límite.
 * </p>
 *
 * <p>
 * <b>Privacidad (para quien implemente otra estrategia).</b>
 * {@link GenreBasedRecommender} solo usa los «me gusta» del propio usuario y el
 * catálogo, así que sus resultados no revelan nada de nadie más. Una
 * implementación que use señales de otros usuarios («a quien le gustó X también
 * le gustó Y») debe usar solo datos <em>agregados</em> y exigir un mínimo de
 * usuarios distintos detrás de cada señal: con pocos usuarios, una
 * recomendación podría delatar qué le gusta a una persona concreta
 * (observación de la revisión de seguridad del 2026-10-17).
 * </p>
 */
public interface Recommender {

    /**
     * Calcula las recomendaciones de un usuario.
     *
     * @param userId identificador del usuario autenticado (del token)
     * @param limit  número máximo de películas y, por separado, de series; el
     *               llamante garantiza que está entre 1 y 24
     * @return ids recomendados, en orden; {@link Recommendations#withoutLikes()}
     *         si el usuario no tiene en qué basarse
     */
    Recommendations recommend(Long userId, int limit);
}
