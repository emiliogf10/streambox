package com.emilio.streambox.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;

import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.validation.Avatar;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Schema;

/**
 * Publica en OpenAPI la lista de avatares válidos del campo {@code avatar} de
 * {@code UpdateProfileRequest} ({@code PATCH /api/users/me}).
 *
 * <p>
 * <b>Por qué hace falta.</b> El campo es un {@code JsonNode} (para distinguir
 * «ausente» de {@code null}; ver {@link UpdateProfileRequest}), y springdoc no
 * sabe qué tipo JSON documentar para él. Y la lista de valores no se puede
 * poner en {@code @Schema(allowableValues = ...)} sin <em>copiar</em> las
 * claves: una anotación solo admite constantes escritas a mano, no
 * {@link Avatar#keys()}. Copiarlas permitiría que la documentación y la
 * validación se separasen al añadir un avatar. Este personalizador sustituye
 * el esquema de la propiedad por uno generado desde {@link Avatar}, de modo
 * que la lista sigue estando en un único sitio.
 * </p>
 *
 * <p>
 * <b>Qué publica.</b> En OpenAPI 3.1 (la que genera springdoc):
 * {@code type: [string, null]} y {@code enum} con las claves y {@code null}
 * (en JSON Schema, {@code enum} restringe también el tipo: sin el
 * {@code null} en la lista, un validador estricto rechazaría el
 * {@code "avatar": null} que la API sí admite). En 3.0, {@code nullable: true}.
 * Conserva la descripción y el ejemplo de la anotación del DTO.
 * </p>
 *
 * <p>
 * Igual que los otros personalizadores, se declara como bean en
 * {@link OpenApiConfig} (sin {@code @Component}) para poder probarlo con
 * {@code new}. Es idempotente.
 * </p>
 */
public class AvatarOpenApiCustomizer implements GlobalOpenApiCustomizer {

    /** Nombre del esquema de la petición en {@code components.schemas}. */
    static final String REQUEST_SCHEMA = UpdateProfileRequest.class.getSimpleName();

    /** Nombre de la propiedad documentada. */
    static final String PROPERTY = "avatar";

    /**
     * Sustituye el esquema de {@code UpdateProfileRequest.avatar}.
     *
     * @param openApi documento OpenAPI ya calculado por springdoc
     */
    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void customise(OpenAPI openApi) {

        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Schema request = openApi.getComponents().getSchemas().get(REQUEST_SCHEMA);
        if (request == null || request.getProperties() == null) {
            return;
        }
        Map<String, Schema> properties = request.getProperties();
        Schema previous = properties.get(PROPERTY);
        if (previous == null) {
            return;
        }

        List<Object> values = new ArrayList<>(Avatar.keys());
        values.add(null);

        Schema avatar = new Schema<>();
        if (SpecVersion.V31 == openApi.getSpecVersion()) {
            avatar.specVersion(SpecVersion.V31);
            Set<String> types = new LinkedHashSet<>(List.of("string", "null"));
            avatar.setTypes(types);
        } else {
            avatar.setType("string");
            avatar.setNullable(Boolean.TRUE);
        }
        avatar.setEnum(values);
        avatar.setDescription(previous.getDescription());
        avatar.setExample(previous.getExample());
        properties.put(PROPERTY, avatar);
    }
}
