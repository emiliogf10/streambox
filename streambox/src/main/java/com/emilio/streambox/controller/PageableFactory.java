package com.emilio.streambox.controller;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.emilio.streambox.exception.InvalidParameterException;

/**
 * Construye y valida la paginación de los listados del catálogo (películas y
 * series).
 *
 * <p>
 * Vivía dentro de {@code MovieController}; se extrajo al añadir las series
 * para que los dos catálogos apliquen <em>exactamente</em> las mismas reglas
 * (y mensajes) de {@code sort}, {@code direction} y desbordamiento de página,
 * sin copiar el código. Lo único que cambia entre listados es la lista blanca
 * de campos ordenables, que recibe cada llamada. Los límites de {@code page}
 * (≥ 0) y {@code size} (1 a 100) siguen en los parámetros de cada
 * controlador con {@code @Min}/{@code @Max}, porque así los publica el OpenAPI.
 * </p>
 *
 * <p>
 * Es de paquete porque solo la usan los controladores.
 * </p>
 */
final class PageableFactory {

    private PageableFactory() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Construye la paginación validando el campo y la dirección de ordenación.
     *
     * <p>
     * Se añade el {@code id} como criterio secundario: si varios elementos
     * tienen el mismo valor en el campo elegido (por ejemplo el mismo año), el
     * orden entre ellos sería indefinido y uno podría aparecer en dos páginas
     * o en ninguna. Con el {@code id} el orden es siempre determinista.
     * </p>
     *
     * <p>
     * El desempate usa la <b>misma dirección</b> que el campo principal: así,
     * con {@code sort=createdAt&direction=desc} los elementos con la misma
     * fecha salen también del más nuevo (id mayor) al más antiguo y el
     * resultado es exactamente el inverso del orden ascendente. Si el
     * desempate fuese siempre ascendente, el orden seguiría siendo estable
     * pero incoherente (por ejemplo, el "último" añadido con un mismo valor
     * saldría detrás de los anteriores en un listado "más recientes primero").
     * </p>
     *
     * <p>
     * También se valida que el desplazamiento ({@code page × size}) quepa en
     * un {@code int}; ver {@link #requireOffsetWithinIntRange(int, int)}.
     * </p>
     *
     * @param page           número de página (ya validado como no negativo)
     * @param size           tamaño de página (ya validado entre 1 y 100)
     * @param sort           campo de ordenación solicitado por el cliente
     * @param direction      dirección solicitada ({@code asc} o {@code desc}, sin
     *                       distinguir mayúsculas); ver {@link #parseDirection(String)}
     * @param sortableFields lista blanca de campos por los que se puede ordenar
     * @return paginación ordenada por el campo indicado en la dirección pedida
     * @throws InvalidParameterException si el campo, la dirección o la página no están permitidos
     */
    static Pageable build(int page, int size, String sort, String direction, Set<String> sortableFields) {

        requireOffsetWithinIntRange(page, size);

        if (!sortableFields.contains(sort)) {
            throw new InvalidParameterException(
                    "sort",
                    "Campo de ordenación no permitido. Valores válidos: "
                            + String.join(", ", new TreeSet<>(sortableFields)));
        }

        Sort.Direction order = parseDirection(direction);

        Sort ordering = Sort.by(order, sort);
        if (!"id".equals(sort)) {
            ordering = ordering.and(Sort.by(order, "id"));
        }

        return PageRequest.of(page, size, ordering);
    }

    /**
     * Rechaza las páginas cuyo desplazamiento no cabe en un {@code int}.
     *
     * <p>
     * Spring Data JPA calcula el desplazamiento de la consulta
     * ({@code page × size}) como {@code int}. Si no cabe lanza
     * {@code InvalidDataAccessApiUsageException} (que acabaría en un 500 por
     * un error que es del cliente y ensuciaría los logs con un ERROR), y
     * además {@code page + 1} desborda con {@code page=Integer.MAX_VALUE} y
     * {@code hasNext} salía {@code true} en una página vacía. Se valida aquí,
     * antes de llegar a la capa de datos, para responder 400 con el mismo
     * formato que un {@code sort} o {@code direction} no permitidos.
     * </p>
     *
     * <p>
     * Límite elegido: el desplazamiento (calculado con {@code long} para que
     * la propia comprobación no desborde) debe ser <b>estrictamente menor</b>
     * que {@code Integer.MAX_VALUE}. Es la regla más simple que cubre los dos
     * problemas: con {@code offset < Integer.MAX_VALUE} y {@code size >= 1}
     * se cumple {@code page <= offset}, así que {@code page + 1} nunca
     * desborda. Perder el offset exacto {@code 2147483647} es irrelevante
     * (ningún catálogo tiene tantos elementos). Una página muy grande pero
     * dentro del límite, posterior a la última, sigue siendo una consulta
     * válida y responde 200 con {@code content} vacío.
     * </p>
     *
     * @param page número de página (ya validado como no negativo)
     * @param size tamaño de página (ya validado entre 1 y 100)
     * @throws InvalidParameterException si {@code page × size} no es menor que {@code Integer.MAX_VALUE}
     */
    private static void requireOffsetWithinIntRange(int page, int size) {

        if ((long) page * size >= Integer.MAX_VALUE) {
            throw new InvalidParameterException(
                    "page",
                    "La página solicitada es demasiado grande para el tamaño de página indicado");
        }
    }

    /**
     * Interpreta la dirección de ordenación recibida.
     *
     * <p>
     * Se hace a mano en lugar de usar {@code Sort.Direction.fromString}: esta
     * última lanza una excepción con un mensaje en inglés del framework, que
     * no debe llegar al cliente, y {@code fromOptionalString} trata un valor
     * vacío como "no indicado" en lugar de rechazarlo.
     * </p>
     *
     * @param direction valor del parámetro {@code direction}
     * @return la dirección correspondiente
     * @throws InvalidParameterException si no es {@code asc} ni {@code desc}
     */
    private static Sort.Direction parseDirection(String direction) {

        if ("asc".equalsIgnoreCase(direction)) {
            return Sort.Direction.ASC;
        }
        if ("desc".equalsIgnoreCase(direction)) {
            return Sort.Direction.DESC;
        }
        throw new InvalidParameterException(
                "direction",
                "Dirección de ordenación no permitida. Valores válidos: asc, desc");
    }
}
