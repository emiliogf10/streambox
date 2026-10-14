package com.emilio.streambox.h2;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Funciones de búsqueda para <b>H2</b>, registradas por la migración
 * {@code db/vendor/h2/V5__accent_insensitive_title_search.sql} con
 * {@code CREATE ALIAS}.
 *
 * <p>
 * Replican la "forma" de las funciones que la V5 de PostgreSQL crea sobre las
 * extensiones {@code unaccent} y {@code pg_trgm}, para que la consulta de
 * {@code specification.TitleSearch} sea la misma en los dos motores. No son una
 * reimplementación fiel: lo que depende de PostgreSQL se prueba contra
 * PostgreSQL real ({@code postgres/PostgresTitleSearchIntegrationTest}).
 * </p>
 *
 * <p>
 * Está en {@code src/main} aunque H2 solo sea una dependencia de test: el
 * backend del E2E y el del navegador de prueba arrancan con
 * {@code spring-boot:run -Dspring-boot.run.useTestClasspath=true}, que añade las
 * dependencias de test pero no {@code target/test-classes}; desde los tests, la
 * V5 de H2 no se aplicaba allí y toda búsqueda por título daba 500. No importa
 * nada de H2 (solo {@code java.text}) y en PostgreSQL nadie la llama, así que
 * en el JAR de producción es inerte. Que siga aquí lo comprueba
 * {@code VendorMigrationsLocationTest}.
 * </p>
 */
public final class H2SearchFunctions {

    /** Marcas combinantes (tildes, diéresis, el punto de la İ...) que deja NFKD. */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    private H2SearchFunctions() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Aproximación de {@code unaccent}: descompone con NFKD y elimina las marcas
     * diacríticas, más unas pocas letras que no se descomponen.
     *
     * <p>
     * Se usa NFKD (compatibilidad) y no NFD porque, como {@code unaccent},
     * convierte las formas de ancho completo en ASCII ({@code ％} en
     * {@code %}, {@code ＿} en {@code _}): así los tests con H2 también
     * detectan si los comodines se escapan antes de normalizar, que es el
     * orden incorrecto.
     * </p>
     *
     * @param text texto ({@code null} devuelve {@code null}, como una función STRICT)
     * @return el texto sin acentos
     */
    public static String unaccent(String text) {

        if (text == null) {
            return null;
        }

        String withoutMarks = COMBINING_MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD))
                .replaceAll("");

        return withoutMarks
                .replace("ß", "ss")
                .replace("æ", "ae").replace("Æ", "AE")
                .replace("œ", "oe").replace("Œ", "OE")
                .replace("ø", "o").replace("Ø", "O")
                .replace("ł", "l").replace("Ł", "L")
                .replace("đ", "d").replace("Đ", "D");
    }

    /**
     * Sustituto de {@code word_similarity}: H2 no tiene trigramas, así que
     * devuelve siempre 0 y la búsqueda tolerante a erratas no encuentra nada
     * con H2. Es preferible a imitar el algoritmo de {@code pg_trgm} en Java:
     * una segunda implementación podría dar por buenos en H2 resultados que
     * PostgreSQL no devuelve.
     *
     * @param query  texto buscado
     * @param target título
     * @return {@code 0.0}, o {@code null} si algún argumento es {@code null}
     */
    public static Double wordSimilarity(String query, String target) {

        if (query == null || target == null) {
            return null;
        }
        return 0.0;
    }
}
