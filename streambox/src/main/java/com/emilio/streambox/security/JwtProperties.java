package com.emilio.streambox.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * Propiedades de configuración de los tokens JWT ({@code jwt.*}).
 *
 * <p>
 * Se validan al arrancar la aplicación: si el secreto falta o es demasiado
 * corto, la aplicación no se inicia y el error indica la propiedad
 * responsable. Antes, un secreto débil solo se descubría al intentar firmar
 * el primer token.
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
 * crean a mano en los tests. La duración sigue con Bean Validation porque su
 * valor no es sensible.
 * </p>
 *
 * @param secret          secreto con el que se firman los tokens (HMAC-SHA).
 *                        Se interpreta como texto UTF-8 y debe tener al menos
 *                        32 caracteres (256 bits). Genera uno con
 *                        {@code openssl rand -base64 48}
 * @param expirationHours horas de validez de cada token (24 por defecto)
 */
@Validated
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,

        @DefaultValue("24")
        @Positive(message = "jwt.expiration-hours debe ser mayor que 0")
        long expirationHours) {

    /** Longitud mínima del secreto: 32 caracteres = 256 bits, lo que exige HS256. */
    public static final int MIN_SECRET_LENGTH = 32;

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

    /**
     * Comprueba el secreto sin incluir nunca su valor en el error.
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
     *                                  de {@value #MIN_SECRET_LENGTH} caracteres
     */
    public JwtProperties {
        if (secret == null || secret.isBlank() || secret.startsWith("${")) {
            throw new IllegalArgumentException(MISSING_SECRET_MESSAGE);
        }
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalArgumentException(SHORT_SECRET_MESSAGE);
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
        return "JwtProperties[secret=******, expirationHours=" + expirationHours + "]";
    }
}
