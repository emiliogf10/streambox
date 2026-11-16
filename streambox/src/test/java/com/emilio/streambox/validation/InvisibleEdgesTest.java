package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Random;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Tests unitarios de {@link InvisibleEdges}.
 *
 * <p>
 * Lo esencial es que el recorte lineal sea <b>idéntico</b> al de la expresión
 * regular a la que sustituye ({@link #OLD_EDGES}), para que el arreglo del
 * ReDoS no cambie qué nombres de usuario, géneros o contraseñas se consideran
 * iguales: se compara carácter a carácter en todo Unicode y con textos
 * aleatorios.
 * </p>
 */
class InvisibleEdgesTest {

    /** La expresión que se usaba antes (cuadrática), como referencia de la semántica. */
    private static final Pattern OLD_EDGES =
            Pattern.compile("^[\\p{Z}\\p{Cc}\\p{Cf}]+|[\\p{Z}\\p{Cc}\\p{Cf}]+$");

    /** La clase de caracteres de esa expresión, para compararla code point a code point. */
    private static final Pattern OLD_CLASS = Pattern.compile("[\\p{Z}\\p{Cc}\\p{Cf}]");

    @Test
    void quitaSoloLosInvisiblesDeLosExtremos() {
        assertEquals("ana", InvisibleEdges.strip("  ana  "));
        assertEquals("ana", InvisibleEdges.strip("\t\nana\r\n"));
        assertEquals("ana", InvisibleEdges.strip(" ​ana　﻿"));
        assertEquals("ana", InvisibleEdges.strip(" ana "));
        assertEquals("ana  lópez", InvisibleEdges.strip("ana  lópez"));
        assertEquals("a​b", InvisibleEdges.strip("a​b"));
        assertEquals("", InvisibleEdges.strip("   "));
        assertEquals("", InvisibleEdges.strip(""));
    }

    /**
     * Los suplementarios se tratan como un solo carácter: una etiqueta de
     * idioma ({@code U+E0001}, formato) se quita entera; un emoji no se toca;
     * un surrogate suelto no es invisible (como en la expresión regular).
     */
    @Test
    void recorrePorCodePoints() {
        String tag = Character.toString(0xE0001);
        String emoji = Character.toString(0x1F3AC);
        String loneHigh = "\uD83C";

        assertEquals("ana", InvisibleEdges.strip(tag + "ana" + tag));
        assertEquals(emoji, InvisibleEdges.strip(" " + emoji + " "));
        assertEquals(loneHigh, InvisibleEdges.strip(" " + loneHigh + " "));
    }

    /** Misma clase de caracteres que la expresión regular, en todos los code points de Unicode. */
    @Test
    void laClaseCoincideConLaDeLaExpresionRegularEnTodoUnicode() {
        for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            boolean expected = OLD_CLASS.matcher(Character.toString(codePoint)).matches();
            if (InvisibleEdges.isInvisible(codePoint) != expected) {
                assertEquals(expected, InvisibleEdges.isInvisible(codePoint),
                        "U+" + Integer.toHexString(codePoint).toUpperCase());
            }
        }
    }

    /**
     * Mismo resultado que la expresión regular en textos aleatorios hechos de
     * visibles, invisibles de cada tipo, suplementarios y surrogates sueltos
     * (semilla fija: si falla, se reproduce).
     */
    @Test
    void elRecorteCoincideConElDeLaExpresionRegular() {
        String[] alphabet = {
                "a", "Ñ", " ", "\t", "\n", "\r", "\u0085", " ", "​", " ", " ",
                "　", "﻿", Character.toString(0xE0001), Character.toString(0x1F3AC),
                "\uD83C", "\uDFAC"
        };
        Random random = new Random(20261112L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            int length = random.nextInt(12);
            for (int j = 0; j < length; j++) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String value = text.toString();
            assertEquals(OLD_EDGES.matcher(value).replaceAll(""), InvisibleEdges.strip(value),
                    () -> "Texto: " + value.codePoints().mapToObj(Integer::toHexString).toList());
        }
    }

    /**
     * El motivo del cambio: con la expresión regular, un millón de espacios
     * interiores eran del orden de horas (O(n²)); el recorrido lineal tarda
     * milisegundos. El margen de 2 s es holgado para no ser inestable en el CI.
     */
    @Test
    void esLinealConUnaRachaEnormeDeInvisiblesInteriores() {
        String huge = "a" + " ".repeat(1_000_000) + "a";

        String stripped = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> InvisibleEdges.strip(huge));

        assertEquals(huge, stripped);
    }
}
