package com.emilio.streambox.mapper;

import java.util.List;

import com.emilio.streambox.dto.CreateGenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;

/**
 * Clase encargada de realizar las conversiones entre la entidad
 * {@link Genre} y los DTO utilizados por la API.
 */
public class GenreMapper {

    /**
     * Constructor privado para evitar la creación de instancias.
     */
    private GenreMapper() {
        // Evita instanciar la clase
    }

    /**
     * Convierte una petición de creación de género en una entidad
     * {@link Genre}.
     *
     * @param request datos recibidos desde la API
     * @return entidad Genre creada a partir de los datos recibidos
     */
    public static Genre toEntity(CreateGenreRequest request) {

        Genre genre = new Genre();

        genre.setName(request.getName());

        return genre;
    }

    /**
     * Convierte una entidad {@link Genre} en un {@link GenreResponse}.
     *
     * @param genre entidad que se desea convertir
     * @return DTO con los datos del género
     */
    public static GenreResponse toResponse(Genre genre) {

        GenreResponse response = new GenreResponse();

        response.setId(genre.getId());
        response.setName(genre.getName());

        return response;
    }

    /**
     * Convierte una lista de entidades Genre en una lista
     * de {@link GenreResponse}.
     *
     * @param genres lista de géneros
     * @return lista de DTOs correspondientes a los géneros
     */
    public static List<GenreResponse> toResponseList(List<Genre> genres) {

        return genres.stream()
                .map(GenreMapper::toResponse)
                .toList();
    }
}