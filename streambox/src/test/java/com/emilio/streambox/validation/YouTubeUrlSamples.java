package com.emilio.streambox.validation;

import java.util.List;
import java.util.Map;

/**
 * Catálogo de enlaces de tráiler aceptados y rechazados, compartido por los
 * tests unitarios ({@code YouTubeUrlsTest}), los de la API
 * ({@code TrailerUrlIntegrationTest}) y los de PostgreSQL real
 * ({@code PostgresTrailerUrlApiIntegrationTest}).
 *
 * <p>
 * Tenerlo en un solo sitio garantiza que se prueba lo mismo en todas las
 * capas: en particular, que <b>cada</b> forma que la API acepta se guarda sin
 * violar el {@code CHECK} de la base de datos en los dos motores.
 * </p>
 */
public final class YouTubeUrlSamples {

    /** Id de ejemplo (11 caracteres, con mayúsculas, dígito, guion y guion bajo). */
    public static final String ID = "aB3_-eFgHiJ";

    /** Forma normalizada de {@link #ID}: la única que se guarda y se devuelve. */
    public static final String NORMALIZED = "https://www.youtube.com/watch?v=" + ID;

    /**
     * Enlaces aceptados y el id que se extrae de cada uno. Todos se normalizan
     * a {@code https://www.youtube.com/watch?v=<id>}.
     */
    public static final Map<String, String> ACCEPTED = Map.ofEntries(
            // watch: con www., m. o sin subdominio
            Map.entry(NORMALIZED, ID),
            Map.entry("https://youtube.com/watch?v=" + ID, ID),
            Map.entry("https://m.youtube.com/watch?v=" + ID, ID),
            // watch: otros parámetros se ignoran, estén antes o después de v
            Map.entry("https://www.youtube.com/watch?v=" + ID + "&t=30s", ID),
            Map.entry("https://www.youtube.com/watch?feature=share&v=" + ID, ID),
            Map.entry("https://www.youtube.com/watch?list=PL123&v=" + ID + "&index=2", ID),
            // un parámetro que solo empieza por v no es v
            Map.entry("https://www.youtube.com/watch?vv=x&v=" + ID, ID),
            // enlaces cortos, con o sin parámetros
            Map.entry("https://youtu.be/" + ID, ID),
            Map.entry("https://youtu.be/" + ID + "?si=AbCdEf123", ID),
            Map.entry("https://youtu.be/" + ID + "?t=42", ID),
            // reproductor incrustado
            Map.entry("https://www.youtube.com/embed/" + ID, ID),
            Map.entry("https://www.youtube.com/embed/" + ID + "?start=10", ID),
            Map.entry("https://www.youtube-nocookie.com/embed/" + ID, ID),
            Map.entry("https://www.youtube-nocookie.com/embed/" + ID + "?autoplay=1&rel=0", ID),
            // ids con todo el juego de caracteres
            Map.entry("https://youtu.be/___________", "___________"),
            Map.entry("https://youtu.be/-----------", "-----------"),
            Map.entry("https://youtu.be/09AZaz_-09A", "09AZaz_-09A"));

    /** Enlaces rechazados (ninguno debe pasar la validación ni normalizarse). */
    public static final List<String> REJECTED = List.of(
            // esquema
            "http://www.youtube.com/watch?v=" + ID,
            "HTTPS://www.youtube.com/watch?v=" + ID,
            "Https://youtu.be/" + ID,
            "//www.youtube.com/watch?v=" + ID,
            "www.youtube.com/watch?v=" + ID,
            "javascript:alert(1)",
            "javascript://www.youtube.com/%0Aalert(1)",
            "data:text/html,<script>alert(1)</script>",
            // otros hosts y parecidos
            "https://vimeo.com/123456789",
            "https://youtube.com.evil.com/watch?v=" + ID,
            "https://www.youtube.com.evil.com/watch?v=" + ID,
            "https://evilyoutube.com/watch?v=" + ID,
            "https://evil.com/www.youtube.com/watch?v=" + ID,
            "https://evil.com/?u=https://www.youtube.com/watch?v=" + ID,
            "https://music.youtube.com/watch?v=" + ID,
            "https://www.youtu.be/" + ID,
            "https://youtube-nocookie.com/embed/" + ID,
            "https://m.youtube.com/embed/" + ID,
            "https://youtube.com/embed/" + ID,
            "https://www.youtube.com./watch?v=" + ID,
            "https://WWW.YOUTUBE.COM/watch?v=" + ID,
            "https://YouTu.be/" + ID,
            // credenciales y puertos
            "https://user@www.youtube.com/watch?v=" + ID,
            "https://user:pass@youtu.be/" + ID,
            "https://www.youtube.com@evil.com/watch?v=" + ID,
            "https://www.youtube.com:443/watch?v=" + ID,
            "https://youtu.be:8080/" + ID,
            "https://youtu.be:/" + ID,
            // fragmentos
            "https://www.youtube.com/watch?v=" + ID + "#t=30",
            "https://youtu.be/" + ID + "#",
            "https://www.youtube.com/watch#v=" + ID,
            // espacios, control, no ASCII
            " https://youtu.be/" + ID,
            "https://youtu.be/" + ID + " ",
            "https://youtu.be/" + ID + "\n",
            "https://youtu.be/\u0000" + ID,
            "https://youtu.be/aB3_-eFgHi​",
            "https://youtu.be/aB3_-eFgHié",
            "https://www.youtube.com/watch?v=" + ID + "\t",
            // ids de 10 y 12 caracteres, o con caracteres fuera del juego
            "https://youtu.be/aB3_-eFgHi",
            "https://youtu.be/aB3_-eFgHiJK",
            "https://www.youtube.com/watch?v=aB3_-eFgHi",
            "https://www.youtube.com/watch?v=aB3_-eFgHiJK",
            "https://www.youtube.com/embed/aB3_-eFgHi",
            "https://youtu.be/aB3_-eFgHi.",
            "https://youtu.be/aB3_-eFgHi%4A",
            "https://www.youtube.com/watch?v=aB3_-eFg%48iJ",
            // rutas distintas de las aceptadas
            "https://www.youtube.com/",
            "https://www.youtube.com",
            "https://www.youtube.com/watch",
            "https://www.youtube.com/watch?",
            "https://www.youtube.com/watch?v=",
            "https://www.youtube.com/watch/?v=" + ID,
            "https://www.youtube.com/Watch?v=" + ID,
            "https://www.youtube.com?v=" + ID,
            "https://www.youtube.com/watch?V=" + ID,
            "https://www.youtube.com/shorts/" + ID,
            "https://www.youtube.com/v/" + ID,
            "https://www.youtube.com/embed/" + ID + "/",
            "https://www.youtube.com/embed/" + ID + "/x",
            "https://www.youtube.com/embed/../watch?v=" + ID,
            "https://youtu.be/" + ID + "/",
            "https://youtu.be/",
            "https://youtu.be/watch?v=" + ID,
            "https://youtu.be//" + ID,
            // dos v: no se sabe qué vídeo es
            "https://www.youtube.com/watch?v=" + ID + "&v=zzzzzzzzzzz",
            // URL mal formadas
            "https://",
            "https:///watch?v=" + ID,
            "https://youtu.be/" + ID + "?a=<b>",
            "https://[::1]/watch?v=" + ID);

    private YouTubeUrlSamples() {
        // Clase de utilidad: no se instancia
    }
}
