package com.emilio.streambox.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests de {@code equals} y {@code hashCode} de las entidades: la igualdad se
 * basa en el identificador asignado por la base de datos.
 */
class EntityEqualityTest {

    private static Movie movie(Long id) {
        Movie movie = new Movie();
        movie.setId(id);
        return movie;
    }

    @Test
    void doblesConElMismoIdSonIguales() {
        assertEquals(movie(1L), movie(1L));
        assertEquals(movie(1L).hashCode(), movie(1L).hashCode());
    }

    @Test
    void entidadesConDistintoIdNoSonIguales() {
        assertNotEquals(movie(1L), movie(2L));
    }

    @Test
    void dosEntidadesSinGuardarNoSonIgualesEntreSi() {
        // Sin id no se sabe si representan la misma fila: solo son iguales a sí mismas.
        Movie a = movie(null);
        Movie b = movie(null);

        assertNotEquals(a, b);
        assertEquals(a, a);
    }

    @Test
    void entidadesDeClasesDistintasConElMismoIdNoSonIguales() {
        Genre genre = new Genre();
        genre.setId(1L);
        User user = new User();
        user.setId(1L);

        assertNotEquals(movie(1L), genre);
        assertNotEquals(genre, user);
        assertFalse(movie(1L).equals(null));
        assertFalse(movie(1L).equals("1"));
    }

    @Test
    void unaEntidadSigueSiendoEncontradaEnUnSetTrasRecibirSuId() {
        // Es el caso real: se añade a un Set antes de guardar y Hibernate le
        // asigna el id después. El hashCode no debe cambiar.
        Movie movie = movie(null);
        Set<Movie> set = new HashSet<>();
        set.add(movie);

        movie.setId(5L);

        assertTrue(set.contains(movie));
    }

    @Test
    void ungeneroYUnUsuarioTambienUsanElId() {
        Genre g1 = new Genre();
        g1.setId(3L);
        Genre g2 = new Genre();
        g2.setId(3L);
        User u1 = new User();
        u1.setId(4L);
        User u2 = new User();
        u2.setId(4L);

        assertEquals(g1, g2);
        assertEquals(u1, u2);
    }

    @Test
    void seriesYEpisodiosTambienUsanElIdYNoSeConfundenConPeliculas() {
        Series s1 = new Series();
        s1.setId(7L);
        Series s2 = new Series();
        s2.setId(7L);
        Episode e1 = new Episode();
        e1.setId(7L);
        Episode e2 = new Episode();
        e2.setId(7L);

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
        assertEquals(e1, e2);
        assertNotEquals(s1, e1);
        assertNotEquals(s1, movie(7L));
        assertNotEquals(new Series(), new Series());

        // El hashCode no cambia al recibir el id (la serie sigue en el Set)
        Series unsaved = new Series();
        Set<Series> set = new HashSet<>();
        set.add(unsaved);
        unsaved.setId(9L);
        assertTrue(set.contains(unsaved));
    }
}
