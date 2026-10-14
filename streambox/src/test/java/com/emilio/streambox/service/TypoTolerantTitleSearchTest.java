package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.emilio.streambox.entity.Movie;

/**
 * Tests unitarios de {@link TypoTolerantTitleSearch}: cuándo se hace el
 * segundo intento (tolerante a erratas), con qué página y qué se devuelve.
 *
 * <p>
 * Con el repositorio simulado se comprueba la decisión en sí, que en H2 no se
 * ve (allí el segundo intento nunca encuentra nada). Que las
 * {@code Specification} hagan lo que dicen se prueba contra PostgreSQL real en
 * {@code postgres.PostgresTypoTolerantSearchIntegrationTest}.
 * </p>
 */
class TypoTolerantTitleSearchTest {

    /** Página pedida por el cliente: la 2, de 5 en 5, ordenada por título. */
    private static final Pageable SORTED_PAGE = PageRequest.of(2, 5, Sort.by("title", "id"));

    private JpaSpecificationExecutor<Movie> repository;
    private Function<String, Specification<Movie>> exactTitle;
    private Function<String, Specification<Movie>> similarTitle;
    private List<String> similarRequests;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(JpaSpecificationExecutor.class);
        similarRequests = new ArrayList<>();
        exactTitle = title -> (root, query, criteriaBuilder) -> null;
        similarTitle = title -> {
            similarRequests.add(title);
            return (root, query, criteriaBuilder) -> null;
        };
    }

    /** Sin título no hay nada que tolerar: un único intento con la página pedida. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   " })
    void sinTituloHaceUnaSolaConsultaConLaPaginaPedida(String title) {
        Page<Movie> all = page(3);
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(all);

        TypoTolerantTitleSearch.Result<Movie> result = search(title);

        assertSame(all, result.page());
        assertFalse(result.approximate());
        verify(repository).findAll(anySpecification(), any(Pageable.class));
        assertEquals(List.of(), similarRequests);
    }

    /** Si la búsqueda exacta encuentra algo, nunca se buscan parecidos (evita ruido: "matrix" no trae "Matilda"). */
    @Test
    void conCoincidenciasExactasNoHaySegundoIntento() {
        Page<Movie> exact = page(1);
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(exact);

        TypoTolerantTitleSearch.Result<Movie> result = search("matrix");

        assertSame(exact, result.page());
        assertFalse(result.approximate());
        verify(repository, times(1)).findAll(anySpecification(), any(Pageable.class));
        assertEquals(List.of(), similarRequests);
    }

    /**
     * Página fuera de rango de una búsqueda que SÍ tiene resultados: el
     * contenido está vacío pero el total no. Decidir por el contenido haría un
     * segundo intento y devolvería títulos parecidos a una búsqueda bien escrita.
     */
    @Test
    void unaPaginaFueraDeRangoConResultadosNoEsUnaBusquedaSinResultados() {
        Page<Movie> outOfRange = new PageImpl<>(List.of(), SORTED_PAGE, 4);
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(outOfRange);

        TypoTolerantTitleSearch.Result<Movie> result = search("interstellar");

        assertSame(outOfRange, result.page());
        assertFalse(result.approximate());
        verify(repository, times(1)).findAll(anySpecification(), any(Pageable.class));
    }

    /** Con menos de 5 letras o dígitos el segundo intento no se hace: ni siquiera se consulta. */
    @ParameterizedTest
    @ValueSource(strings = { "amro", "ma", "a b c d", "!!!!!!" })
    void unaBusquedaCortaSinResultadosNoHaceSegundoIntento(String title) {
        Page<Movie> empty = page(0);
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(empty);

        TypoTolerantTitleSearch.Result<Movie> result = search(title);

        assertSame(empty, result.page());
        assertFalse(result.approximate());
        verify(repository, times(1)).findAll(anySpecification(), any(Pageable.class));
        assertEquals(List.of(), similarRequests);
    }

    /**
     * Sin coincidencias exactas y con 5 letras o más: segundo intento con la
     * MISMA página y tamaño pero SIN orden, para que mande el orden por parecido
     * de la {@code Specification}; la respuesta es aproximada.
     */
    @Test
    void sinCoincidenciasHaceElSegundoIntentoSinOrdenYMarcaLaRespuestaComoAproximada() {
        Page<Movie> similar = page(2);
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(page(0)).thenReturn(similar);

        TypoTolerantTitleSearch.Result<Movie> result = search("interstelar");

        assertSame(similar, result.page());
        assertTrue(result.approximate());
        assertEquals(List.of("interstelar"), similarRequests);

        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(anySpecification(), pages.capture());
        assertEquals(List.of(SORTED_PAGE, PageRequest.of(2, 5)), pages.getAllValues());
        assertTrue(pages.getAllValues().get(1).getSort().isUnsorted());
    }

    /** Si tampoco hay parecidos, es simplemente "sin resultados": la página exacta y {@code approximate = false}. */
    @Test
    void siTampocoHayParecidosDevuelveLaPaginaExactaNoAproximada() {
        Page<Movie> exact = page(0);
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(exact).thenReturn(page(0));

        TypoTolerantTitleSearch.Result<Movie> result = search("zzzzzzzz");

        assertSame(exact, result.page());
        assertFalse(result.approximate());
        verify(repository, times(2)).findAll(anySpecification(), any(Pageable.class));
    }

    /** Los filtros recibidos se usan en los dos intentos: la lista no se modifica al añadir el del título. */
    @Test
    void noModificaLaListaDeFiltrosRecibida() {
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(page(0)).thenReturn(page(0));
        Specification<Movie> genre = (root, query, criteriaBuilder) -> null;
        List<Specification<Movie>> filters = new ArrayList<>(List.of(genre));

        TypoTolerantTitleSearch.find(repository, filters, "interstelar", exactTitle, similarTitle, SORTED_PAGE);

        assertEquals(List.of(genre), filters);
    }

    /** Una consulta sin paginar sigue sin paginar en el segundo intento (no lanza al pedir el número de página). */
    @Test
    void unaConsultaSinPaginarSigueSinPaginar() {
        when(repository.findAll(anySpecification(), any(Pageable.class))).thenReturn(page(0)).thenReturn(page(1));

        TypoTolerantTitleSearch.Result<Movie> result = TypoTolerantTitleSearch.find(
                repository, List.of(), "interstelar", exactTitle, similarTitle, Pageable.unpaged());

        assertTrue(result.approximate());
        ArgumentCaptor<Pageable> pages = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, times(2)).findAll(anySpecification(), pages.capture());
        assertTrue(pages.getAllValues().get(1).isUnpaged());
    }

    private TypoTolerantTitleSearch.Result<Movie> search(String title) {
        return TypoTolerantTitleSearch.find(repository, List.of(), title, exactTitle, similarTitle, SORTED_PAGE);
    }

    /** Página de {@code total} películas (todas en esta página, para simplificar). */
    private static Page<Movie> page(int total) {
        List<Movie> movies = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            movies.add(new Movie());
        }
        return new PageImpl<>(movies);
    }

    private static Specification<Movie> anySpecification() {
        return any();
    }
}
