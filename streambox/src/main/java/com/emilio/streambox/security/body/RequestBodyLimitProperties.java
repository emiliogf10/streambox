package com.emilio.streambox.security.body;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Tamaño máximo del cuerpo de las peticiones ({@code streambox.http.*}).
 *
 * <p>
 * Lo aplica {@link RequestBodySizeLimitFilter} a todas las peticiones. Se
 * valida en el constructor, como {@code JwtProperties}: un valor absurdo (cero,
 * negativo o enorme) impide arrancar con un mensaje claro en vez de dejar la
 * API sin límite o rechazando todo.
 * </p>
 *
 * @param maxRequestBody tamaño máximo del cuerpo ({@code streambox.http.max-request-body},
 *                       variable {@code STREAMBOX_HTTP_MAX_REQUEST_BODY}; formato
 *                       de Spring Boot: {@code 64KB}, {@code 65536B}...). Por
 *                       defecto {@value #DEFAULT_TEXT}. Debe ser mayor que 0 y
 *                       como mucho {@value #MAX_TEXT}. Si se cambia, hay que
 *                       cambiar también {@code client_max_body_size} en
 *                       {@code frontend/nginx/default.conf}
 */
@ConfigurationProperties(prefix = "streambox.http")
public record RequestBodyLimitProperties(DataSize maxRequestBody) {

    /** Valor por defecto, en el formato de la propiedad. */
    static final String DEFAULT_TEXT = "64KB";

    /** Tope de la propiedad, en texto para los mensajes. */
    static final String MAX_TEXT = "10MB";

    /** Valor por defecto: 64 KB (65 536 bytes). */
    public static final DataSize DEFAULT = DataSize.ofKilobytes(64);

    /**
     * Tope de la configuración. La API solo recibe JSON pequeño (el cuerpo
     * legítimo más grande ronda los 16 KB en el peor caso); 10 MB ya sería un
     * error de configuración (p. ej. {@code 64MB} en vez de {@code 64KB}) que
     * devolvería a la API el problema que este límite resuelve. Una subida de
     * archivos futura no debe subir este valor: tendrá su propia ruta y su
     * propio límite (ver el capítulo 13 del manual).
     */
    static final DataSize MAX = DataSize.ofMegabytes(10);

    /**
     * Valida el tamaño (y pone el de por defecto si no se configura).
     *
     * @throws IllegalArgumentException si no es mayor que 0 o supera {@value #MAX_TEXT}
     */
    public RequestBodyLimitProperties {
        if (maxRequestBody == null) {
            maxRequestBody = DEFAULT;
        }
        if (maxRequestBody.toBytes() <= 0) {
            throw new IllegalArgumentException(
                    "streambox.http.max-request-body debe ser mayor que 0 (por defecto " + DEFAULT_TEXT + ")");
        }
        if (maxRequestBody.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(
                    "streambox.http.max-request-body no puede superar " + MAX_TEXT + " (por defecto " + DEFAULT_TEXT
                            + "): la API solo recibe JSON pequeño");
        }
    }
}
