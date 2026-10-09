package com.emilio.streambox.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;

import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.JwtAuthenticationFilter;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * Documenta en cada operación autenticada las dos formas de enviar el JWT
 * (cookie o Bearer) y, en las no seguras, el 403 {@code CSRF_REJECTED}.
 *
 * <p>
 * <b>Problema que resuelve.</b> Los controladores declaran
 * {@code @SecurityRequirement(name = "bearerAuth")}, así que el OpenAPI decía
 * que la única forma de autenticarse era {@code Authorization: Bearer}, cuando
 * la principal (la del navegador) es la cookie {@code streambox_token}. Y la
 * defensa CSRF de {@code JwtAuthenticationFilter} (403 {@code CSRF_REJECTED}
 * si una petición POST/PUT/PATCH/DELETE autenticada por cookie no trae
 * {@code X-Requested-With: StreamBox}) solo aparecía en refresh y logout: un
 * cliente generado desde el OpenAPI no sabría que su POST a favoritos puede
 * fallar así.
 * </p>
 *
 * <p>
 * <b>Por qué una regla global y no anotaciones en cada controlador.</b> Igual
 * que con {@link ErrorResponseOpenApiCustomizer}: el filtro aplica la misma
 * regla a todos los endpoints, así que documentarla a mano serían decenas de
 * repeticiones y bastaría olvidar una en un endpoint nuevo. Aquí se aplica sola
 * a cualquier endpoint futuro que declare {@code bearerAuth}.
 * </p>
 *
 * <p>
 * <b>Por qué un customizer de operación y no de documento.</b> springdoc
 * ejecuta los customizers de operación mientras construye cada ruta, antes que
 * cualquier {@code OpenApiCustomizer}. Así el 403 que se añade aquí recibe
 * después, sin depender del orden entre beans, el cuerpo {@code ErrorResponse}
 * de {@link ErrorResponseOpenApiCustomizer}. Cuando se llama, springdoc ya ha
 * rellenado la seguridad y las respuestas a partir de las anotaciones.
 * </p>
 *
 * <p>
 * Qué hace en cada operación cuya seguridad incluye {@code bearerAuth}:
 * </p>
 * <ul>
 *   <li>Deja dos requisitos alternativos (en OpenAPI, la lista
 *       {@code security} es un «o»): primero {@code cookieAuth}, la forma
 *       principal, y después {@code bearerAuth}.</li>
 *   <li>Si el método HTTP no es seguro, documenta el 403
 *       {@code CSRF_REJECTED}: lo crea si la operación no tenía 403 o lo añade
 *       a la descripción del que ya hubiera (por ejemplo, el de «sin permisos
 *       de administrador»), porque una respuesta solo puede tener una entrada
 *       por código.</li>
 * </ul>
 * <p>
 * No toca las operaciones públicas (sin seguridad): login y registro no
 * exigen la cabecera, y refresh y logout ya documentan su propio 403 (la
 * exigen siempre, también con Bearer). Es idempotente: aplicarlo dos veces
 * deja el mismo resultado.
 * </p>
 */
public class CookieAuthOperationCustomizer implements GlobalOperationCustomizer {

    /** Código de respuesta del rechazo CSRF. */
    static final String FORBIDDEN = "403";

    /** Texto que identifica que un 403 ya documenta el rechazo CSRF (para no repetirlo). */
    static final String CSRF_CODE = "CSRF_REJECTED";

