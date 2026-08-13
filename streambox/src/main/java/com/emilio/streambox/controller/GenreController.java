package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateGenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.mapper.GenreMapper;
import com.emilio.streambox.service.GenreService;

import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar las operaciones relacionadas
 * con los géneros cinematográficos de Streambox.
 */
@RestController
@RequestMapping("/api/genres")
public class GenreController {

    private final GenreService genreService;

    /**
     * Crea una instancia del controlador de géneros.
     *
     * @param genreService servicio encargado de gestionar
     *                     la lógica de negocio de los géneros
     */
    public GenreController(GenreService genreService) {
        this.genreService = genreService;
    }

    /**
     * Obtiene todos los géneros almacenados.
     *
     * @return lista de géneros
     */
    @GetMapping
    public List<GenreResponse> getGenres() {

        return GenreMapper.toResponseList(
                genreService.getAllGenres());
    }

    /**
     * Crea un nuevo género.
     *
     * @param request datos del género que se desea crear
     * @return género creado
     */
    @PostMapping
    public GenreResponse createGenre(
            @Valid @RequestBody CreateGenreRequest request) {

        Genre genre = GenreMapper.toEntity(request);

        Genre savedGenre = genreService.saveGenre(genre);

        return GenreMapper.toResponse(savedGenre);
    }
}