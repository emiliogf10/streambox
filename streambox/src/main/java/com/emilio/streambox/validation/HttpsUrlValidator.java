package com.emilio.streambox.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Implementación de {@link HttpsUrl}.
 *
 * <p>
 * La URL {@code https} se analiza con {@link URI} en lugar de con una
 * expresión regular: escribir a mano una regex que reconozca bien todas las
 * URL válidas (puertos, IPv6, parámetros, fragmentos, caracteres codificados)
 * y ninguna inválida es muy difícil, y {@code URI} ya separa el esquema, las
 * credenciales y el host según el estándar. La regex solo se usa para la
 * portada propia, que tiene una forma fija y muy sencilla.
 * </p>
 *
 * <p>
 * No se comprueba si el host es {@code localhost} o una IP privada: el
 * servidor nunca descarga estas URL (lo hace el navegador de quien ve la
 * película), así que no hay riesgo de que la API haga peticiones internas.
 * </p>
 */
public class HttpsUrlValidator implements ConstraintValidator<HttpsUrl, String> {

    /**
     * Prefijo obligatorio, en minúsculas.
     *
     * <p>
     * El estándar dice que el esquema no distingue mayúsculas y los navegadores
     * aceptan {@code HTTPS://}, pero se rechaza en lugar de normalizarlo: un
     * validador no puede cambiar el valor, así que habría que reescribirlo en
     * otra capa y lo guardado ya no sería exactamente lo validado. La forma
     * canónica es en minúsculas, el mensaje de error dice literalmente
     * "https://" y corregirlo es trivial para el administrador.
     * </p>
     */
    private static final String HTTPS_PREFIX = "https://";

    /**
     * Portada propia: {@code /covers/} seguido de un nombre de archivo sin
     * subcarpetas. Que el primer carácter sea alfanumérico y que no se admita
     * {@code /} impide salir de la carpeta ({@code ..}, {@code ../x}) y los
     * archivos ocultos; sin {@code ?}, {@code #} ni {@code %} no hay parámetros,
     * fragmentos ni caracteres codificados que el servidor estático pudiera
     * decodificar de otra forma ({@code %2e%2e} es {@code ..}).
     */
    private static final Pattern LOCAL_COVER = Pattern.compile("/covers/[A-Za-z0-9][A-Za-z0-9._-]*");

    private boolean allowLocalCovers;
    private int maxLength;

    /**
     * Lee la configuración de la anotación.
     *
     * @param annotation anotación del campo validado
     */
    @Override
    public void initialize(HttpsUrl annotation) {
        allowLocalCovers = annotation.allowLocalCovers();
        maxLength = annotation.maxLength();
    }

    /**
     * Comprueba el valor.
     *
     * <p>
     * {@code null}, los textos en blanco y los demasiado largos se dan por
     * válidos <b>aquí</b> porque ya los rechazan {@code @NotBlank} y
     * {@code @Size}; así el campo recibe un único error. "En blanco" se mide
     * con {@link String#isBlank()}, el mismo criterio que usa
     * {@code @NotBlank} en Hibernate Validator 9: si se usara uno más amplio,
     * un texto que {@code @NotBlank} considerase con contenido (por ejemplo, un
     * espacio Unicode raro) no lo rechazaría nadie.
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
        if (!isPrintableAscii(value)) {
            return false;
        }
        if (allowLocalCovers && LOCAL_COVER.matcher(value).matches()) {
            return true;
        }
        return isAbsoluteHttpsUrl(value);
    }

    /**
     * Indica si el texto solo contiene caracteres ASCII visibles (del
     * {@code !} al {@code ~}).
     *
     * <p>
     * Rechaza de una vez espacios (también al principio o al final), saltos de
     * línea, caracteres de control y cualquier carácter no ASCII. Hace falta
     * porque {@link URI} es más permisivo que el estándar: admite caracteres
     * Unicode en la ruta, incluidos invisibles como el espacio de ancho cero o
     * los que invierten la dirección del texto, que pueden disfrazar una URL.
     * </p>
     */
    private static boolean isPrintableAscii(String value) {
        return value.chars().allMatch(c -> c > ' ' && c < 0x7F);
    }

    /**
     * Indica si el texto es una URL {@code https} absoluta con host y sin
     * credenciales.
     *
     * <p>
     * Exigir el prefijo literal descarta de entrada {@code http:},
     * {@code javascript:}, {@code data:}, {@code file:}, {@code ftp:}, las URL
     * relativas al protocolo ({@code //host/x}) y {@code HTTPS://}. Después
     * {@link URI} rechaza lo mal formado ({@code https://} a secas, caracteres
     * prohibidos como {@code \} o {@code "}) y permite comprobar las partes:
     * sin host ({@code https:///ruta}, o un host que no es un nombre válido)
     * {@code getHost()} devuelve {@code null}, y cualquier {@code @} en la
     * autoridad aparece en {@code getRawUserInfo()}.
     * </p>
     */
    private static boolean isAbsoluteHttpsUrl(String value) {
        if (!value.startsWith(HTTPS_PREFIX)) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            return false;
        }
        String host = uri.getHost();
        return "https".equals(uri.getScheme())
                && host != null
                && !host.isEmpty()
                && uri.getRawUserInfo() == null;
    }
}
