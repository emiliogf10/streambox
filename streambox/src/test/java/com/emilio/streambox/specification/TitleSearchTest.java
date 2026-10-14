package com.emilio.streambox.specification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regla de longitud mínima de la búsqueda tolerante a erratas
 * ({@link TitleSearch#toleratesTypos(String)}): cuentan las letras y los
 * dígitos, no los espacios ni los signos, porque {@code pg_trgm} solo forma
 * trigramas con ellos. El porqué del mínimo está en {@link TitleSearch}.
 */
class TitleSearchTest {

    @ParameterizedTest
    @ValueSource(strings = { "matrx", "interstelar", "senor anillo", "  ab-cd-e  ", "Pokémon", "12345", "千と千尋の" })
    void conCincoLetrasODigitosOMasSeIntentaLaTolerancia(String text) {
        assertTrue(TitleSearch.toleratesTypos(text), text);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "a", "ma", "amro", "a b c d", "!!!!!!!!", "%%%%%", "____a" })
    void conMenosDeCincoLetrasODigitosNo(String text) {
        assertFalse(TitleSearch.toleratesTypos(text), text);
    }

    @Test
    void unTextoNuloNoSeIntenta() {
        assertFalse(TitleSearch.toleratesTypos(null));
    }

    /**
     * Las marcas combinantes sueltas no son letras: un texto formado solo por
     * acentos (cuya clave, tras {@code unaccent}, es vacía) nunca intenta la
     * búsqueda tolerante. Y una letra escrita descompuesta ("n" + tilde
     * combinante) cuenta una vez, igual que la "ñ".
     */
    @ParameterizedTest
    @ValueSource(strings = { "́́́́́́", " ̃ ̃ ̃ ̃ ̃ ",
            "mãñ" })
    void lasMarcasCombinantesNoCuentanComoLetras(String text) {
        assertFalse(TitleSearch.toleratesTypos(text), text);
    }

    /**
     * Se cuentan las letras del texto descompuesto (NFKD), como queda tras
     * normalizarlo: una ligadura cuenta por sus letras ("ﬁ" son dos), y la "ñ"
     * descompuesta, por una.
     */
    @ParameterizedTest
    @ValueSource(strings = { "ﬁﬁﬁ", "mañana" })
    void seCuentanLasLetrasDelTextoNormalizado(String text) {
        assertTrue(TitleSearch.toleratesTypos(text), text);
    }

    /** Un emoji no es una letra: cinco emojis no bastan (y no se parten los pares sustitutos). */
    @Test
    void losEmojisNoCuentanComoLetras() {
        assertFalse(TitleSearch.toleratesTypos("🎬🎬🎬🎬🎬"));
    }
}
