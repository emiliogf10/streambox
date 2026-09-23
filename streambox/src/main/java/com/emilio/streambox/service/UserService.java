package com.emilio.streambox.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.AmbiguousTitleException;
import com.emilio.streambox.exception.MovieAlreadyInFavoritesException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.MovieNotInFavoritesException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con los usuarios de Streambox.
 *
 * <p>
 * Se encarga de coordinar las operaciones entre los controladores,
 * el repositorio de usuarios y los componentes de seguridad necesarios
 * para proteger las contraseñas.
 * </p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    /**
     * Repositorio utilizado para consultar las películas de favoritos.
     */
    private final MovieRepository movieRepository;

    /**
     * Crea una instancia del servicio de usuarios.
     *
     * @param userRepository  repositorio utilizado para acceder
     *                        a los usuarios almacenados
     * @param passwordEncoder componente utilizado para cifrar
     *                        las contraseñas de los usuarios
     * @param movieRepository repositorio utilizado para consultar las películas
     *                        de la lista de favoritos
     */
    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            MovieRepository movieRepository) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.movieRepository = movieRepository;
    }

    /**
     * Obtiene todos los usuarios registrados en Streambox.
     *
     * @return lista con todos los usuarios almacenados en la base de datos
     */
    public List<User> getAllUsers() {

        return userRepository.findAll();
    }

    /**
     * Guarda un nuevo usuario en la base de datos.
     *
     * <p>
     * Antes de guardar el usuario se comprueba que tanto el nombre
     * de usuario como el correo electrónico no estén siendo utilizados
     * por otro usuario.
     * </p>
     *
     * <p>
     * Los usuarios registrados mediante este servicio reciben
     * automáticamente el rol {@link Role#USER} y la fecha de creación
     * correspondiente al momento en el que se realiza el registro.
     * </p>
     *
     * <p>
     * La contraseña nunca se almacena directamente. Antes de persistir
     * el usuario, se cifra mediante {@link PasswordEncoder}.
     * </p>
     *
     * @param user usuario que se desea guardar en la base de datos
     * @return usuario guardado con su identificador generado,
     *         rol asignado, fecha de creación y contraseña cifrada
     * @throws IllegalArgumentException si el nombre de usuario
     *                                  ya está en uso
     * @throws IllegalArgumentException si el correo electrónico
     *                                  ya está en uso
     */
    public User saveUser(User user) {

        if (user.getEmail() != null) {
            user.setEmail(user.getEmail().trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (user.getUsername() != null) {
            user.setUsername(user.getUsername().trim());
        }

        if (userRepository.existsByUsername(user.getUsername())) {

            throw new UserAlreadyExistsException(
                    "El nombre de usuario ya está en uso");
        }

        if (userRepository.existsByEmail(user.getEmail())) {

            throw new UserAlreadyExistsException(
                    "El correo electrónico ya está en uso");
        }

        user.setRole(Role.USER);
        user.setCreatedAt(LocalDateTime.now());

        user.setPassword(
                passwordEncoder.encode(user.getPassword()));

        return userRepository.save(user);
    }

    /**
     * Obtiene las películas incluidas en la lista de favoritos de un usuario.
     *
     * @param userId identificador del usuario propietario de la lista
     * @return lista de películas favoritas con sus géneros cargados
     * @throws UserNotFoundException si no existe el usuario indicado
     */
    @Transactional(readOnly = true)
    public List<Movie> getFavoriteMovies(Long userId) {

        User user = getUserWithFavoriteMovies(userId);

        return List.copyOf(user.getFavoriteMovies());
    }

    /**
     * Añade una película a la lista de favoritos de un usuario.
     *
     * <p>
     * La relación se administra desde {@link User}, que es el lado propietario
     * de la asociación JPA. Un {@code Set} evita duplicados en la lista.
     * </p>
     *
     * @param userId identificador del usuario propietario de la lista
     * @param movieId identificador de la película que se desea añadir
     * @throws UserNotFoundException  si no existe el usuario indicado
     * @throws MovieNotFoundException si no existe la película indicada
     */
    @Transactional
    public void addMovieToFavorites(Long userId, Long movieId) {

        User user = getUserWithFavoriteMovies(userId);
        Movie movie = getMovie(movieId);

        if (!user.getFavoriteMovies().add(movie)) {
            throw new MovieAlreadyInFavoritesException(
                    "La película ya está incluida en tu lista de favoritos");
        }
    }

    /**
     * Añade una película a la lista de favoritos mediante su título exacto.
     *
     * @param userId identificador del usuario propietario de la lista
     * @param title   título exacto de la película que se desea añadir
     * @throws UserNotFoundException         si no existe el usuario indicado
     * @throws MovieNotFoundException        si no existe la película indicada
     * @throws MovieAlreadyInFavoritesException si la película ya está en la lista
     */
    @Transactional
    public void addMovieToFavoritesByTitle(Long userId, String title) {

        User user = getUserWithFavoriteMovies(userId);
        Movie movie = getMovieByTitle(title);

        if (!user.getFavoriteMovies().add(movie)) {
            throw new MovieAlreadyInFavoritesException(
                    "La película ya está incluida en tu lista de favoritos");
        }
    }

    /**
     * Elimina una película de la lista de favoritos de un usuario.
     *
     * <p>
     * Si la película no pertenece a la lista, se devuelve un error de negocio
     * para que el cliente pueda informar claramente de la situación.
     * </p>
     *
     * @param userId identificador del usuario propietario de la lista
     * @param movieId identificador de la película que se desea eliminar
     * @throws UserNotFoundException  si no existe el usuario indicado
     * @throws MovieNotFoundException si no existe la película indicada
     */
    @Transactional
    public void removeMovieFromFavorites(Long userId, Long movieId) {

        User user = getUserWithFavoriteMovies(userId);
        Movie movie = getMovie(movieId);

        if (!user.getFavoriteMovies().remove(movie)) {
            throw new MovieNotInFavoritesException(
                    "La película no está incluida en tu lista de favoritos");
        }
    }

    /**
     * Elimina una película de la lista de favoritos mediante su título exacto.
     *
     * @param userId identificador del usuario propietario de la lista
     * @param title   título exacto de la película que se desea eliminar
     * @throws UserNotFoundException      si no existe el usuario indicado
     * @throws MovieNotFoundException     si no existe la película indicada
     * @throws MovieNotInFavoritesException si la película no está en la lista
     */
    @Transactional
    public void removeMovieFromFavoritesByTitle(Long userId, String title) {

        User user = getUserWithFavoriteMovies(userId);
        Movie movie = getMovieByTitle(title);

        if (!user.getFavoriteMovies().remove(movie)) {
            throw new MovieNotInFavoritesException(
                    "La película no está incluida en tu lista de favoritos");
        }
    }

    /**
     * Elimina todas las películas de la lista de favoritos de un usuario.
     *
     * @param userId identificador del usuario propietario de la lista
     * @throws UserNotFoundException si no existe el usuario indicado
     */
    @Transactional
    public void clearFavoriteMovies(Long userId) {

        getUserWithFavoriteMovies(userId).getFavoriteMovies().clear();
    }

    /**
     * Busca un usuario junto con sus películas favoritas y los géneros de
     * cada película.
     *
     * @param userId identificador del usuario que se desea localizar
     * @return usuario encontrado con la lista de favoritos cargada
     * @throws UserNotFoundException si no existe el usuario indicado
     */
    private User getUserWithFavoriteMovies(Long userId) {

        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));
    }

    /**
     * Busca una película por su título exacto sin distinguir mayúsculas.
     *
     * @param title título exacto de la película que se desea localizar
     * @return película encontrada
     * @throws MovieNotFoundException si no existe la película indicada
     */
    private Movie getMovieByTitle(String title) {

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

    /**
     * Busca una película por su identificador.
     *
     * @param movieId identificador de la película que se desea localizar
     * @return película encontrada
     * @throws MovieNotFoundException si no existe la película indicada
     */
    private Movie getMovie(Long movieId) {

        return movieRepository.findById(movieId)
                .orElseThrow(() -> new MovieNotFoundException("Película no encontrada"));
    }
}