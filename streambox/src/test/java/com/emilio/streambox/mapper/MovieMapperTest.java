package com.emilio.streambox.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;

/** Tests unitarios de {@link MovieMapper}. */
class MovieMapperTest {

    private static MovieRequest request(String title) {
        return new MovieRequest(title, "Sinopsis", 120, 2001,
                "https://example.com/i.jpg", "https://example.com/v.mp4", Set.of(1L));
    }

    private static Genre genre(Long id, String name) {
        Genre genre = new Genre();
        genre.setId(id);
        genre.setName(name);
        return genre;
    }

    @Test
    void toEntityCopiaLosCamposPeroNoAsignaIdGenerosNiFecha() {
        Movie movie = MovieMapper.toEntity(request("Dune"));

        assertEquals("Dune", movie.getTitle());
        assertEquals("Sinopsis", movie.getDescription());
        assertEquals(120, movie.getDuration());
        assertEquals(2001, movie.getReleaseYear());
        assertEquals("https://example.com/i.jpg", movie.getImageUrl());
        assertEquals("https://example.com/v.mp4", movie.getVideoUrl());
        assertNull(movie.getId());
        assertNull(movie.getCreatedAt());
        assertEquals(0, movie.getGenres().size());
    }

    @Test
    void updateEntityNoTocaIdFechaNiGeneros() {
        Movie movie = new Movie();
        movie.setId(7L);
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        movie.setCreatedAt(created);
        Set<Genre> genres = new HashSet<>(Set.of(genre(1L, "Drama")));
        movie.setGenres(genres);

        MovieMapper.updateEntity(request("Nuevo título"), movie);

        assertEquals("Nuevo título", movie.getTitle());
        assertEquals(7L, movie.getId());
        assertEquals(created, movie.getCreatedAt());
        assertSame(genres, movie.getGenres());
    }

    @Test
    void toResponseOrdenaLosGenerosPorNombre() {
        Movie movie = MovieMapper.toEntity(request("Orden"));
        movie.setId(1L);
        movie.setGenres(new HashSet<>(Set.of(genre(3L, "Scifi"), genre(1L, "Action"), genre(2L, "Drama"))));

        MovieResponse response = MovieMapper.toResponse(movie);

        assertEquals(List.of("Action", "Drama", "Scifi"),
                response.genres().stream().map(g -> g.name()).toList());
    }

    @Test
    void toResponseListConservaElOrden() {
        Movie a = MovieMapper.toEntity(request("A"));
        Movie b = MovieMapper.toEntity(request("B"));

        List<MovieResponse> responses = MovieMapper.toResponseList(List.of(b, a));

        assertEquals("B", responses.get(0).title());
        assertEquals("A", responses.get(1).title());
    }
}
