package com.emilio.streambox.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.CreateGenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.mapper.GenreMapper;
import com.emilio.streambox.repository.GenreRepository;

/**
 * Servicio con la lógica de negocio de los géneros cinematográficos.
 *
 * <p>
 * Devuelve {@link GenreResponse} y no la entidad, igual que el resto de
 * servicios.
 * </p>
 */
@Service
public class GenreService {

    private final GenreRepository genreRepository;

    /**
     * Crea el servicio de géneros.
     *
     * @param genreRepository repositorio de géneros
     */
    public GenreService(GenreRepository genreRepository) {
        this.genreRepository = genreRepository;
    }

    /**
     * Obtiene todos los géneros.
     *
     * @return lista de géneros
     */
    @Transactional(readOnly = true)
    public List<GenreResponse> getAllGenres() {

        return GenreMapper.toResponseList(genreRepository.findAll());
    }

    /**
     * Crea un género.
     *
     * <p>
     * El nombre se normaliza antes de guardarlo: se eliminan los espacios
     * exteriores y se deja la primera letra en mayúscula y el resto en
     * minúsculas, para que {@code "ACCION"} y {@code "accion"} no sean dos
     * géneros distintos. Si el nombre ya existe, la restricción
     * {@code UNIQUE} de la base de datos lo impide y la API responde 409.
     * </p>
     *
     * @param request datos del género (ya validados)
     * @return el género creado
     */
    @Transactional
    public GenreResponse createGenre(CreateGenreRequest request) {

        String normalized = request.getName().trim();
        if (!normalized.isEmpty()) {
            normalized = Character.toUpperCase(normalized.charAt(0))
                    + normalized.substring(1).toLowerCase(Locale.ROOT);
        }

        var genre = GenreMapper.toEntity(request);
        genre.setName(normalized);

        return GenreMapper.toResponse(genreRepository.save(genre));
    }
}
