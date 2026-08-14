package com.emilio.streambox.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.dto.UpdateMovieRequest;
import com.emilio.streambox.exception.GenreNotFoundException;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con las películas de Streambox.
 *
 * <p>
 * Centraliza las operaciones relacionadas con las películas,
 * evitando que los controladores tengan que acceder directamente
 * al repositorio.
 * </p>
 */
@Service
public class MovieService {

    private final MovieRepository movieRepository;

    private final GenreRepository genreRepository;

    /**
     * Crea una instancia del servicio de películas.
     *
     * @param movieRepository repositorio utilizado para acceder
     *                        a las películas almacenadas
     */
    public MovieService(MovieRepository movieRepository, GenreRepository genreRepository) {
        this.movieRepository = movieRepository;
        this.genreRepository = genreRepository;
    }

    /**
     * Obtiene todas las películas almacenadas en Streambox.
     *
     * @return lista de todas las películas
     */
    public List<Movie> getAllMovies() {
        return movieRepository.findAll();
    }

    /**
     * Obtiene una película mediante su identificador.
     *
     * @param id identificador de la película que se desea obtener
     * @return película correspondiente al identificador proporcionado
     * @throws RuntimeException si no existe ninguna película con el ID indicado
     */
    public Movie getMovieById(Long id) {

        return movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException("Película no encontrada"));
    }

    /**
     * Guarda una nueva película y asocia los géneros indicados.
     *
     * @param movie    película que se desea guardar
     * @param genreIds identificadores de los géneros asociados
     * @return película almacenada con sus géneros
     * @throws RuntimeException si alguno de los géneros indicados no existe
     */
    public Movie saveMovie(Movie movie, Set<Long> genreIds) {

        movie.setCreatedAt(LocalDateTime.now());

        Set<Genre> genres = new HashSet<>();

        for (Long genreId : genreIds) {

            Genre genre = genreRepository.findById(genreId)
                    .orElseThrow(() -> new GenreNotFoundException(
                            "Género no encontrado: " + genreId));

            genres.add(genre);
        }

        movie.setGenres(genres);

        return movieRepository.save(movie);
    }

    /**
     * Modifica los datos de una película existente y actualiza
     * sus géneros asociados.
     *
     * <p>
     * Primero se comprueba que la película exista. Después se actualizan
     * sus datos y se sustituyen los géneros actuales por los indicados
     * en la petición.
     * </p>
     *
     * @param id      identificador de la película que se desea modificar
     * @param request datos actualizados de la película
     * @return película modificada y almacenada en la base de datos
     * @throws MovieNotFoundException si no existe una película con el ID indicado
     * @throws RuntimeException       si alguno de los géneros indicados no existe
     */
    public Movie updateMovie(Long id, UpdateMovieRequest request) {

        Movie existingMovie = movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException(
                        "Película no encontrada"));

        existingMovie.setTitle(request.getTitle());
        existingMovie.setDescription(request.getDescription());
        existingMovie.setDuration(request.getDuration());
        existingMovie.setReleaseYear(request.getReleaseYear());
        existingMovie.setImageUrl(request.getImageUrl());
        existingMovie.setVideoUrl(request.getVideoUrl());

        Set<Genre> genres = new HashSet<>();

        for (Long genreId : request.getGenreIds()) {

            Genre genre = genreRepository.findById(genreId)
                    .orElseThrow(() -> new GenreNotFoundException(
                            "Género no encontrado: " + genreId));

            genres.add(genre);
        }

        existingMovie.setGenres(genres);

        return movieRepository.save(existingMovie);
    }

    /**
     * Elimina una película existente mediante su identificador.
     *
     * <p>
     * Antes de eliminar la película se comprueba que exista. De esta
     * forma, si se solicita eliminar un identificador inexistente, se
     * lanza {@link MovieNotFoundException} y la API puede devolver
     * correctamente un {@code 404 Not Found}.
     * </p>
     *
     * @param id identificador de la película que se desea eliminar
     * @throws MovieNotFoundException si no existe ninguna película
     *                                con el ID indicado
     */
    public void deleteMovie(Long id) {

        if (!movieRepository.existsById(id)) {
            throw new MovieNotFoundException("Película no encontrada");
        }

        movieRepository.deleteById(id);
    }
}
