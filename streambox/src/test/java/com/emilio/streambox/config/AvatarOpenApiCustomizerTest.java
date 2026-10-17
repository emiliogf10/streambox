package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.validation.Avatar;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

/**
 * Tests unitarios de {@link AvatarOpenApiCustomizer}: la lista publicada sale
 * de {@link Avatar} (más {@code null}), se conservan la descripción y el
 * ejemplo, y no falla si el documento no tiene el esquema.
 */
class AvatarOpenApiCustomizerTest {

    private final AvatarOpenApiCustomizer customizer = new AvatarOpenApiCustomizer();

    @Test
    void enOpenApi31PublicaLasClavesYNullConTipoStringONull() {
        OpenAPI openApi = documentWithAvatar(SpecVersion.V31);

        customizer.customise(openApi);
        customizer.customise(openApi); // idempotente

        Schema<?> avatar = avatarSchema(openApi);
        assertEquals(expectedEnum(), avatar.getEnum());
        assertEquals(Set.of("string", "null"), avatar.getTypes());
        assertEquals("descripción", avatar.getDescription());
        assertEquals("rocket", avatar.getExample());
    }

    @Test
    void enOpenApi30UsaNullable() {
        OpenAPI openApi = documentWithAvatar(SpecVersion.V30);

        customizer.customise(openApi);

        Schema<?> avatar = avatarSchema(openApi);
        assertEquals("string", avatar.getType());
        assertTrue(avatar.getNullable());
        assertEquals(expectedEnum(), avatar.getEnum());
    }

    @Test
    void sinElEsquemaNoHaceNada() {
        assertDoesNotThrow(() -> customizer.customise(new OpenAPI()));
        assertDoesNotThrow(() -> customizer.customise(new OpenAPI().components(new Components())));
        OpenAPI withoutAvatar = new OpenAPI().components(new Components()
                .addSchemas(AvatarOpenApiCustomizer.REQUEST_SCHEMA, new ObjectSchema()
                        .addProperty("username", new StringSchema())));
        customizer.customise(withoutAvatar);
        assertTrue(!withoutAvatar.getComponents().getSchemas().get(AvatarOpenApiCustomizer.REQUEST_SCHEMA)
                .getProperties().containsKey(AvatarOpenApiCustomizer.PROPERTY));
    }

    private static OpenAPI documentWithAvatar(SpecVersion version) {
        Schema<Object> previous = new Schema<>();
        previous.setDescription("descripción");
        previous.setExample("rocket");
        OpenAPI openApi = new OpenAPI(version).components(new Components()
                .addSchemas(AvatarOpenApiCustomizer.REQUEST_SCHEMA, new ObjectSchema()
                        .addProperty(AvatarOpenApiCustomizer.PROPERTY, previous)));
        return openApi;
    }

    private static Schema<?> avatarSchema(OpenAPI openApi) {
        return (Schema<?>) openApi.getComponents().getSchemas().get(AvatarOpenApiCustomizer.REQUEST_SCHEMA)
                .getProperties().get(AvatarOpenApiCustomizer.PROPERTY);
    }

    private static List<Object> expectedEnum() {
        List<Object> expected = new ArrayList<>(Avatar.keys());
        expected.add(null);
        return expected;
    }
}
