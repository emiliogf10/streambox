package com.emilio.streambox.dto;

/**
 * Género tal como lo ve el panel de administración
 * ({@code GET /api/admin/genres}): con cuántas películas y series lo tienen
 * asignado.
 *
 * <p>
 * Es un DTO aparte de {@link GenreResponse} (y no dos campos más en él) porque
 * {@code seriesCount} cuenta también las series sin episodios, que para los
 * usuarios no existen: si {@code GET /api/genres} lo devolviera, un usuario
 * podría deducir que hay series ocultas. Con dos tipos, ese dato no puede
 * llegar por descuido a una respuesta pública.
 * </p>
 *
 * <p>
 * Los recuentos son los mismos que daría el 409 {@code GENRE_IN_USE} al
 * intentar borrarlo: con {@code movieCount} y {@code seriesCount} a 0 se
 * puede borrar.
 * </p>
 *
 * @param id          identificador del género
 * @param name        nombre del género
 * @param movieCount  películas que lo tienen asignado
 * @param seriesCount series que lo tienen asignado, también las que aún no tienen episodios
 */
public record AdminGenreResponse(Long id, String name, long movieCount, long seriesCount) {
}
