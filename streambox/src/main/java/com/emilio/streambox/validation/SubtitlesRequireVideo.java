package com.emilio.streambox.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Restricción de Bean Validation a nivel de clase para las peticiones con
 * vídeo y subtítulos ({@link VideoWithSubtitles}): no puede haber subtítulos
 * sin vídeo.
 *
 * <p>
 * <b>Por qué.</b> Unos subtítulos sin nada que reproducir no se verían nunca, y
 * la base de datos lo prohíbe con {@code ck_movies_subtitles_require_video} y
 * {@code ck_episodes_subtitles_require_video} (migración {@code V15}). Sin esta
 * comprobación, la petición pasaría la validación, el {@code INSERT} o el
 * {@code UPDATE} violaría el {@code CHECK} y el cliente recibiría un 500 sin
 * explicación (ver {@code GlobalExceptionHandler#handleDataIntegrityViolation}).
 * Con ella recibe un 400 {@code VALIDATION_ERROR} que dice qué corregir.
 * </p>
 *
 * <p>
 * <b>Dónde va el error.</b> En {@code validationErrors.subtitlesUrl}, no en
 * {@code videoUrl}: el vídeo es opcional y está bien vacío; lo que sobra es el
 * campo que el administrador acaba de rellenar. Es una restricción de clase
 * porque compara dos campos, y como {@link PositionWithinDuration} cuelga su
 * error del campo con {@code addPropertyNode}.
 * </p>
 *
 * <p>
 * <b>Cuándo opina</b> (un solo error por campo, ver
 * {@link SubtitlesRequireVideoValidator}): solo si los subtítulos son válidos
 * por sí mismos (no superan {@link #maxLength()} y cumplen {@link WebVttUrl}) y
 * el vídeo es nulo o está en blanco. Un vídeo con texto pero inválido ya tiene
 * su propio error en {@code videoUrl}, y repetirlo aquí no ayudaría.
 * </p>
 */
@Documented
@Constraint(validatedBy = SubtitlesRequireVideoValidator.class)
@Target({ ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface SubtitlesRequireVideo {

    /**
     * Mensaje de error que recibe el cliente en {@code validationErrors.subtitlesUrl}.
     *
     * @return mensaje de error
     */
    String message() default "Los subtítulos necesitan un vídeo: indica también la URL del vídeo";

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
     * Longitud máxima de los subtítulos: la misma que su {@code @Size}. Por
     * encima, el campo ya tiene el error de longitud y esta restricción calla.
     *
     * @return longitud máxima de {@code subtitlesUrl}; por defecto, sin límite
     */
    int maxLength() default Integer.MAX_VALUE;
}
