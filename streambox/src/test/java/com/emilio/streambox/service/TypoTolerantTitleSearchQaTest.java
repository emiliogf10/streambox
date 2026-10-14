package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.emilio.streambox.entity.Series;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * Revisión de QA de {@link TypoTolerantTitleSearch}: que el <b>segundo
 * intento</b> lleve de verdad todos los filtros recibidos.
 *
 * <p>
 * Es lo que mantiene ocultas las series sin episodios en la búsqueda pública
 * ({@code SeriesSpecification.hasEpisodes()} es uno de esos filtros) y lo que
 * hace que el género y el año se respeten entre los títulos parecidos. Los
 * tests contra PostgreSQL real ya lo comprueban, pero se omiten sin Docker, y
 * con H2 el segundo intento nunca encuentra nada: sin este test, un segundo
 * intento que olvidara los filtros pasaría la suite con H2 en verde (se
 * comprobó con esa mutación: {@code TypoTolerantTitleSearchTest} no la
 * detectaba).
 * </p>
 *
 * <p>
 * Cada {@code Specification} anota su nombre al construir el predicado; se
 * ejecutan las dos consultas capturadas y se compara qué filtros contiene cada
 * una. Los predicados son {@code null} (Spring Data los ignora al combinar),
 * así que no hace falta una base de datos.
 * </p>
 */
class TypoTolerantTitleSearchQaTest {

    @Test
    @SuppressWarnings("unchecked")
    void elSegundoIntentoLlevaTodosLosFiltrosDelPrimeroCambiandoSoloElDeTitulo() {
        JpaSpecificationExecutor<Series> repository = mock(JpaSpecificationExecutor.class);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()))
                .thenReturn(new PageImpl<>(List.of(new Series())));

        List<String> applied = new ArrayList<>();
        List<Specification<Series>> filters = List.of(recording("visible", applied), recording("genre", applied));
        Function<String, Specification<Series>> exact = title -> recording("exact:" + title, applied);
        Function<String, Specification<Series>> similar = title -> recording("similar:" + title, applied);

        TypoTolerantTitleSearch.Result<Series> result = TypoTolerantTitleSearch.find(
                repository, filters, "interstelar", exact, similar, PageRequest.of(0, 10));

        assertTrue(result.approximate());
        ArgumentCaptor<Specification<Series>> specifications = ArgumentCaptor.forClass(Specification.class);
        verify(repository, times(2)).findAll(specifications.capture(), any(Pageable.class));

        assertEquals(List.of("visible", "genre", "exact:interstelar"),
                predicatesOf(specifications.getAllValues().get(0), applied));
        assertEquals(List.of("visible", "genre", "similar:interstelar"),
                predicatesOf(specifications.getAllValues().get(1), applied),
                "el segundo intento debe conservar la visibilidad y los demás filtros");
    }

    /** {@code Specification} que solo anota su nombre cuando se construye su predicado. */
    private static Specification<Series> recording(String name, List<String> applied) {
        return (root, query, criteriaBuilder) -> {
            applied.add(name);
            return null;
        };
    }

    /** Construye el predicado de la especificación y devuelve los nombres de las que la forman. */
    @SuppressWarnings("unchecked")
    private static List<String> predicatesOf(Specification<Series> specification, List<String> applied) {
        applied.clear();
        specification.toPredicate(mock(Root.class), mock(CriteriaQuery.class), mock(CriteriaBuilder.class));
        return List.copyOf(applied);
    }
}
