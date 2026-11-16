package com.emilio.streambox.validation;

/**
 * Recorta los caracteres invisibles de los extremos de un texto en tiempo
 * <b>lineal</b>, sin expresiones regulares.
 *
 * <p>
 * <b>Qué se considera invisible.</b> Los separadores Unicode ({@code \p{Z}}:
 * espacio, espacio de no separación {@code U+00A0}, espacio ideográfico
 * {@code U+3000}, separadores de línea y de párrafo...), los caracteres de
 * control ({@code \p{Cc}}: tabulador, saltos de línea, NUL...) y los de formato
 * ({@code \p{Cf}}: espacio de ancho cero {@code U+200B}, BOM {@code U+FEFF}...).
 * Es exactamente la clase {@code [\p{Z}\p{Cc}\p{Cf}]} de {@code java.util.regex}
 * (las dos se basan en {@link Character#getType(int)}), y se recorre por
 * <i>code points</i> como hace el motor de expresiones regulares, así que un
 * carácter suplementario (dos {@code char}) se trata como uno solo. Lo que hay
 * <i>dentro</i> del texto no se toca.
 * </p>
 *
 * <p>
 * <b>Por qué sin expresión regular (ReDoS).</b> Antes se usaba
 * {@code ^[…]+|[…]+$} con {@code replaceAll("")}. La segunda rama no tiene
 * ancla inicial: ante una racha interior de n invisibles seguida de un carácter
 * visible ({@code "a" + " ".repeat(n) + "a"}), el motor la intenta desde cada
 * posición de la racha, consume hasta el final de esta y fracasa al no
 * encontrar el fin del texto: n + (n-1) + … pasos, O(n²). Con los 64 KB que
 * admite el cuerpo de una petición eran unos 25 s de CPU por campo, y la
 * política de contraseñas lo recibía desde el registro anónimo (NV-1 de la
 * auditoría de seguridad run-3). Este recorrido mira cada carácter como mucho
 * una vez desde cada extremo.
 * </p>
 *
 * <p>
 * La usan {@code PasswordPolicy}, {@code UserService} y {@code GenreService}
 * para que los tres recorten exactamente igual.
 * </p>
 */
public final class InvisibleEdges {

    private InvisibleEdges() {
        // Solo métodos estáticos: no se instancia
    }

    /**
     * Quita los caracteres invisibles (ver la clase) de los dos extremos.
     *
     * @param value texto (no {@code null})
     * @return el texto sin invisibles al principio ni al final (puede quedar vacío)
     */
    public static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isInvisible(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        // start siempre cae en el límite de un code point, así que codePointBefore
        // nunca empareja un surrogate del final con uno ya recortado al principio.
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isInvisible(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    /**
     * Indica si un code point es de los que se recortan: separador
     * ({@code Zs}, {@code Zl}, {@code Zp}), control ({@code Cc}) o formato
     * ({@code Cf}). Un surrogate suelto ({@code Cs}) no lo es, igual que en la
     * expresión regular.
     *
     * @param codePoint code point que se comprueba
     * @return {@code true} si es invisible según la definición de la clase
     */
    public static boolean isInvisible(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR,
                    Character.CONTROL, Character.FORMAT -> true;
            default -> false;
        };
    }
}
