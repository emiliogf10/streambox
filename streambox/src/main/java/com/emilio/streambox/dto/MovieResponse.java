package com.emilio.streambox.dto;

import java.time.Instant;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO con los datos de una película que se devuelven al cliente.
 *
 * <p>
 * Es un {@code record}: inmutable y sin código repetitivo. Se construye
 * dentro de los servicios, mientras la transacción sigue abierta, con
 * {@link com.emilio.streambox.mapper.MovieMapper#toResponse}.
 * </p>
 *
 * <p>
 * {@code backdropUrl}, {@code videoUrl}, {@code trailerUrl} y
 * {@code subtitlesUrl} pueden ser {@code null} (y salen en el JSON como
 * {@code null}, no se omiten): una película puede no tener imagen de fondo,
 * vídeo del contenido, tráiler, subtítulos o ninguno (los subtítulos solo
 * existen si hay vídeo). En OpenAPI 3.1 eso se documenta
 * con el tipo {@code ["string", "null"]}.
 * </p>
 *
 * @param id          identificador de la película
 * @param title       título
 * @param description sinopsis
 * @param duration    duración en minutos
 * @param releaseYear año de estreno
 * @param imageUrl    URL de la portada
 * @param backdropUrl URL de la imagen horizontal de cabecera, o {@code null}
 *                    si no tiene (el banner usa entonces {@code imageUrl})
 * @param videoUrl    URL del vídeo de la película, o {@code null} si no tiene
 * @param trailerUrl  tráiler de YouTube normalizado
 *                    ({@code https://www.youtube.com/watch?v=<id>}), o
 *                    {@code null} si no tiene
 * @param subtitlesUrl URL de los subtítulos WebVTT en español, o {@code null}
 *                    si no tiene (nunca sin {@code videoUrl})
 * @param createdAt   instante en el que se registró la película
 * @param genres      géneros de la película, ordenados por nombre
 */
public record MovieResponse(
        Long id,
        String title,
        String description,
        Integer duration,
        Integer releaseYear,
        String imageUrl,
        @Schema(types = { "string", "null" }, description = MovieResponse.BACKDROP_URL_DESCRIPTION,
                example = "https://image.tmdb.org/t/p/w1280/fondo.jpg")
        String backdropUrl,
        @Schema(types = { "string", "null" },
                description = "URL del vídeo de la película, o null si no tiene vídeo del contenido.")
        String videoUrl,
        @Schema(types = { "string", "null" }, description = MovieResponse.TRAILER_URL_DESCRIPTION,
                example = "https://www.youtube.com/watch?v=aBcDeFgHiJk")
        String trailerUrl,
        @Schema(types = { "string", "null" }, description = MovieResponse.SUBTITLES_URL_DESCRIPTION,
                example = "https://videos.streambox.example/subtitulos/dune.es.vtt")
        String subtitlesUrl,
        Instant createdAt,
        Set<GenreResponse> genres) {

    /**
     * Texto OpenAPI del {@code trailerUrl} de las respuestas, compartido con
     * las de series para que las tres documenten lo mismo.
     */
    public static final String TRAILER_URL_DESCRIPTION = "Tráiler en YouTube, siempre en la forma "
            + "normalizada https://www.youtube.com/watch?v=<id> (id de 11 caracteres de letras, números, "
            + "guion o guion bajo), o null si no tiene.";

    /**
     * Texto OpenAPI del {@code backdropUrl} de las respuestas, compartido con
     * {@link SeriesResponse} y {@link SeriesDetailResponse}.
     */
    public static final String BACKDROP_URL_DESCRIPTION = "Imagen horizontal de cabecera (proporción "
            + "recomendada 16:9) para el banner del inicio y de la página del título: URL https:// o portada "
            + "propia /covers/<archivo>. Es opcional: null si no tiene, y entonces el banner usa el póster "
            + "(imageUrl).";

    /**
     * Texto OpenAPI del {@code subtitlesUrl} de las respuestas, compartido con
     * {@link EpisodeResponse}.
     */
    public static final String SUBTITLES_URL_DESCRIPTION = "URL de los subtítulos en español (archivo "
            + "WebVTT, https://…/archivo.vtt), o null si no tiene. Solo hay subtítulos si hay videoUrl. El "
            + "reproductor los carga como pista de subtítulos; su servidor debe permitir CORS.";
}
