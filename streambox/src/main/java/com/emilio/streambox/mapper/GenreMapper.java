package com.emilio.streambox.mapper;

import java.util.List;

import com.emilio.streambox.dto.GenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;

/**
 * Convierte entre la entidad {@link Genre} y los DTO de la API.
 */
public final class GenreMapper {

    private GenreMapper() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Crea una entidad nueva a partir de los datos recibidos.
     *
     * @param request datos del género recibidos del cliente
     * @return entidad {@link Genre} sin guardar
     */
    public static Genre toEntity(GenreRequest request) {

        Genre genre = new Genre();
        genre.setName(request.name());
        return genre;
    }

    /**
     * Convierte un género en el DTO que se devuelve al cliente.
     *
     * @param genre entidad a convertir
     * @return DTO con el identificador y el nombre del género
     */
    public static GenreResponse toResponse(Genre genre) {

        return new GenreResponse(genre.getId(), genre.getName());
    }

    /**
     * Convierte una lista de géneros en una lista de DTO.
     *
     * @param genres géneros a convertir
     * @return lista de DTO en el mismo orden
     */
    public static List<GenreResponse> toResponseList(List<Genre> genres) {

        return genres.stream()
                .map(GenreMapper::toResponse)
                .toList();
    }
}
