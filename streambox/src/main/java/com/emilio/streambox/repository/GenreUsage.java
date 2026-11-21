package com.emilio.streambox.repository;

/**
 * Proyección con un género y cuántas películas y series lo tienen asignado,
 * resultado de {@link GenreRepository#findAllWithUsage()}.
 *
 * <p>
 * Es una interfaz (proyección de Spring Data) y no la entidad porque los
 * recuentos no son columnas de {@code genres}: los calcula la consulta. Los
 * nombres de los métodos deben coincidir con los alias de la consulta
 * ({@code id}, {@code name}, {@code movieCount}, {@code seriesCount}).
 * </p>
 */
public interface GenreUsage {

    /**
     * @return identificador del género
     */
    Long getId();

    /**
     * @return nombre del género
     */
    String getName();

    /**
     * @return número de películas que tienen asignado el género
     */
    long getMovieCount();

    /**
     * @return número de series que tienen asignado el género, <strong>también
     *         las que no tienen episodios</strong> (ocultas para los usuarios)
     */
    long getSeriesCount();
}
