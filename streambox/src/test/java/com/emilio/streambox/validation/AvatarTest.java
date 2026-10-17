package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

/**
 * Tests unitarios de la lista blanca de avatares ({@link Avatar}) y de su
 * validador ({@link AvatarChoiceValidator}).
 */
class AvatarTest {

    /**
     * Formato que exige {@code ck_users_avatar} (V7). Si una clave nueva no lo
     * cumpliera, la API la aceptaría y la base de datos la rechazaría (500).
     */
    private static final Pattern DATABASE_FORMAT = Pattern.compile("[a-z0-9-]{1,32}");

    private final AvatarChoiceValidator validator = new AvatarChoiceValidator();

    /** La lista es la del contrato con el frontend ({@code lib/avatars.ts}), en el mismo orden. */
    @Test
    void laListaEsLaDelContrato() {
        assertEquals(List.of("rocket", "ghost", "cat", "dog", "star", "flame", "leaf", "moon", "sun", "zap",
                "music", "crown"), Avatar.keys());
    }

    @Test
    void lasClavesSonUnicasYLaListaEsInmutable() {
        assertEquals(Avatar.values().length, Avatar.keys().stream().distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> Avatar.keys().add("otro"));
    }

    @ParameterizedTest
    @EnumSource(Avatar.class)
    void cadaClaveCumpleElFormatoDeLaBaseDeDatosYSeReconoce(Avatar avatar) {
        assertTrue(DATABASE_FORMAT.matcher(avatar.key()).matches(), avatar.key());
        assertTrue(Avatar.isKnownKey(avatar.key()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "Cat", "CAT", " cat", "cat ", "unicornio", "ROCKET", "Kat" })
    void soloSeReconoceLaClaveExacta(String key) {
        assertFalse(Avatar.isKnownKey(key));
    }

    // --- AvatarChoiceValidator ---

    @Test
    void elCampoAusenteYElNullJsonSonValidos() {
        assertTrue(validator.isValid(null, null), "ausente");
        assertTrue(validator.isValid(NullNode.getInstance(), null), "null JSON");
    }

    @Test
    void unaClaveDeLaListaEsValida() {
        assertTrue(validator.isValid(text("crown"), null));
    }

    @Test
    void cualquierOtroValorJsonNoEsValido() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        assertFalse(validator.isValid(text("unicornio"), null), "formato válido fuera de la lista");
        assertFalse(validator.isValid(text("Cat"), null), "mayúsculas");
        assertFalse(validator.isValid(text(""), null), "vacío");
        assertFalse(validator.isValid(nodes.numberNode(5), null), "número");
        assertFalse(validator.isValid(nodes.booleanNode(true), null), "booleano");
        assertFalse(validator.isValid(nodes.objectNode().put("key", "cat"), null), "objeto");
        assertFalse(validator.isValid(nodes.arrayNode().add("cat"), null), "array");
    }

    private static JsonNode text(String value) {
        return JsonNodeFactory.instance.stringNode(value);
    }
}
