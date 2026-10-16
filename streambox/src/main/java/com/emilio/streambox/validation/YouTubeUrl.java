package com.emilio.streambox.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation: el texto debe ser un enlace a un vídeo de
 * YouTube en una de las formas aceptadas para el tráiler (ver
 * {@link YouTubeUrls}, que es quien analiza el enlace).
 *
 * <p>
 * <b>Por qué no basta con {@link HttpsUrl}.</b> El vídeo de una película se
 * abre en una pestaña nueva, así que basta con que sea una URL {@code https}
 * segura. El tráiler, en cambio, el frontend lo <em>incrusta</em> en la página
 * con un {@code <iframe>} (y la CSP solo permite incrustar
 * {@code youtube-nocookie.com}): aceptar cualquier URL sería guardar un enlace
 * que nunca se podría reproducir, o abrir la puerta a incrustar otro sitio si
 * algún cliente fuera menos estricto. Por eso se aceptan solo unas pocas formas
 * fijas de enlace de YouTube, y el servicio guarda siempre la forma
 * normalizada ({@link YouTubeUrls#normalize(String)}).
 * </p>
 *
 * <p>
 * <b>Valores que no evalúa</b> (devuelve válido): {@code null} y textos en
 * blanco, porque el tráiler es opcional (en blanco = sin tráiler, se guarda
 * {@code null}), y textos más largos que {@link #maxLength()}, que rechaza
 * {@code @Size} con su propio mensaje (mismo motivo que
 * {@link HttpsUrl#maxLength()}: un único error por campo y siempre el mismo).
 * </p>
 */
@Documented
@Constraint(validatedBy = YouTubeUrlValidator.class)
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface YouTubeUrl {

    /**
     * Mensaje de error. El de los DTO es
     * {@code MovieRequest.TRAILER_URL_FORMAT_MESSAGE} (contrato con el frontend).
     *
     * @return mensaje que recibe el cliente en {@code validationErrors}
     */
    String message() default "El tráiler debe ser un enlace de un vídeo de YouTube";

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
     * Longitud a partir de la cual este validador no opina y deja el error a
     * {@code @Size}, que debe acompañarlo con el mismo máximo (ver
     * {@link HttpsUrl#maxLength()}).
     *
     * @return longitud máxima evaluada; por defecto, sin límite
     */
    int maxLength() default Integer.MAX_VALUE;
}
