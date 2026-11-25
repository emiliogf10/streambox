package com.emilio.streambox.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation para la URL de unos subtítulos: debe ser una
 * URL {@code https://} válida según {@link HttpsUrl} <b>y</b> apuntar a un
 * archivo WebVTT ({@code .vtt}).
 *
 * <p>
 * <b>Qué cuenta como «archivo {@code .vtt}».</b> El <b>texto completo</b>
 * anterior al primer {@code ?} o {@code #}, pasado a minúsculas ASCII, debe
 * terminar en {@code .vtt}. Así valen {@code https://cdn.example.com/es.vtt},
 * {@code .../es.VTT}, {@code .../es.vtt?token=abc} y {@code .../es.vtt#t=10},
 * y no valen {@code .../es.srt}, {@code .../es.vtt.html} ni
 * {@code https://cdn.example.com/descargar?f=es.vtt} (la terminación está en la
 * consulta, no en el archivo).
 * </p>
 *
 * <p>
 * <b>Por qué no {@code URI.getPath()}.</b> El formulario del panel
 * ({@code frontend/src/lib/movieValidation.ts}, {@code hasVttPath}) corta el
 * texto tal cual, sin interpretar la URL, para no depender de cómo normaliza
 * cada navegador. Si el servidor usara la ruta de {@link java.net.URI}, los dos
 * lados discreparían en casos como {@code https://cdn.example.vtt} (sin ruta:
 * el cliente lo acepta y {@code getPath()} daría la cadena vacía) o una ruta
 * con caracteres codificados ({@code getPath()} decodifica {@code %2E}): el
 * administrador vería un formulario válido y un 400 del servidor. La regla
 * importa por coherencia, no por seguridad: el navegador decide qué es un
 * WebVTT por su contenido, no por la extensión, y lo peligroso (otros
 * esquemas, credenciales) ya lo cierra {@link HttpsUrl}.
 * </p>
 *
 * <p>
 * <b>Un solo mensaje.</b> Las dos condiciones (URL {@code https://} y
 * {@code .vtt}) dan el mismo error, para que el campo nunca tenga dos
 * (por eso no se combina con un {@code @HttpsUrl} aparte). Como
 * {@link HttpsUrl}, no evalúa {@code null}, los textos en blanco (significan
 * «sin subtítulos») ni los de más de {@link #maxLength()} caracteres, que deja
 * a {@code @Size}.
 * </p>
 */
@Documented
@Constraint(validatedBy = WebVttUrlValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface WebVttUrl {

    /**
     * Mensaje de error.
     *
     * @return mensaje que recibe el cliente en {@code validationErrors}
     */
    String message() default "Los subtítulos deben ser un archivo .vtt (WebVTT) con una URL que empiece por https://";

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
     * Longitud a partir de la cual esta restricción no opina y deja el error a
     * {@code @Size}, que debe acompañarla con el mismo máximo (mismo motivo que
     * {@link HttpsUrl#maxLength()}: un único error por campo, y primero la
     * longitud).
     *
     * @return longitud máxima evaluada; por defecto, sin límite
     */
    int maxLength() default Integer.MAX_VALUE;
}
