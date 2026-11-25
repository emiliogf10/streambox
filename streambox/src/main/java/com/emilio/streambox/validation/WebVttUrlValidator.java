package com.emilio.streambox.validation;

import java.util.Locale;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link WebVttUrl}.
 *
 * <p>
 * La parte {@code https://} es la de {@link HttpsUrlValidator#isHttpsUrl}, no
 * una copia: los subtítulos y el vídeo deben aceptar exactamente las mismas
 * URL. La terminación {@code .vtt} se comprueba sobre el texto, no sobre
 * {@link java.net.URI#getPath()} (el motivo está en {@link WebVttUrl}).
 * </p>
 */
public class WebVttUrlValidator implements ConstraintValidator<WebVttUrl, String> {

    /** Terminación obligatoria, en minúsculas. */
    private static final String VTT_EXTENSION = ".vtt";

    private int maxLength;

    /**
     * Lee la configuración de la anotación.
     *
     * @param annotation anotación del campo validado
     */
    @Override
    public void initialize(WebVttUrl annotation) {
        maxLength = annotation.maxLength();
    }

    /**
     * Comprueba el valor.
     *
     * <p>
     * {@code null}, los textos en blanco y los demasiado largos se dan por
     * válidos <b>aquí</b>, con el mismo criterio que {@link HttpsUrlValidator}:
     * en blanco significa «sin subtítulos» y la longitud la rechaza
     * {@code @Size}, así que el campo recibe un único error.
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
        return isWebVttUrl(value);
    }

    /**
     * Indica si el texto es una URL {@code https://} válida de un archivo
     * {@code .vtt}, sin mirar la longitud.
     *
     * <p>
     * Pública para que {@link SubtitlesRequireVideoValidator} sepa si los
     * subtítulos son válidos (solo entonces exige el vídeo) con el mismo
     * criterio que esta restricción.
     * </p>
     *
     * @param value texto a comprobar (no nulo)
     * @return {@code true} si cumple {@link HttpsUrl} y termina en {@code .vtt}
     *         antes del primer {@code ?} o {@code #}
     */
    public static boolean isWebVttUrl(String value) {
        return HttpsUrlValidator.isHttpsUrl(value) && hasVttExtension(value);
    }

    /**
     * Indica si lo anterior al primer {@code ?} o {@code #} termina en
     * {@code .vtt} sin distinguir mayúsculas.
     *
     * <p>
     * {@link Locale#ROOT} evita que la configuración regional de la JVM cambie
     * el resultado (en turco, {@code "I".toLowerCase()} no es {@code "i"}). Solo
     * se llama con texto ASCII ya comprobado, así que equivale a las
     * «minúsculas ASCII» del frontend ({@code toLowerCase()} de JavaScript).
     * </p>
     */
    private static boolean hasVttExtension(String value) {
        int end = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) {
            end = query;
        }
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return value.substring(0, end).toLowerCase(Locale.ROOT).endsWith(VTT_EXTENSION);
    }
}
