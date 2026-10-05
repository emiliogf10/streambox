package com.emilio.streambox.config;

import java.math.BigDecimal;
import java.util.Objects;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.http.HttpHeaders;

import com.emilio.streambox.dto.ErrorResponse;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.core.converter.ResolvedSchema;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Hace que todas las respuestas de error (4xx y 5xx) del OpenAPI documenten
 * el cuerpo {@link ErrorResponse}.
 *
 * <p>
 * <b>Problema que resuelve.</b> springdoc rellena cada {@code @ApiResponse}
 * que no declara {@code content} con el tipo de retorno del método. Como los
 * controladores devuelven el DTO correcto (por ejemplo {@code LoginResponse}),
 * el 401 y el 429 del login acababan documentados como si devolvieran un
 * token, el 404 de películas como una {@code MovieResponse}, etc. En los
 * métodos {@code void} pasaba lo contrario: sus errores quedaban sin cuerpo.
 * Un cliente generado desde el OpenAPI leería un error como un token.
 * </p>
 *
 * <p>
 * <b>Por qué un customizer y no {@code content = @Content(...)} en cada
 * {@code @ApiResponse}.</b> En la API todos los errores salen con el mismo
 * formato ({@code GlobalExceptionHandler} y {@code SecurityErrorResponseWriter}
 * lo garantizan), así que es una regla global, no una decisión de cada
 * endpoint. Anotarlo a mano serían más de 60 repeticiones y bastaría olvidarlo
 * en un endpoint nuevo para volver al bug. Aquí la regla vive en un solo
 * sitio y se aplica sola a cualquier endpoint futuro. Es un
 * {@link GlobalOpenApiCustomizer} (y no un {@code OpenApiCustomizer} simple)
 * para que se aplique también si algún día se definen grupos de
 * documentación ({@code GroupedOpenApi}).
 * </p>
 *
 * <p>
 * Qué hace, para cada respuesta cuyo código empieza por 4 o 5 (también los
 * rangos {@code 4XX}/{@code 5XX}):
 * </p>
 * <ul>
 *   <li>Sustituye su contenido por {@code application/json} con
 *       {@code $ref} a {@code ErrorResponse}. Se sustituye siempre, aunque el
 *       endpoint hubiera declarado otro: es justo el invariante de la API. Si
 *       algún día un error necesitara otro cuerpo, habría que hacer aquí la
 *       excepción.</li>
 *   <li>En los 429 declara la cabecera {@code Retry-After} (segundos, entero
 *       de al menos 1), que envían tanto {@code RateLimitingFilter} como el
 *       bloqueo de cuenta.</li>
 * </ul>
 * <p>
 * No toca las 2xx (siguen con el tipo de retorno del método) ni las
 * respuestas sin cuerpo como el 204. Mantiene la descripción de cada
 * {@code @ApiResponse}, que es lo que explica cada caso concreto.
 * </p>
 *
 * <p>
 * El esquema {@code ErrorResponse} se genera con el mismo motor que usa
 * springdoc ({@link ModelConverters} de swagger-core, en la versión de la
 * especificación del documento, 3.0 o 3.1), así que lee sus anotaciones
 * {@code @Schema} igual que haría con un DTO devuelto por un controlador. El
 * método es idempotente: aplicarlo dos veces deja el mismo resultado.
 * </p>
 */
public class ErrorResponseOpenApiCustomizer implements GlobalOpenApiCustomizer {

    /** Las respuestas de error se escriben siempre como JSON. */
    private static final String JSON = org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

    /**
     * Documenta {@link ErrorResponse} en todas las respuestas de error.
     *
     * @param openApi documento OpenAPI ya calculado por springdoc
     */
    @Override
    public void customise(OpenAPI openApi) {

        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            return;
        }

        String errorSchemaRef = registerErrorResponseSchema(openApi);

        openApi.getPaths().values().stream()
                .flatMap(pathItem -> pathItem.readOperations().stream())
                .map(Operation::getResponses)
                .filter(Objects::nonNull)
                .forEach(responses -> responses.forEach(
                        (code, response) -> documentIfError(code, response, errorSchemaRef)));
    }

    /**
     * Genera el esquema de {@link ErrorResponse} (y los que referencie) y lo
     * añade a {@code components.schemas} si no estaba.
     *
     * <p>
     * Se usa el convertidor de la misma versión de la especificación que el
     * documento: springdoc genera OpenAPI 3.1 y un esquema creado para 3.0 se
     * serializaría mal (por ejemplo, sin {@code type}).
     * </p>
     *
     * @param openApi documento en el que se registra el esquema
     * @return referencia ({@code #/components/schemas/...}) al esquema
     */
    private static String registerErrorResponseSchema(OpenAPI openApi) {

        boolean openapi31 = SpecVersion.V31 == openApi.getSpecVersion();
        ResolvedSchema resolved = ModelConverters.getInstance(openapi31)
                .resolveAsResolvedSchema(new AnnotatedType(ErrorResponse.class).resolveAsRef(true));

        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        resolved.referencedSchemas.forEach((name, schema) -> {
            // Si springdoc ya tenía ese esquema se respeta el suyo.
            if (components.getSchemas() == null || !components.getSchemas().containsKey(name)) {
                components.addSchemas(name, schema);
            }
        });
        return resolved.schema.get$ref();
    }

    /**
     * Si la respuesta es de error, le pone el cuerpo {@code ErrorResponse} y,
     * si es un 429, la cabecera {@code Retry-After}.
     *
     * @param code           código de la respuesta ({@code "404"}, {@code "4XX"}, {@code "default"}...)
     * @param response       respuesta documentada
     * @param errorSchemaRef referencia al esquema {@code ErrorResponse}
     */
    private static void documentIfError(String code, ApiResponse response, String errorSchemaRef) {

        // Una respuesta que es un $ref a components.responses no admite contenido propio.
        if (!isError(code) || response.get$ref() != null) {
            return;
        }

        response.setContent(new Content().addMediaType(JSON,
                new MediaType().schema(new Schema<>().$ref(errorSchemaRef))));

        if ("429".equals(code)) {
            response.addHeaderObject(HttpHeaders.RETRY_AFTER, retryAfterHeader());
        }
    }

    /**
     * Indica si un código de respuesta OpenAPI es de error. Cubre los códigos
     * concretos y los rangos {@code 4XX}/{@code 5XX}; {@code default} no se
     * considera error porque puede describir cualquier respuesta.
     */
    private static boolean isError(String code) {
        return code != null && (code.startsWith("4") || code.startsWith("5"));
    }

    /**
     * Cabecera {@code Retry-After} de los 429. Se crea nueva en cada respuesta
     * para no compartir objetos mutables entre operaciones.
     */
    private static Header retryAfterHeader() {
        return new Header()
                .description("Segundos que hay que esperar antes de volver a intentarlo (entero, mínimo 1).")
                .required(true)
                .schema(new IntegerSchema().minimum(BigDecimal.ONE));
    }
}
