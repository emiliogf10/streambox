package com.emilio.streambox.config;

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
 * Define la información general de la API y configura el esquema
 * de autenticación mediante tokens JWT para que los endpoints
 * protegidos puedan probarse directamente desde Swagger UI.
 * </p>
 *
 * <p>
 * También registra {@link ErrorResponseOpenApiCustomizer}, que hace que todas
 * las respuestas 4xx/5xx documenten el cuerpo {@code ErrorResponse} en lugar
 * del tipo de la respuesta correcta.
 * </p>
 */
@Configuration
public class OpenApiConfig {

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
                        .addSecuritySchemes(
                                "bearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
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
}