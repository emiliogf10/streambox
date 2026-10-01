package com.emilio.streambox.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
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
 * película) para modificar un solo elemento, como ocurría antes.
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
            // Dos peticiones simultáneas pasaron la comprobación anterior: la
            // clave primaria de la tabla de unión impide el duplicado.
            throw new MovieAlreadyInFavoritesException(ALREADY_IN_FAVORITES);
        }
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
