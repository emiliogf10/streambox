package com.emilio.streambox.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link SubtitlesRequireVideo}.
 *
 * <p>
 * Solo opina cuando los subtítulos son válidos por sí mismos. Si están en
 * blanco no hay subtítulos; si son demasiado largos o no son un {@code .vtt}
 * por {@code https://}, ya los rechazan {@code @Size} o {@link WebVttUrl}, y
 * quejarse también aquí daría dos errores para {@code subtitlesUrl}:
 * {@code GlobalExceptionHandler}, que guarda uno por campo, se quedaría con
 * uno cualquiera (Hibernate Validator no garantiza el orden). Mismo criterio
 * que {@link PositionWithinDurationValidator}.
 * </p>
 *
 * <p>
 * «Hay vídeo» = no es nulo ni está en blanco ({@link String#isBlank()}), el
 * mismo criterio con el que el mapper guarda {@code null}: así se cubre
 * exactamente lo que violaría el {@code CHECK} ({@code subtitles_url IS NULL OR
 * video_url IS NOT NULL}). Un vídeo con texto inválido cuenta como «hay»: tiene
 * su propio error en {@code videoUrl} y la petición ya no llegará a guardarse.
 * </p>
 */
public class SubtitlesRequireVideoValidator
        implements ConstraintValidator<SubtitlesRequireVideo, VideoWithSubtitles> {

    private int maxLength;

    /**
     * Lee la configuración de la anotación.
     *
     * @param annotation anotación de la clase validada
     */
    @Override
    public void initialize(SubtitlesRequireVideo annotation) {
        maxLength = annotation.maxLength();
    }

    /**
     * Comprueba que unos subtítulos válidos vayan acompañados de un vídeo.
     *
     * @param request petición ({@code null} se considera válida)
     * @param context contexto de validación, usado para asignar el error al
     *                campo {@code subtitlesUrl}
     * @return {@code false} solo si los subtítulos son válidos y el vídeo es
     *         nulo o está en blanco
     */
    @Override
    public boolean isValid(VideoWithSubtitles request, ConstraintValidatorContext context) {

        if (request == null) {
            return true;
        }
        String subtitles = request.subtitlesUrl();
        if (subtitles == null || subtitles.isBlank() || subtitles.length() > maxLength
                || !WebVttUrlValidator.isWebVttUrl(subtitles)) {
            return true;
        }
        String video = request.videoUrl();
        if (video != null && !video.isBlank()) {
            return true;
        }

        // Un error de clase no tiene campo por defecto: se cuelga de
        // subtitlesUrl para que llegue como validationErrors.subtitlesUrl.
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("subtitlesUrl")
                .addConstraintViolation();
        return false;
    }
}
