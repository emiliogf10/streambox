package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Las dos vistas de géneros contra <b>PostgreSQL real</b>: la de gestión con
 * recuentos ({@code GET /api/admin/genres}) y el filtro por títulos visibles
 * ({@code GET /api/genres?withTitles=...}).
 *
 * <p>
 * Qué protege frente a H2:
 * </p>
 * <ul>
 *   <li>Los recuentos son subconsultas escalares correlacionadas en el
 *       {@code SELECT} (JPQL que traduce Hibernate): PostgreSQL devuelve
 *       {@code COUNT} como {@code bigint} y los alias deben llegar a la
 *       proyección {@code GenreUsage}. Además, que coinciden con las cifras
 *       del 409 {@code GENRE_IN_USE}, que se calculan con otras consultas.</li>
 *   <li>El filtro es SQL nativo con {@code EXISTS} anidados (géneros de series
 *       con episodios): debe excluir de verdad las series vacías también en
 *       PostgreSQL.</li>
 * </ul>
 */
class PostgresGenreViewsIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;

    private String adminToken;
    private String userToken;

    private Genre comedia;
    private Genre drama;
    private Genre vacio;
    private Genre western;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = tokenFor(saveUser("pgviewsadmin", Role.ADMIN));
        userToken = tokenFor(saveUser("pgviewsuser", Role.USER));

        western = saveGenre("Western");
        drama = saveGenre("Drama");
        vacio = saveGenre("Vacío");
        comedia = saveGenre("Comedia");

        // Drama: 2 películas y 2 series (una vacía). Comedia: 1 película (compartida)
        // y 1 serie vacía. Western: solo 1 serie con episodios. Vacío: nada.
        saveMovie("Uno", 2000, drama);
        saveMovie("Dos", 2001, drama, comedia);
        saveEpisode(saveSeries("Serie de drama", 2010, drama), 1, 1);
        saveSeries("Serie vacía de drama", 2011, drama);
        saveSeries("Serie vacía de comedia", 2012, comedia);
        saveEpisode(saveSeries("Serie del oeste", 2013, western), 1, 1);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** Recuentos por nombre, con las series vacías incluidas, y un USER no los ve. */
    @Test
    void laVistaDeGestionCuentaPeliculasYSeriesSoloParaAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/genres").header("Authorization", userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/genres").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(comedia.getId()))
                .andExpect(jsonPath("$[0].movieCount").value(1))
                .andExpect(jsonPath("$[0].seriesCount").value(1))
                .andExpect(jsonPath("$[1].id").value(drama.getId()))
                .andExpect(jsonPath("$[1].movieCount").value(2))
                .andExpect(jsonPath("$[1].seriesCount").value(2))
                .andExpect(jsonPath("$[2].id").value(vacio.getId()))
                .andExpect(jsonPath("$[2].movieCount").value(0))
                .andExpect(jsonPath("$[2].seriesCount").value(0))
                .andExpect(jsonPath("$[3].id").value(western.getId()))
                .andExpect(jsonPath("$[3].movieCount").value(0))
                .andExpect(jsonPath("$[3].seriesCount").value(1));
    }

    /**
     * Las cifras de la vista son las del 409 (que se calculan con otras
     * consultas, las derivadas {@code countByGenres_Id}).
     *
     * <p>
     * Primero se pide la vista y después se borra cada género: para cada uno,
     * los recuentos que dio {@code GET /api/admin/genres} deben ser los que
     * dice el mensaje del 409, y el único con 0 y 0 debe borrarse (204). Si
     * la vista y el borrado contaran distinto en PostgreSQL, el panel
     * desactivaría «Borrar» donde no debe (o al revés). Los mensajes se
     * escriben a mano para no repetir la lógica del servicio.
     * </p>
     */
    @Test
    void losRecuentosCoincidenConElConflictoAlBorrar() throws Exception {
        Map<Long, long[]> view = adminView();

        expectInUse(view, drama, 2, 2, "lo usan 2 películas y 2 series. "
                + "Quítalo de esas películas y de esas series");
        expectInUse(view, comedia, 1, 1, "lo usan 1 película y 1 serie. "
                + "Quítalo de esa película y de esa serie");
        expectInUse(view, western, 0, 1, "lo usa 1 serie. Quítalo de esa serie");

        assertArrayEquals(new long[] { 0, 0 }, view.get(vacio.getId()));
        mockMvc.perform(delete("/api/genres/{id}", vacio.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    /**
     * Pide {@code GET /api/admin/genres} y devuelve, por id de género, sus
     * recuentos {@code [movieCount, seriesCount]}.
     */
    private Map<Long, long[]> adminView() throws Exception {
        JsonNode body = new ObjectMapper().readTree(mockMvc
                .perform(get("/api/admin/genres").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        Map<Long, long[]> counts = new HashMap<>();
        body.forEach(node -> counts.put(node.get("id").asLong(),
                new long[] { node.get("movieCount").asLong(), node.get("seriesCount").asLong() }));
        assertEquals(4, counts.size());
        return counts;
    }

    /**
     * Comprueba que la vista dio {@code movies} y {@code series} para
     * {@code genre} y que borrarlo da el 409 con esas mismas cifras.
     */
    private void expectInUse(Map<Long, long[]> view, Genre genre, long movies, long series, String usage)
            throws Exception {
        assertArrayEquals(new long[] { movies, series }, view.get(genre.getId()),
                "recuentos de la vista para " + genre.getName());

        mockMvc.perform(delete("/api/genres/{id}", genre.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género «" + genre.getName()
                        + "»: " + usage + " antes de borrarlo."));
    }

    /** Películas: Comedia y Drama, por nombre. */
    @Test
    void elFiltroDePeliculasDevuelveLosGenerosConPeliculas() throws Exception {
        mockMvc.perform(get("/api/genres").param("withTitles", "movies").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(comedia.getId()))
                .andExpect(jsonPath("$[1].id").value(drama.getId()));
    }

    /** Series: Drama y Western; Comedia no (su única serie está vacía). */
    @Test
    void elFiltroDeSeriesIgnoraLasSeriesSinEpisodios() throws Exception {
        mockMvc.perform(get("/api/genres").param("withTitles", "series").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(drama.getId()))
                .andExpect(jsonPath("$[1].id").value(western.getId()));
    }
}
