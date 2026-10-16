package com.emilio.streambox.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link YouTubeUrl}.
 *
 * <p>
 * No analiza el enlace por su cuenta: delega en {@link YouTubeUrls}, el mismo
 * código que usa el servicio para normalizarlo antes de guardarlo. Así lo que
 * este validador acepta siempre se puede normalizar (ver la nota de
 * {@link YouTubeUrls}).
 * </p>
 */
public class YouTubeUrlValidator implements ConstraintValidator<YouTubeUrl, String> {

    private int maxLength;

    /**
     * Lee la configuración de la anotación.
     *
     * @param annotation anotación del campo validado
     */
    @Override
    public void initialize(YouTubeUrl annotation) {
        maxLength = annotation.maxLength();
    }

    /**
     * Comprueba el valor.
     *
     * <p>
     * {@code null} y los textos en blanco ("sin tráiler") se dan por válidos,
     * igual que los demasiado largos, que rechaza {@code @Size}. "En blanco" se
     * mide con {@link String#isBlank()}, el mismo criterio con el que
     * {@link YouTubeUrls#normalize(String)} los convierte en {@code null}: si
     * fueran criterios distintos, un texto podría pasar la validación como
     * "vacío" y no saber normalizarse.
     * </p>
     *
     * @param value   texto recibido
     * @param context contexto de validación (no se usa: el mensaje es el de la anotación)
     * @return {@code true} si el valor es válido o le corresponde a otra restricción
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            return true;
        }
        return YouTubeUrls.isValid(value);
    }
}
