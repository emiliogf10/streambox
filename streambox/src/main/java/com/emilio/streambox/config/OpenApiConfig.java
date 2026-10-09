package com.emilio.streambox.config;

import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.JwtAuthenticationFilter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuración de la documentación OpenAPI de Streambox.
 *
 * <p>
 * Define la información general de la API y los dos esquemas de
 * autenticación con los que se puede enviar el JWT de acceso:
 * </p>
 * <ul>
 *   <li>{@value #COOKIE_AUTH}: la cookie HttpOnly {@code streambox_token}, la
 *       forma principal (la usa el navegador). Las peticiones no seguras
 *       autenticadas así exigen {@code X-Requested-With: StreamBox}.</li>
 *   <li>{@value #BEARER_AUTH}: {@code Authorization: Bearer}, para clientes de
 *       API y para probar los endpoints desde Swagger UI.</li>
 * </ul>
 * <p>
 * Los controladores solo declaran {@code @SecurityRequirement(name = "bearerAuth")};
 * {@link CookieAuthOperationCustomizer} añade la cookie como alternativa y el
 * 403 {@code CSRF_REJECTED}. {@link ErrorResponseOpenApiCustomizer} hace que
 * todas las respuestas 4xx/5xx documenten el cuerpo {@code ErrorResponse} en
 * lugar del tipo de la respuesta correcta.
 * </p>
 */
@Configuration
public class OpenApiConfig {

    /** Nombre del esquema de seguridad {@code Authorization: Bearer}. */
    public static final String BEARER_AUTH = "bearerAuth";

    /** Nombre del esquema de seguridad de la cookie de sesión {@code streambox_token}. */
    public static final String COOKIE_AUTH = "cookieAuth";

    /**
     * Configura la especificación OpenAPI de la aplicación.
     *
     * @return configuración de OpenAPI
     */
    @Bean
    public OpenAPI customOpenAPI() {

        return new OpenAPI()
                .info(new Info()
                        .title("Streambox API")
                        .version("1.0")
                        .description(
                                "API REST para la gestión de películas, "
                                        + "géneros y usuarios de Streambox."))
                .components(new Components()
                        .addSecuritySchemes(COOKIE_AUTH, cookieAuthScheme())
                        .addSecuritySchemes(BEARER_AUTH, bearerAuthScheme()));
    }

    /**
     * Esquema de la cookie de sesión. Es un {@code apiKey} en cookie porque
     * OpenAPI no tiene un tipo específico para sesiones por cookie.
     *
     * <p>
     * La descripción avisa de la cabecera CSRF, que es lo que más sorprende a
     * quien llega desde Bearer, y de que Swagger UI no puede fijar la cookie
     * (el navegador no deja a JavaScript escribir una cookie HttpOnly), así que
     * para probar desde allí hay que usar {@value #BEARER_AUTH}.
     * </p>
     */
    private static SecurityScheme cookieAuthScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.COOKIE)
                .name(AuthCookieService.COOKIE_NAME)
                .description("Autenticación principal (navegador): el JWT de acceso viaja en la cookie HttpOnly "
                        + AuthCookieService.COOKIE_NAME + " que fija POST /api/auth/login (Path=/api, 15 minutos; "
                        + "se renueva con POST /api/auth/refresh). Las peticiones no seguras (POST, PUT, PATCH, "
                        + "DELETE) autenticadas por esta cookie deben llevar además la cabecera "
                        + JwtAuthenticationFilter.CSRF_HEADER + ": " + JwtAuthenticationFilter.CSRF_HEADER_VALUE
                        + " (defensa CSRF); si falta, 403 CSRF_REJECTED. Si la petición trae Authorization: "
                        + "Bearer, manda el Bearer y la cookie no se mira. Swagger UI no puede fijar esta cookie: "
                        + "para probar desde aquí usa bearerAuth.");
    }

    /** Esquema {@code Authorization: Bearer} para clientes de API (y Swagger UI). */
    private static SecurityScheme bearerAuthScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Alternativa para clientes de API sin navegador: el mismo JWT de acceso (el valor "
                        + "de la cookie " + AuthCookieService.COOKIE_NAME + ") en la cabecera Authorization: "
                        + "Bearer. Con Bearer no se exige " + JwtAuthenticationFilter.CSRF_HEADER
                        + " (no hay riesgo de CSRF).");
    }

    /**
     * Documenta {@code ErrorResponse} en todas las respuestas de error.
     *
     * <p>
     * Se declara como bean aquí, junto al resto de la configuración de
     * OpenAPI, y la clase no lleva {@code @Component}, para poder probarla
     * con un simple {@code new} en los tests unitarios.
     * </p>
     *
     * @return customizer que springdoc aplica al generar {@code /v3/api-docs}
     */
    @Bean
    public ErrorResponseOpenApiCustomizer errorResponseOpenApiCustomizer() {
        return new ErrorResponseOpenApiCustomizer();
    }

    /**
     * Añade la cookie como alternativa al Bearer y documenta el 403
     * {@code CSRF_REJECTED} en las operaciones autenticadas no seguras.
     *
     * <p>
     * Igual que {@link #errorResponseOpenApiCustomizer()}, se declara aquí y la
     * clase no lleva {@code @Component} para poder probarla con {@code new}.
     * </p>
     *
     * @return customizer que springdoc aplica a cada operación al generar {@code /v3/api-docs}
     */
    @Bean
    public CookieAuthOperationCustomizer cookieAuthOperationCustomizer() {
        return new CookieAuthOperationCustomizer();
    }
}
