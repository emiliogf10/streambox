package com.emilio.streambox.validation;

/**
 * Expresiones regulares (para {@code @Pattern}) que impiden los caracteres de
 * control en los textos que llegan del cliente. Son constantes porque las
 * anotaciones de validación solo admiten valores constantes; viven aquí para
 * que todos los textos (búsquedas, títulos, descripciones, {@code by-title})
 * usen exactamente la misma regla.
 *
 * <p>
 * <b>Qué son.</b> La categoría Unicode {@code Cc}: {@code U+0000} a
 * {@code U+001F}, {@code U+007F} y {@code U+0080} a {@code U+009F}. Incluye el
 * tabulador ({@code \t}) y los saltos de línea ({@code \n}, {@code \r}).
 * </p>
 *
 * <p>
 * <b>Por qué se rechazan: el NUL ({@code U+0000}).</b> PostgreSQL no lo admite
 * en un texto ({@code SQLSTATE 22021}). Spring traduce ese error a una
 * {@code DataIntegrityViolationException}, que la API respondía como un 409
 * «conflicto con datos existentes» (falso: no había ningún conflicto) y que hoy
 * acabaría en un 500. Es un error del cliente y debe ser un 400
 * {@code VALIDATION_ERROR} que diga qué campo falla, sin llegar a la base de
 * datos. H2 sí guarda el NUL, así que la suite normal no veía el fallo: lo
 * prueba {@code PostgresControlCharactersIntegrationTest}. El resto de
 * controles no rompen PostgreSQL, pero en un título o una sinopsis no tienen
 * ningún uso legítimo (son invisibles o mueven el cursor) y una regla sin
 * excepciones es más fácil de documentar y de replicar en el cliente.
 * </p>
 *
 * <p>
 * Los caracteres de formato invisibles ({@code Cf}, como {@code U+200B}) no
 * son de control y siguen permitidos: no rompen nada.
 * </p>
 */
public final class ControlCharacters {

    /**
     * Texto de <b>una sola línea</b> (títulos y búsquedas): ningún carácter de
     * control, tampoco el tabulador ni los saltos de línea. El campo del
     * formulario es un {@code <input>}, donde el tabulador mueve el foco y no
     * se pueden escribir saltos de línea; un salto pegado en un título no lo
     * haría encontrable y se mostraría como un espacio.
     */
    public static final String SINGLE_LINE_TEXT = "[^\\p{Cc}]*";

    /**
     * Texto de <b>varias líneas</b> (sinopsis de películas, series y
     * episodios): ningún carácter de control salvo el tabulador
     * ({@code U+0009}), el salto de línea ({@code U+000A}) y el retorno de
     * carro ({@code U+000D}).
     *
     * <p>
     * <b>Por qué se permiten esos tres.</b> El panel de administración escribe
     * la sinopsis en un {@code <textarea>}: pulsar Intro mete un {@code \n}, y
     * un texto pegado desde otro programa puede traer {@code \r\n} o
     * tabuladores. PostgreSQL los admite y las sinopsis que ya estén guardadas
     * con saltos de línea deben poder reenviarse en un {@code PUT}; rechazarlos
     * rompería el formulario sin ganar nada. El resto de controles (el NUL, el
     * tabulador vertical, el {@code ESC}...) no los produce ningún teclado y se
     * siguen rechazando.
     * </p>
     *
     * <p>
     * La clase {@code [\t\n\r[^\p{Cc}]]} es la <em>unión</em> de esos tres
     * caracteres y de todo lo que no es de control (sintaxis de
     * {@code java.util.regex}).
     * </p>
     */
    public static final String MULTILINE_TEXT = "[\\t\\n\\r[^\\p{Cc}]]*";

    private ControlCharacters() {
        // Solo constantes: no se instancia
    }
}
