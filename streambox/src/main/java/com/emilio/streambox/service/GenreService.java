package com.emilio.streambox.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.repository.GenreRepository;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con los géneros cinematográficos de Streambox.
 *
 * <p>
 * Centraliza las operaciones relacionadas con los géneros,
 * evitando que los controladores tengan que acceder directamente
 * al repositorio.
 * </p>
 */
@Service
public class GenreService {

    private final GenreRepository genreRepository;

    /**
     * Crea una instancia del servicio de géneros.
     *
     * @param genreRepository repositorio utilizado para acceder
     *                        a los géneros almacenados
     */
    public GenreService(GenreRepository genreRepository) {
        this.genreRepository = genreRepository;
    }

    /**
     * Obtiene todos los géneros almacenados en Streambox.
     *
     * @return lista de todos los géneros almacenados
     */
    public List<Genre> getAllGenres() {

        return genreRepository.findAll();
    }

    /**
     * Guarda un nuevo género en la base de datos.
     *
     * <p>
     * El nombre del género se normaliza antes de persistirlo:
     * se eliminan los espacios al inicio y al final, y se capitaliza
     * la primera letra para garantizar consistencia en el almacenamiento.
     * </p>
     *
     * @param genre género que se desea guardar
     * @return género almacenado en la base de datos
     */
    public Genre saveGenre(Genre genre) {

        String normalized = genre.getName().trim();
        if (!normalized.isEmpty()) {
            normalized = Character.toUpperCase(normalized.charAt(0))
                    + normalized.substring(1).toLowerCase(java.util.Locale.ROOT);
        }
        genre.setName(normalized);

        return genreRepository.save(genre);
    }
}