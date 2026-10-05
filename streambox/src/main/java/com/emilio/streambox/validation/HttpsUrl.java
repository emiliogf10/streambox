package com.emilio.streambox.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation: el texto debe ser una URL {@code https://}
 * absoluta, con host y sin credenciales y, si se permite, una portada propia
 * del frontend ({@code /covers/<archivo>}).
 *
 * <p>
 * <b>Por qué no basta con {@code @URL} de Hibernate Validator.</b> {@code @URL}
 * solo comprueba que el texto se pueda interpretar como URL, con cualquier
 * esquema: {@code http:}, {@code file:}, {@code ftp:}, {@code jar:}... Estas
 * direcciones se guardan y se devuelven tal cual a todos los clientes de la
 * API, que las usan como {@code src} de una imagen o {@code href} de un
 * enlace. El frontend web ya neutraliza {@code videoUrl} con
 * {@code getSafeVideoUrl}, pero la API no debe depender de que cada cliente se
 * proteja por su cuenta: es mejor no aceptar nunca un esquema peligroso. Exigir
 * {@code https} además encaja con la CSP prevista para el frontend
 * ({@code img-src 'self' data: https:}), que bloquearía una portada en
 * {@code http}, y evita contenido mixto en una web servida por HTTPS.
 * </p>
 *
 * <p>
 * <b>Reglas de una URL válida</b> (ver {@link HttpsUrlValidator}):
 * </p>
 * <ul>
 * <li>Solo caracteres ASCII imprimibles: sin espacios ni caracteres de control.
 * Lo demás debe ir codificado ({@code %20}, {@code %C3%B1}...), que es lo que
 * define el estándar de las URI (RFC 3986) y lo que copia el navegador.</li>
 * <li>Empieza exactamente por {@code https://} (en minúsculas).</li>
 * <li>Tiene host ({@code https://} a secas o {@code https:///ruta} no valen).</li>
 * <li>No lleva credenciales ({@code https://usuario:clave@host} se rechaza:
 * expondría una contraseña a todos los clientes y es un truco habitual de
 * suplantación, porque lo que se lee antes de la {@code @} no es el host).</li>
 * </ul>
 *
 * <p>
 * Con {@link #allowLocalCovers()} también se acepta una ruta
 * {@code /covers/<archivo>} donde el archivo cumple
 * {@code [A-Za-z0-9][A-Za-z0-9._-]*}: son las portadas de ejemplo que sirve el
 * propio frontend ({@code frontend/public/covers}).
 * </p>
 *
 * <p>
 * <b>Valores que no evalúa</b> (devuelve válido para que solo se queje una
 * restricción por campo): {@code null} y textos en blanco (los rechaza
 * {@code @NotBlank}) y textos más largos que {@link #maxLength()} (los rechaza
 * {@code @Size}). El motivo está en {@link #maxLength()}.
 * </p>
 */
@Documented
@Constraint(validatedBy = HttpsUrlValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface HttpsUrl {

    /**
     * Mensaje de error. Conviene indicarlo en cada uso para nombrar el campo
     * ("La URL de la imagen...").
     *
     * @return mensaje que recibe el cliente en {@code validationErrors}
     */
    String message() default "La URL debe empezar por https://";

    /**
     * Grupos de validación (estándar de Bean Validation; no se usan en el proyecto).
     *
     * @return grupos a los que pertenece la restricción
     */
    Class<?>[] groups() default {};

    /**
     * Metadatos adicionales (estándar de Bean Validation; no se usan en el proyecto).
     *
     * @return carga asociada a la restricción
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * Si es {@code true}, además de las URL {@code https://} se aceptan las
     * portadas propias del frontend ({@code /covers/<archivo>}). Solo tiene
     * sentido para imágenes: un vídeo nunca se sirve desde esa carpeta.
     *
     * @return si se permiten las rutas {@code /covers/<archivo>}
     */
    boolean allowLocalCovers() default false;

    /**
     * Longitud a partir de la cual este validador <b>no opina</b> y deja el
     * error a {@code @Size}, que debe acompañarlo con el mismo máximo.
     *
     * <p>
     * Si una URL de 600 caracteres con formato incorrecto la rechazaran a la
     * vez {@code @Size} y esta restricción, el campo tendría dos errores y
     * {@code GlobalExceptionHandler} (que guarda uno por campo) se quedaría
     * con cualquiera de ellos, porque Hibernate Validator no garantiza el
     * orden. Así cada caso da siempre el mismo mensaje: primero la longitud,
     * que es lo primero que el usuario tiene que corregir. Se mantiene
     * {@code @Size} en lugar de comprobar aquí la longitud porque springdoc
     * lo traduce a {@code maxLength} en el OpenAPI.
     * </p>
     *
     * @return longitud máxima evaluada; por defecto, sin límite
     */
    int maxLength() default Integer.MAX_VALUE;
}