    /** Métodos que la defensa CSRF deja pasar (los mismos que {@code JwtAuthenticationFilter}). */
    private static final Set<RequestMethod> SAFE_METHODS =
            Set.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE);

    /** Descripción del 403 cuando la operación no tenía ninguno. */
    static final String CSRF_DESCRIPTION = "Código " + CSRF_CODE + ": la petición se autentica por la cookie "
            + AuthCookieService.COOKIE_NAME + " y le falta la cabecera " + JwtAuthenticationFilter.CSRF_HEADER
            + ": " + JwtAuthenticationFilter.CSRF_HEADER_VALUE + " (defensa CSRF). Con Authorization: Bearer "
            + "no se exige";

    /** Frase que se añade a un 403 que ya existía (normalmente el de falta de permisos). */
    static final String CSRF_APPENDIX = "También 403 con código " + CSRF_CODE + " si la petición se autentica "
            + "por la cookie " + AuthCookieService.COOKIE_NAME + " y le falta la cabecera "
            + JwtAuthenticationFilter.CSRF_HEADER + ": " + JwtAuthenticationFilter.CSRF_HEADER_VALUE
            + " (defensa CSRF; con Authorization: Bearer no se exige)";

    /**
     * Añade la cookie como alternativa y, si procede, el 403 {@code CSRF_REJECTED}.
     *
     * @param operation     operación ya construida por springdoc a partir de las anotaciones
     * @param handlerMethod método del controlador que la atiende (de él sale el método HTTP)
     * @return la misma operación, modificada
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {

        if (!requires(operation, OpenApiConfig.BEARER_AUTH)) {
            return operation;
        }

        addCookieAlternative(operation);

        if (isUnsafe(handlerMethod)) {
            documentCsrfRejection(operation);
        }
        return operation;
    }

    /**
     * Indica si alguno de los requisitos de seguridad de la operación usa el
     * esquema indicado.
     */
    private static boolean requires(Operation operation, String scheme) {
        return operation.getSecurity() != null
                && operation.getSecurity().stream().anyMatch(requirement -> requirement.containsKey(scheme));
    }

    /**
     * Deja la cookie como primer requisito alternativo, seguida de los que ya
     * hubiera. Se crea una lista nueva en lugar de modificar la existente
     * porque springdoc podría compartirla entre operaciones del mismo
     * controlador (la anotación está en la clase).
     */
    private static void addCookieAlternative(Operation operation) {

        if (requires(operation, OpenApiConfig.COOKIE_AUTH)) {
            return;
        }
        List<SecurityRequirement> security = new ArrayList<>();
        security.add(new SecurityRequirement().addList(OpenApiConfig.COOKIE_AUTH));
        security.addAll(operation.getSecurity());
        operation.setSecurity(security);
    }

    /**
     * Indica si el método del controlador atiende algún método HTTP no seguro.
     * Un {@code @RequestMapping} sin método explícito atiende a todos, así que
     * también cuenta como no seguro (springdoc genera entonces una operación
     * por método con el mismo {@code HandlerMethod} y todas reciben el 403:
     * mejor que sobre a que falte; hoy ningún controlador lo hace).
     */
    private static boolean isUnsafe(HandlerMethod handlerMethod) {

        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getMethod(), RequestMapping.class);
        if (mapping == null) {
            return false;
        }
        RequestMethod[] methods = mapping.method();
        return methods.length == 0 || Arrays.stream(methods).anyMatch(method -> !SAFE_METHODS.contains(method));
    }

    /**
     * Crea el 403 {@code CSRF_REJECTED} o lo añade a la descripción del 403
     * existente, salvo que ya lo mencione.
     */
    private static void documentCsrfRejection(Operation operation) {

        ApiResponses responses = operation.getResponses();
        if (responses == null) {
            responses = new ApiResponses();
            operation.setResponses(responses);
        }

        ApiResponse forbidden = responses.get(FORBIDDEN);
        if (forbidden == null) {
            operation.setResponses(withResponseInOrder(responses, FORBIDDEN,
                    new ApiResponse().description(CSRF_DESCRIPTION)));
            return;
        }

        String description = forbidden.getDescription();
        if (description == null || description.isBlank()) {
            forbidden.setDescription(CSRF_DESCRIPTION);
        } else if (!description.contains(CSRF_CODE)) {
            forbidden.setDescription(stripFinalPeriod(description) + ". " + CSRF_APPENDIX);
        }
    }

    /**
     * Copia las respuestas insertando la nueva en su sitio por código (antes
     * del primer código mayor), para que el 403 no quede detrás del 404 o el
     * 409 en el documento. Conserva las extensiones de la colección original.
     */
    private static ApiResponses withResponseInOrder(ApiResponses responses, String code, ApiResponse response) {

        ApiResponses ordered = new ApiResponses();
        boolean inserted = false;
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            if (!inserted && entry.getKey().compareTo(code) > 0) {
                ordered.addApiResponse(code, response);
                inserted = true;
            }
            ordered.addApiResponse(entry.getKey(), entry.getValue());
        }
        if (!inserted) {
            ordered.addApiResponse(code, response);
        }
        ordered.setExtensions(responses.getExtensions());
        return ordered;
    }

    /** Quita el punto final, si lo hay, para encadenar otra frase sin dejar «..». */
    private static String stripFinalPeriod(String text) {
        String trimmed = text.strip();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
