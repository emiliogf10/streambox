package com.emilio.streambox.controller;

/**
 * Reglas de validación del parámetro {@code title} de las tres búsquedas por
 * título ({@code /api/movies/search}, {@code /api/series/search} y
 * {@code /api/admin/series}). Son constantes porque las anotaciones de
 * validación ({@code @Size}, {@code @Pattern}) solo admiten valores
 * constantes, y así las tres búsquedas comparten exactamente la misma regla y
 * el mismo mensaje.
 *
 * <p>
 * Un texto que no las cumple se rechaza con un 400 {@code VALIDATION_ERROR} y
 * el motivo en {@code validationErrors.title}, antes de llegar a la base de
 * datos.
 * </p>
 */
final class TitleSearchParameter {

    /**
     * Longitud máxima del texto buscado (en unidades UTF-16, como mide
     * {@code @Size}): la misma que la de un título ({@code MovieRequest},
     * {@code SeriesRequest}).
     *
     * <p>
     * <b>Por qué hay límite.</b> Sin él, un texto de miles de caracteres no
     * encuentra nada en la búsqueda exacta, pasa a la tolerante a erratas y
     * obliga a calcular {@code word_similarity} dos veces por fila recorriendo
     * la tabla entera (filtro y orden), con un coste que crece con la longitud
     * del texto: cualquier usuario registrado podía encarecer cada consulta a
     * voluntad. <b>Por qué este número.</b> Ningún título puede contener un
     * texto más largo que él mismo, así que con 150 no se pierde ninguna
     * búsqueda legítima.
     * </p>
     */
    static final int MAX_LENGTH = 150;

    /** Mensaje del límite de longitud. */
    static final String MAX_LENGTH_MESSAGE = "La búsqueda no puede superar los " + MAX_LENGTH + " caracteres";

    /**
     * Sin caracteres de control (categoría Unicode {@code Cc}: {@code U+0000}
     * a {@code U+001F}, {@code U+007F} y {@code U+0080} a {@code U+009F}).
     *
     * <p>
     * <b>El NUL ({@code U+0000}) es el motivo principal:</b> PostgreSQL no lo
     * admite en un texto, así que una búsqueda con {@code %00} acababa en un
     * 500 (y, mientras el texto iba como literal en el SQL, en una conexión
     * rota). Un error del cliente debe ser un 400, no un fallo del servidor que
     * cualquiera puede provocar y que llena el log de trazas.
     * </p>
     *
     * <p>
     * <b>Tampoco se permite el tabulador ni los saltos de línea</b>, aunque
     * PostgreSQL sí los admita: la búsqueda es un campo de una sola línea (el
     * tabulador mueve el foco, no se escribe), los títulos se escriben con
     * espacios, y un tabulador pegado en medio del texto no encontraría nada
     * sin explicar por qué. Una regla sin excepciones ("ningún carácter de
     * control") es además más fácil de documentar y de replicar en el cliente.
     * Los caracteres de formato invisibles ({@code Cf}, como {@code U+200B})
     * no son de control y siguen permitidos: no rompen nada y simplemente no
     * encuentran títulos.
     * </p>
     */
    static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}]*";

    /** Mensaje de los caracteres de control. */
    static final String NO_CONTROL_CHARACTERS_MESSAGE = "La búsqueda no puede contener caracteres de control";

    /** Respuesta 400 documentada en OpenAPI por las tres búsquedas. */
    static final String BAD_REQUEST_DESCRIPTION = "Parámetros de paginación u ordenación inválidos, o texto de "
            + "búsqueda (title) con más de " + MAX_LENGTH + " caracteres o con caracteres de control";

    private TitleSearchParameter() {
        // Solo constantes: no se instancia
    }
}
