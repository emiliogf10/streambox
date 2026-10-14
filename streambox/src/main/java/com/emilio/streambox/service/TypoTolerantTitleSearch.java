package com.emilio.streambox.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.emilio.streambox.specification.TitleSearch;

/**
 * Búsqueda por título en dos intentos, compartida por películas
 * ({@link MovieService}) y series ({@link SeriesService}, catálogo y panel):
 * primero la búsqueda exacta y, solo si no encuentra nada, la tolerante a
 * erratas.
 *
 * <h2>Reglas</h2>
 * <ol>
 * <li><b>Primer intento:</b> el título <em>contiene</em> el texto (sin
 * mayúsculas ni acentos), con el orden y la página pedidos.</li>
 * <li><b>Segundo intento, solo si el primero no encontró nada</b>
 * ({@code getTotalElements() == 0}) <b>y</b> el texto tiene al menos 5 letras
 * o dígitos ({@link TitleSearch#toleratesTypos(String)}): los mismos filtros,
 * cambiando "contiene" por "se parece".
 * <ul>
 * <li>Se mira el <b>total</b> y no el contenido de la página: pedir una página
 * fuera de rango de una búsqueda que sí tiene resultados devuelve una página
 * vacía, pero no debe convertir la respuesta en una de títulos parecidos.
 * Spring Data calcula ese total con un {@code COUNT} solo cuando hace falta
 * (en la página 0 con menos resultados que el tamaño lo deduce sin
 * consultar).</li>
 * <li>No se suma siempre a la búsqueda exacta: buscar "matrix" bien escrito
 * traería también "Matilda" (comparten "mat"). Solo se recurre a los parecidos
 * cuando no hay nada mejor que enseñar.</li>
 * <li>La página del segundo intento va <b>sin orden</b>: así la
 * {@code Specification} de parecido ordena del título más parecido al menos
 * (desempate por {@code id}). El {@code sort} pedido se ignora a propósito:
 * ordenar por título o por año unos resultados aproximados escondería el que
 * el usuario buscaba entre los que solo comparten unas letras.</li>
 * </ul>
 * </li>
 * <li><b>Si el segundo intento tampoco encuentra nada</b>, se devuelve la
 * página (vacía) del primero con {@code approximate = false}: para el cliente
 * es simplemente "sin resultados", igual que si no hubiera habido segundo
 * intento.</li>
 * </ol>
 *
 * <p>
 * En H2 (tests) la búsqueda tolerante nunca encuentra nada (ver
 * {@link TitleSearch}), así que el resultado es siempre el del primer intento.
 * </p>
 *
 * <p>
 * Es de paquete y sin estado: solo la usan los servicios del catálogo, que la
 * llaman dentro de su propia transacción de lectura.
 * </p>
 */
final class TypoTolerantTitleSearch {

    private TypoTolerantTitleSearch() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Resultado de la búsqueda.
     *
     * @param page        página de entidades encontradas
     * @param approximate {@code true} si viene del segundo intento (títulos parecidos)
     * @param <E>         tipo de entidad
     */
    record Result<E>(Page<E> page, boolean approximate) {
    }

    /**
     * Ejecuta la búsqueda en uno o dos intentos según las reglas de la clase.
     *
     * @param repository   repositorio con consultas por {@code Specification}
     * @param filters      filtros que no son el título (género, año, visibilidad...);
     *                     se aplican igual en los dos intentos
     * @param title        texto buscado ({@code null} o en blanco = sin filtro de título)
     * @param exactTitle   fábrica del filtro "contiene" (p. ej. {@code MovieSpecification::hasTitle})
     * @param similarTitle fábrica del filtro "se parece" (p. ej. {@code MovieSpecification::hasSimilarTitle})
     * @param pageable     página, tamaño y orden pedidos
     * @param <E>          tipo de entidad
     * @return la página encontrada y si es aproximada
     */
    static <E> Result<E> find(
            JpaSpecificationExecutor<E> repository,
            List<Specification<E>> filters,
            String title,
            Function<String, Specification<E>> exactTitle,
            Function<String, Specification<E>> similarTitle,
            Pageable pageable) {

        if (title == null || title.isBlank()) {
            return new Result<>(repository.findAll(Specification.allOf(filters), pageable), false);
        }

        Page<E> exact = repository.findAll(Specification.allOf(with(filters, exactTitle.apply(title))), pageable);

        if (exact.getTotalElements() > 0 || !TitleSearch.toleratesTypos(title)) {
            return new Result<>(exact, false);
        }

        Page<E> similar = repository.findAll(
                Specification.allOf(with(filters, similarTitle.apply(title))), withoutSort(pageable));

        return similar.getTotalElements() > 0 ? new Result<>(similar, true) : new Result<>(exact, false);
    }

    /** Copia de los filtros con uno más (no modifica la lista recibida, que se usa en los dos intentos). */
    private static <E> List<Specification<E>> with(List<Specification<E>> filters, Specification<E> extra) {

        List<Specification<E>> all = new ArrayList<>(filters);
        all.add(extra);
        return all;
    }

    /**
     * La misma página y tamaño sin orden, para que mande el orden por parecido
     * de la {@code Specification} (Spring Data lo sustituiría por el de la página).
     */
    private static Pageable withoutSort(Pageable pageable) {

        return pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()) : Pageable.unpaged();
    }
}
