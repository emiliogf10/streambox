package com.emilio.streambox.validation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reconoce y normaliza los enlaces de YouTube que la API acepta como tráiler.
 *
 * <p>
 * <b>Por qué una clase aparte del validador.</b> El tráiler se valida con
 * {@link YouTubeUrl} y después se guarda <em>normalizado</em> como
 * {@code https://www.youtube.com/watch?v=<id>}, que es la única forma que
 * permite la base de datos ({@code ck_movies_trailer_url} y
 * {@code ck_series_trailer_url}). Si la validación y la normalización tuvieran
 * cada una su propio análisis del enlace, podrían discrepar: un enlace que el
 * validador diera por bueno y la normalización no supiera convertir acabaría
 * en una violación del {@code CHECK} y en un 500. Aquí hay un único análisis
 * ({@link #videoId(String)}) que usan los dos, así que todo lo que la API
 * acepta se puede normalizar, y todo lo normalizado cumple el {@code CHECK}.
 * </p>
 *
 * <p>
 * <b>Formas aceptadas</b> (contrato con el frontend, que replica exactamente
 * estas reglas en {@code frontend/src/lib/youtube.ts}):
 * </p>
 * <ul>
 * <li>{@code https://www.youtube.com/watch?v=<id>}, también con {@code m.} o
 * sin subdominio; el resto de parámetros ({@code &t=30s}, {@code &list=...})
 * se ignora, pero tiene que haber exactamente un {@code v}.</li>
 * <li>{@code https://youtu.be/<id>} (con parámetros opcionales, p. ej.
 * {@code ?si=...}).</li>
 * <li>{@code https://www.youtube.com/embed/<id>} y
 * {@code https://www.youtube-nocookie.com/embed/<id>} (con parámetros
 * opcionales).</li>
 * </ul>
 * <p>
 * Donde {@code <id>} son exactamente 11 caracteres de {@code [A-Za-z0-9_-]}.
 * Todo lo demás se rechaza: {@code http:}, {@code HTTPS:}, otros hosts
 * (también los parecidos, como {@code youtube.com.evil.com} o
 * {@code evilyoutube.com}) o el host en mayúsculas, credenciales
 * ({@code usuario@}), puertos (incluso {@code :443}), fragmentos ({@code #}),
 * espacios, caracteres de control o no ASCII, y rutas con algo más
 * ({@code /watch/}, {@code /embed/<id>/x}).
 * </p>
 *
 * <p>
 * <b>Por qué {@link URI} y no una expresión regular sobre la URL entera.</b>
 * {@code URI} separa el esquema, la autoridad (credenciales, host y puerto),
 * la ruta, los parámetros y el fragmento según el estándar (RFC 3986), y así
 * se puede comparar el host <em>exacto</em>: es la forma más fiable de no caer
 * en trucos como {@code https://www.youtube.com@evil.com/...}, donde lo que
 * parece el host es en realidad el usuario. Las regex solo se usan para el id,
 * que tiene una forma fija.
 * </p>
 *
 * <p>
 * <b>Por qué el host se compara con mayúsculas y minúsculas.</b> Los hosts no
 * distinguen mayúsculas y un navegador aceptaría {@code WWW.YouTube.com}, pero
 * el frontend valida con la misma comparación exacta y las dos validaciones
 * deben coincidir (si no, el formulario daría por bueno lo que el servidor
 * rechaza, o al revés). Es el mismo criterio que {@link HttpsUrl} con el
 * esquema: lo que se copia de YouTube ya viene en minúsculas.
 * </p>
 */
public final class YouTubeUrls {

    /** Id de un vídeo de YouTube: exactamente 11 caracteres de letras, dígitos, {@code _} o {@code -}. */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** Prefijo de la forma normalizada (la única que guarda la base de datos). */
    private static final String NORMALIZED_PREFIX = "https://www.youtube.com/watch?v=";

    /** Prefijo obligatorio, en minúsculas (mismo criterio que {@link HttpsUrl}). */
    private static final String HTTPS_PREFIX = "https://";

    /** Hosts de la forma {@code /watch?v=<id>}. */
    private static final Set<String> WATCH_HOSTS = Set.of("www.youtube.com", "youtube.com", "m.youtube.com");

    /** Hosts de la forma {@code /embed/<id>}. */
    private static final Set<String> EMBED_HOSTS = Set.of("www.youtube.com", "www.youtube-nocookie.com");

    /** Host de los enlaces cortos {@code https://youtu.be/<id>}. */
    private static final String SHORT_HOST = "youtu.be";

    private YouTubeUrls() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Extrae el id del vídeo si el texto es una de las formas aceptadas.
     *
     * @param value enlace recibido (puede ser {@code null})
     * @return el id de 11 caracteres, o vacío si el texto no es un enlace de
     *         YouTube aceptado (o es {@code null})
     */
    public static Optional<String> videoId(String value) {
        if (value == null || !isPrintableAscii(value) || !value.startsWith(HTTPS_PREFIX)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
        String host = uri.getHost();
        /*
         * La autoridad en bruto debe ser exactamente el host: así se descartan
         * a la vez las credenciales (usuario@host), cualquier puerto (también
         * el vacío, "host:", para el que getPort() devuelve -1) y los hosts
         * que URI no sabe interpretar (getHost() null). Sin fragmento: ni
         * siquiera uno vacío ("#"), igual que en el frontend.
         */
        if (host == null || !host.equals(uri.getRawAuthority()) || uri.getRawFragment() != null) {
            return Optional.empty();
        }
        String path = uri.getRawPath();
        String id = null;
        if (WATCH_HOSTS.contains(host) && "/watch".equals(path)) {
            id = idFromWatchQuery(uri.getRawQuery());
        } else if (SHORT_HOST.equals(host) && path.startsWith("/")) {
            id = path.substring(1);
        } else if (EMBED_HOSTS.contains(host) && path.startsWith("/embed/")) {
            id = path.substring("/embed/".length());
        }
        return id != null && VIDEO_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /**
     * Indica si el texto es un enlace de YouTube aceptado como tráiler.
     *
     * @param value enlace recibido
     * @return {@code true} si {@link #videoId(String)} reconoce un id
     */
    public static boolean isValid(String value) {
        return videoId(value).isPresent();
    }

    /**
     * Devuelve la forma que se guarda en la base de datos:
     * {@code https://www.youtube.com/watch?v=<id>}.
     *
     * <p>
     * {@code null} y los textos en blanco significan "sin tráiler" y devuelven
     * {@code null}: así un formulario que manda {@code ""} no necesita un caso
     * especial y "sin tráiler" tiene una sola representación.
     * </p>
     *
     * @param value enlace ya validado con {@link YouTubeUrl}, o {@code null}/en blanco
     * @return la forma normalizada, o {@code null} si no hay tráiler
     * @throws IllegalArgumentException si el texto no es un enlace aceptado.
     *         Nunca debería ocurrir porque el DTO se valida antes; si pasa es un
     *         error de programación (alguien llamó al servicio sin validar) y es
     *         mejor fallar aquí con un mensaje claro que con el {@code CHECK} de
     *         la base de datos.
     */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return videoId(value)
                .map(id -> NORMALIZED_PREFIX + id)
                .orElseThrow(() -> new IllegalArgumentException("El tráiler no es un enlace de YouTube válido"));
    }

    /**
     * Saca el id del parámetro {@code v} de un enlace {@code watch}.
     *
     * <p>
     * Tiene que haber <b>exactamente un</b> {@code v}: con dos
     * ({@code ?v=AAA&v=BBB}) no está claro qué vídeo se quiso enlazar (cada
     * programa se queda con uno distinto) y se rechaza. Se trabaja con los
     * parámetros en bruto, sin decodificar: un id con {@code %} no es válido.
     * </p>
     *
     * @param rawQuery parte {@code ?...} sin decodificar, o {@code null}
     * @return el valor del único {@code v}, o {@code null} si no hay o hay varios
     */
    private static String idFromWatchQuery(String rawQuery) {
        if (rawQuery == null) {
            return null;
        }
        String id = null;
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.startsWith("v=")) {
                if (id != null) {
                    return null;
                }
                id = pair.substring(2);
            }
        }
        return id;
    }

    /**
     * Indica si el texto solo contiene caracteres ASCII visibles (del {@code !}
     * al {@code ~}), igual que {@link HttpsUrlValidator}: descarta espacios
     * (también al principio o al final), saltos de línea, caracteres de control
     * y caracteres invisibles que pueden disfrazar un enlace, que {@link URI}
     * admitiría en la ruta.
     */
    private static boolean isPrintableAscii(String value) {
        return value.chars().allMatch(c -> c > ' ' && c < 0x7F);
    }
}
