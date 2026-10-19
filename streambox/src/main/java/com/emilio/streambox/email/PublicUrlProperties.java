package com.emilio.streambox.email;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * URL pública de la web ({@code streambox.public-url}, variable
 * {@code STREAMBOX_PUBLIC_URL}): la dirección con la que los usuarios abren
 * StreamBox en el navegador. Con ella se construyen los enlaces de los
 * correos.
 *
 * <p>
 * <b>Por qué una propiedad y nunca la cabecera {@code Host}.</b> La cabecera
 * {@code Host} (o {@code X-Forwarded-Host}) la escribe el cliente. Si el
 * enlace del correo se construyera con ella, un atacante podría registrar el
 * email de su víctima enviando {@code Host: sitio-del-atacante.com}: la víctima
 * recibiría un correo legítimo de StreamBox con un enlace al dominio del
 * atacante, que se quedaría con el token al abrirlo (<em>host header
 * injection</em>). Con un valor de la configuración, el enlace siempre apunta
 * a la web de verdad.
 * </p>
 *
 * <p>
 * Por defecto {@code http://localhost:5173} (el Vite de desarrollo). En el
 * perfil {@code prod} es obligatoria ({@code application-prod.properties} la
 * lee de {@code STREAMBOX_PUBLIC_URL} sin valor por defecto): un enlace a
 * {@code localhost} en un correo real no le serviría a nadie.
 * </p>
 *
 * @param publicUrl URL absoluta {@code http} o {@code https}, sin usuario,
 *                  consulta ni fragmento; puede llevar una ruta (si la web se
 *                  sirve en una subcarpeta). La barra final se quita
 */
@ConfigurationProperties(prefix = "streambox")
public record PublicUrlProperties(@DefaultValue("http://localhost:5173") String publicUrl) {

    /**
     * Valida la URL y la deja sin la barra final.
     *
     * @throws IllegalArgumentException si no es una URL absoluta válida
     */
    public PublicUrlProperties {
        publicUrl = validate(publicUrl);
    }

    /**
     * Construye un enlace absoluto a una ruta de la web.
     *
     * @param pathAndFragment ruta que empieza por {@code /}, con fragmento si hace
     *                        falta (p. ej. {@code /verificar-email#token=...})
     * @return el enlace completo
     */
    public String link(String pathAndFragment) {
        return publicUrl + pathAndFragment;
    }

    private static String validate(String value) {
        String problem = "streambox.public-url (STREAMBOX_PUBLIC_URL) debe ser la URL absoluta http o https de "
                + "la web, sin usuario, consulta ni fragmento (por ejemplo https://streambox.example.com)";
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(problem);
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(problem, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || !("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(problem);
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
