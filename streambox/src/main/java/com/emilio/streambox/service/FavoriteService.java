package com.emilio.streambox.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.AmbiguousTitleException;
import com.emilio.streambox.exception.MovieAlreadyInFavoritesException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.MovieNotInFavoritesException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio de la lista de favoritos de cada usuario ("Mi lista").
 *
 * <p>
 * Cada operación es una consulta directa sobre la tabla de unión: añadir una
 * película es un {@code INSERT}, quitarla un {@code DELETE}. No se carga la
 * colección completa de favoritos del usuario (con los géneros de cada
 * película) para modificar un solo elemento.
 * </p>
 *
 * <p>
 * Todas las operaciones reciben el identificador del usuario <em>autenticado</em>
 * (obtenido del token, nunca de la URL), de modo que un usuario solo puede
 * tocar su propia lista.
 * </p>
 */
@Service
public class FavoriteService {

    private static final String ALREADY_IN_FAVORITES =
            "La película ya está incluida en tu lista de favoritos";
    private static final String NOT_IN_FAVORITES =
            "La película no está incluida en tu lista de favoritos";

    private final UserRepository userRepository;
    private final MovieRepository movieRepository;

    /**
     * Crea el servicio de favoritos.
     *
     * @param userRepository  repositorio de usuarios (contiene las consultas de favoritos)
     * @param movieRepository repositorio de películas
     */
    public FavoriteService(
            UserRepository userRepository,
            MovieRepository movieRepository) {

        this.userRepository = userRepository;
        this.movieRepository = movieRepository;
    }

    /**
     * Obtiene la lista de un usuario ordenada por título.
     *
     * @param userId identificador del usuario autenticado
     * @return películas de su lista
     */
    @Transactional(readOnly = true)
    public List<MovieResponse> getFavorites(Long userId) {

        return MovieMapper.toResponseList(userRepository.findFavoriteMovies(userId));
    }

    /**
     * Añade una película a la lista por su identificador.
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     * @throws MovieNotFoundException           si la película no existe
     * @throws MovieAlreadyInFavoritesException si ya estaba en la lista
     */
    @Transactional
    public void addFavorite(Long userId, Long movieId) {

        if (!movieRepository.existsById(movieId)) {
            throw new MovieNotFoundException("Película no encontrada");
        }

        insertFavorite(userId, movieId);
    }

    /**
     * Añade una película a la lista por su título exacto (sin distinguir
     * mayúsculas).
     *
     * @param userId identificador del usuario autenticado
     * @param title  título exacto de la película
     * @throws MovieNotFoundException           si no existe ninguna con ese título
     * @throws AmbiguousTitleException          si existe más de una con ese título
     * @throws MovieAlreadyInFavoritesException si ya estaba en la lista
     */
    @Transactional
    public void addFavoriteByTitle(Long userId, String title) {

        insertFavorite(userId, findMovieByTitle(title).getId());
    }

    /**
     * Quita una película de la lista por su identificador.
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     * @throws MovieNotFoundException        si la película no existe
     * @throws MovieNotInFavoritesException  si no estaba en la lista
     */
    @Transactional
    public void removeFavorite(Long userId, Long movieId) {

        if (!movieRepository.existsById(movieId)) {
            throw new MovieNotFoundException("Película no encontrada");
        }

        deleteFavorite(userId, movieId);
    }

    /**
     * Quita una película de la lista por su título exacto (sin distinguir
     * mayúsculas).
     *
     * @param userId identificador del usuario autenticado
     * @param title  título exacto de la película
     * @throws MovieNotFoundException       si no existe ninguna con ese título
     * @throws AmbiguousTitleException      si existe más de una con ese título
     * @throws MovieNotInFavoritesException si no estaba en la lista
     */
    @Transactional
    public void removeFavoriteByTitle(Long userId, String title) {

        deleteFavorite(userId, findMovieByTitle(title).getId());
    }

    /**
     * Vacía la lista del usuario. Si ya estaba vacía no es un error.
     *
     * @param userId identificador del usuario autenticado
     */
    @Transactional
    public void clearFavorites(Long userId) {

        userRepository.clearFavorites(userId);
    }

    private void insertFavorite(Long userId, Long movieId) {

        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException("Usuario no encontrado");
        }

        if (userRepository.isFavorite(userId, movieId)) {
            throw new MovieAlreadyInFavoritesException(ALREADY_IN_FAVORITES);
        }

        try {
            userRepository.addFavorite(userId, movieId);
        } catch (DataIntegrityViolationException e) {
            throw translateInsertViolation(e);
        }
    }

    /**
     * Traduce una violación de integridad del {@code INSERT} a la excepción de
     * dominio que corresponde, mirando el {@code SQLSTATE} y no el texto del
     * mensaje (que cambia con el motor y con el idioma).
     *
     * <ul>
     *   <li>Unicidad ({@code 23505}): dos peticiones simultáneas pasaron la
     *       comprobación {@code isFavorite} y la clave primaria de la tabla de
     *       unión impide el duplicado: 409.</li>
     *   <li>Clave foránea ({@code 23503}; {@code 23506} en H2 cuando falta el
     *       registro padre): la película (o el usuario) se borró entre la
     *       comprobación de existencia y el {@code INSERT}. No es un duplicado,
     *       sino un recurso inexistente: 404. Devolver 409 "ya está en favoritos"
     *       sería un mensaje falso.</li>
     *   <li>Cualquier otra violación (nulos, longitudes...) no se disfraza: se
     *       relanza tal cual: {@code GlobalExceptionHandler} la convierte en un
     *       409 {@code DATA_INTEGRITY_VIOLATION} con un mensaje genérico (sin
     *       revelar el detalle del motor), porque indicaría un fallo de
     *       programación.</li>
     * </ul>
     *
     * @param error excepción lanzada por el repositorio
     * @return la excepción de dominio a lanzar; si no es reconocida, el propio {@code error}
     */
    private RuntimeException translateInsertViolation(DataIntegrityViolationException error) {

        String sqlState = SqlStates.find(error);

        if (error instanceof DuplicateKeyException || SqlStates.UNIQUE_VIOLATION.equals(sqlState)) {
            return new MovieAlreadyInFavoritesException(ALREADY_IN_FAVORITES);
        }
        if (SqlStates.FOREIGN_KEY_VIOLATION.equals(sqlState)
                || SqlStates.H2_FOREIGN_KEY_PARENT_MISSING.equals(sqlState)) {
            return new MovieNotFoundException("Película no encontrada");
        }
        return error;
    }

    private void deleteFavorite(Long userId, Long movieId) {

        if (userRepository.removeFavorite(userId, movieId) == 0) {
            throw new MovieNotInFavoritesException(NOT_IN_FAVORITES);
        }
    }

    private Movie findMovieByTitle(String title) {

        List<Movie> movies = movieRepository.findAllByTitleIgnoreCase(title);

        if (movies.isEmpty()) {
            throw new MovieNotFoundException(
                    "No existe ninguna película con el título indicado");
        }
        if (movies.size() > 1) {
            throw new AmbiguousTitleException(
                    "Existe más de una película con el título indicado. Utiliza el ID.");
        }
        return movies.get(0);
    }
}
