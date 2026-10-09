package com.emilio.streambox.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Propiedades de configuración de los tokens JWT ({@code jwt.*}).
 *
 * <p>
 * Se validan al arrancar la aplicación: si el secreto falta o es demasiado
 * corto, o la vida del token no es razonable, la aplicación no se inicia y el
 * error indica la propiedad responsable. Antes, un secreto débil solo se
 * descubría al intentar firmar el primer token.
 * </p>
 *
 * <h2>Por qué el secreto no se valida con Bean Validation</h2>
 * <p>
 * Con {@code @NotBlank}/{@code @Size} sobre {@code secret}, un secreto de
 * menos de 32 caracteres hacía que Spring Boot imprimiese su valor en el log
 * de arranque: el error de validación guarda el valor rechazado
 * ({@code rejected value [...]}) y el informe «APPLICATION FAILED TO START»
 * lo muestra como {@code Value: "..."}. Ese valor no llega a usarse, pero
 * puede ser un secreto real cortado o mal copiado, y los logs acaban en
 * sistemas de agregación a los que accede más gente que al propio secreto.
 * </p>
 * <p>
 * Por eso el secreto se comprueba en el constructor compacto del
 * {@code record}: Spring Boot lo invoca al enlazar las propiedades (antes de
 * crear cualquier bean que las use y, por tanto, antes de servir
 * peticiones), y el mensaje de la excepción lo escribimos nosotros, sin el
 * valor. Además, la regla pasa a ser un invariante del tipo: ningún
 * {@code JwtProperties} puede existir con un secreto débil, tampoco los que se
 * crean a mano en los tests. La vida del token se comprueba en el mismo sitio
 * por coherencia (y porque Bean Validation no tiene restricciones estándar
 * para {@link Duration}).
 * </p>
 *
 * <h2>Por qué el token de acceso dura minutos</h2>
 * <p>
 * Un JWT es <em>stateless</em>: el servidor no puede revocarlo, así que quien
 * copie uno puede usarlo hasta su {@code exp}. Con 15 minutos esa ventana es
 * pequeña; la sesión larga la mantiene el refresh token
 * ({@code streambox_refresh}), que sí se guarda en la base de datos y se puede
 * revocar. Por eso hay un máximo de {@value #MAX_ACCESS_TOKEN_TTL_TEXT}: un TTL
 * de horas anularía la ventaja de poder cerrar sesión de verdad.
 * </p>
 *
 * @param secret         secreto con el que se firman los tokens (HMAC-SHA256).
 *                       Se interpreta como texto UTF-8 y debe tener al menos
 *                       32 caracteres (256 bits). Genera uno con
 *                       {@code openssl rand -base64 48}
 * @param accessTokenTtl vida de cada token de acceso ({@code jwt.access-token-ttl},
 *                       variable {@code JWT_ACCESS_TOKEN_TTL}; formato de Spring
 *                       Boot: {@code 15m}, {@code 900s}, {@code PT15M}...). Por
 *                       defecto 15 minutos; debe ser mayor que 0 y como mucho
 *                       {@value #MAX_ACCESS_TOKEN_TTL_TEXT}. La cookie
 *                       {@code streambox_token} lleva el mismo {@code Max-Age}
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,

        @DefaultValue("15m")
        Duration accessTokenTtl) {

    /** Longitud mínima del secreto: 32 caracteres = 256 bits, lo que exige HS256. */
    public static final int MIN_SECRET_LENGTH = 32;

    /** Vida máxima admitida para el token de acceso. */
    public static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofHours(1);

    /** {@link #MAX_ACCESS_TOKEN_TTL} como texto, para los mensajes y el Javadoc. */
    static final String MAX_ACCESS_TOKEN_TTL_TEXT = "1 hora";

    /** Mensaje cuando el secreto falta o solo tiene espacios. */
    static final String MISSING_SECRET_MESSAGE =
            "jwt.secret es obligatorio (variable de entorno JWT_SECRET); "
                    + "genera uno con: openssl rand -base64 48";

    /**
     * Mensaje cuando el secreto es demasiado corto. No incluye el valor ni su
     * longitud: la longitud acota la búsqueda de un atacante que lea el log y
     * no aporta nada para arreglarlo (la solución es la misma: generar uno
     * nuevo).
     */
    static final String SHORT_SECRET_MESSAGE =
            "jwt.secret (variable de entorno JWT_SECRET) debe tener al menos "
                    + MIN_SECRET_LENGTH + " caracteres (256 bits); "
                    + "genera uno con: openssl rand -base64 48";

    /** Mensaje cuando la vida del token de acceso no es válida. */
    static final String INVALID_TTL_MESSAGE =
            "jwt.access-token-ttl (variable de entorno JWT_ACCESS_TOKEN_TTL) debe ser mayor que 0 "
                    + "y como mucho " + MAX_ACCESS_TOKEN_TTL_TEXT + " (por ejemplo 15m)";

    /**
     * Comprueba el secreto sin incluir nunca su valor en el error, y la vida
     * del token.
     *
     * <p>
     * Un marcador sin resolver ({@code ${...}}) cuenta como secreto ausente.
     * {@code application.properties} define {@code jwt.secret=${JWT_SECRET}} y,
     * si la variable no existe, Spring Boot deja el texto literal
     * {@code ${JWT_SECRET}} en lugar de fallar: el error diría «demasiado
     * corto» cuando el problema real es que falta. Y si el marcador fuese más
     * largo ({@code ${JWT_SECRET_PRODUCCION_...}}) se aceptaría como clave un
     * texto que cualquiera puede leer en el repositorio.
     * </p>
     *
     * @throws IllegalArgumentException si el secreto falta, está en blanco, es
     *                                  un marcador sin resolver o tiene menos
     *                                  de {@value #MIN_SECRET_LENGTH} caracteres,
     *                                  o si la vida del token es nula, cero,
     *                                  negativa o mayor que
     *                                  {@link #MAX_ACCESS_TOKEN_TTL}
     */
    public JwtProperties {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalArgumentException(MISSING_SECRET_MESSAGE);
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(SHORT_SECRET_MESSAGE);
        }
        if (accessTokenTtl == null || accessTokenTtl.isZero() || accessTokenTtl.isNegative()
                || accessTokenTtl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0) {
            throw new IllegalArgumentException(INVALID_TTL_MESSAGE);
        }
    }

    /**
     * Representación sin el secreto.
     *
     * <p>
     * El {@code toString()} que Java genera para los {@code record} incluye
     * todos los campos; si alguien registrase este objeto en un log (o
     * apareciese en el mensaje de una excepción), el secreto quedaría a la
     * vista. Se sustituye por un marcador fijo.
     * </p>
     *
     * @return texto con la duración y el secreto enmascarado
     */
    @Override
    public String toString() {
        return "JwtProperties[secret=******, accessTokenTtl=" + accessTokenTtl + "]";
    }
}
