package com.emilio.streambox.config;

import java.util.Locale;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Quita de Spring MVC el conversor YAML para que los cuerpos de la API solo se
 * lean en JSON.
 *
 * <p>
 * <b>El problema.</b> Spring Framework registra por su cuenta un conversor por
 * cada formato que encuentra en el classpath. springdoc trae
 * {@code jackson-dataformat-yaml} (lo usa para generar
 * {@code /v3/api-docs.yaml}), así que Spring añadía
 * {@code MappingJackson2YamlHttpMessageConverter} y <b>todos</b> los
 * {@code @RequestBody} (login, registro, películas, series...) se aceptaban
 * también con {@code Content-Type: application/yaml}. Nadie usa ese formato:
 * el frontend y la documentación hablan JSON. Era superficie innecesaria: un
 * segundo analizador (SnakeYAML, con un historial de fallos propio: alias que
 * se multiplican, documentos enormes...) procesando entrada de cualquier
 * cliente, incluidas las rutas públicas de login y registro.
 * </p>
 *
 * <p>
 * <b>Relación con la pista NV-A (auditoría 2).</b> {@code RateLimitingFilter}
 * cuenta en el límite por IP todo {@code Content-Type} que no sea de los que
 * otra web puede enviar sin preflight, precisamente porque el YAML también
 * llegaba al controlador. Con este cambio el YAML recibe 415
 * {@code UNSUPPORTED_MEDIA_TYPE} (vía {@code GlobalExceptionHandler}) y sigue
 * contando en el límite: el filtro no cambia, y la garantía «todo lo que se
 * lee, cuenta» se vuelve más sencilla porque ya solo se lee JSON.
 * </p>
 *
 * <p>
 * <b>Por qué se quita del todo y no solo para lectura.</b> La misma lista de
 * conversores sirve para leer y escribir. El único que necesitaba YAML al
 * escribir era {@code /v3/api-docs.yaml}, y springdoc genera ese documento él
 * mismo y lo devuelve como {@code byte[]} (lo escribe
 * {@code ByteArrayHttpMessageConverter}), así que sigue funcionando. Quitarlo
 * también de la escritura evita además que un {@code Accept: application/yaml}
 * serialice los DTO de la API en un formato que nadie consume: ahora recibe 406
 * {@code NOT_ACCEPTABLE}, explicado en JSON. Que ese 406 (y cualquier otro
 * error) llegue de verdad al cliente lo garantiza {@code GlobalExceptionHandler},
 * que fija {@code Content-Type: application/json} en todos sus errores: si no,
 * el error tampoco se podía escribir en YAML y el cliente recibía un 401 falso
 * desde {@code /error} (ver {@code ErrorResponseAlwaysJsonTomcatIntegrationTest}).
 * </p>
 *
 * <p>
 * Se usa {@link WebMvcConfigurer#configureMessageConverters(HttpMessageConverters.ServerBuilder)},
 * la vía de Spring Framework 7 (la variante con {@code List} está obsoleta), y
 * {@code configureMessageConvertersList}, que se aplica sobre la lista final ya
 * construida: así da igual en qué orden se ejecuten los demás configuradores
 * (el de Spring Boot incluido). El conversor se reconoce por los tipos que
 * anuncia y no por su clase, para cubrir tanto el de Jackson 2 como el de
 * Jackson 3 si algún día llega al classpath.
 * </p>
 */
@Configuration
public class JsonOnlyMessageConvertersConfig implements WebMvcConfigurer {

    /**
     * Quita los conversores YAML de la lista final de Spring MVC, que comparten
     * la lectura de los {@code @RequestBody} y la escritura de las respuestas.
     *
     * @param builder constructor de los conversores del servidor
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(
                converters -> converters.removeIf(JsonOnlyMessageConvertersConfig::isYamlConverter));
    }

    /**
     * Indica si el conversor trabaja con YAML: anuncia algún tipo cuyo subtipo
     * es {@code yaml}, {@code x-yaml} o un estructurado {@code *+yaml}
     * ({@code application/yaml}, {@code text/yaml}, {@code application/x-yaml}...).
     *
     * <p>
     * No se usa {@code isCompatibleWith}: los conversores genéricos
     * ({@code String}, {@code byte[]}, recursos) anuncian {@code *}{@code /*},
     * que es «compatible» con YAML, y se quitarían por error.
     * </p>
     *
     * @param converter conversor registrado por Spring
     * @return {@code true} si hay que quitarlo
     */
    static boolean isYamlConverter(HttpMessageConverter<?> converter) {
        return converter.getSupportedMediaTypes().stream()
                .anyMatch(JsonOnlyMessageConvertersConfig::isYaml);
    }

    private static boolean isYaml(MediaType mediaType) {
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        return subtype.equals("yaml") || subtype.equals("x-yaml") || subtype.endsWith("+yaml");
    }
}
