package com.emilio.streambox.dto;

import java.util.Set;

import com.emilio.streambox.validation.HttpsUrl;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO con los datos de una película, utilizado tanto para crearla
 * ({@code POST /api/movies}) como para modificarla ({@code PUT /api/movies/{id}}).
 *
 * <p>
 * Como {@code PUT} sustituye la película completa, ambas operaciones reciben
 * exactamente los mismos campos y basta un solo DTO.
 * </p>
 *
 * <p>
 * No contiene datos generados por la aplicación, como el identificador o la
 * fecha de creación.
 * </p>
 *
 * <p>
 * <b>URLs.</b> {@code imageUrl} y {@code videoUrl} se validan con
 * {@link HttpsUrl} (solo {@code https://} absoluta, con host y sin
 * credenciales; la imagen admite además una portada propia
 * {@code /covers/<archivo>}) en lugar de con {@code @URL}, que aceptaba
 * cualquier esquema ({@code http:}, {@code file:}, {@code ftp:}...). El
 * {@code @Size} evita además que una URL más larga que su columna
 * {@code VARCHAR(500)} llegue a la base de datos: antes acababa en un
 * {@code DataIntegrityViolationException} y el cliente recibía un 409
 * ("conflicto con datos existentes") que no explicaba el problema. Si una URL
 * es demasiado larga solo se informa de la longitud (ver
 * {@link HttpsUrl#maxLength()}). Las constantes de longitud y los mensajes
 * están aquí para que la anotación, los tests y la documentación usen siempre
 * el mismo texto, que es parte del contrato con el frontend.
 * </p>
 *
 * @param title       título de la película (obligatorio, máximo 150 caracteres)
 * @param description sinopsis (obligatoria, máximo 1000 caracteres)
 * @param duration    duración en minutos (obligatoria, al menos 1)
 * @param releaseYear año de estreno (obligatorio, entre 1888 y 2100)
 * @param imageUrl    URL de la portada (obligatoria, máximo 500 caracteres,
 *                    {@code https://} o {@code /covers/<archivo>})
 * @param videoUrl    URL del vídeo (obligatoria, máximo 500 caracteres, {@code https://})
 * @param genreIds    identificadores de los géneros de la película (entre 1 y
 *                    {@value #MAX_GENRES})
 */
public record MovieRequest(

        @NotBlank(message = MovieRequest.TITLE_REQUIRED_MESSAGE)
        @Size(max = 150, message = "El título no puede superar los 150 caracteres")
        String title,

        @NotBlank(message = MovieRequest.DESCRIPTION_REQUIRED_MESSAGE)
        @Size(max = 1000, message = MovieRequest.DESCRIPTION_SIZE_MESSAGE)
        String description,

        @NotNull(message = MovieRequest.DURATION_REQUIRED_MESSAGE)
        @Min(value = 1, message = MovieRequest.DURATION_MIN_MESSAGE)
        Integer duration,

        @NotNull(message = MovieRequest.RELEASE_YEAR_REQUIRED_MESSAGE)
        @Min(value = 1888, message = MovieRequest.RELEASE_YEAR_RANGE_MESSAGE)
        @Max(value = 2100, message = MovieRequest.RELEASE_YEAR_RANGE_MESSAGE)
        Integer releaseYear,

        @Schema(description = "URL de la portada. Debe ser una URL https:// absoluta, con host y sin "
                + "credenciales (usuario:clave@), o una portada propia del frontend con la forma "
                + "/covers/<archivo>, donde el archivo empieza por letra o número y solo contiene "
                + "letras, números, punto, guion y guion bajo. Solo caracteres ASCII visibles "
                + "(sin espacios); máximo 500 caracteres.",
                example = "https://image.tmdb.org/t/p/w500/portada.jpg")
        @NotBlank(message = MovieRequest.IMAGE_URL_REQUIRED_MESSAGE)
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.IMAGE_URL_SIZE_MESSAGE)
        @HttpsUrl(allowLocalCovers = true, maxLength = MovieRequest.URL_MAX_LENGTH,
                message = MovieRequest.IMAGE_URL_FORMAT_MESSAGE)
        String imageUrl,

        @Schema(description = "URL del vídeo. Debe ser una URL https:// absoluta, con host y sin "
                + "credenciales (usuario:clave@). Solo caracteres ASCII visibles (sin espacios); "
                + "máximo 500 caracteres.",
                example = "https://videos.streambox.example/watch/dune")
        @NotBlank(message = MovieRequest.VIDEO_URL_REQUIRED_MESSAGE)
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_SIZE_MESSAGE)
        @HttpsUrl(maxLength = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_FORMAT_MESSAGE)
        String videoUrl,

        @NotEmpty(message = MovieRequest.GENRES_REQUIRED_MESSAGE)
        @Size(max = MovieRequest.MAX_GENRES, message = MovieRequest.GENRES_SIZE_MESSAGE)
        Set<@NotNull(message = MovieRequest.GENRE_ID_REQUIRED_MESSAGE) Long> genreIds) {

    /*
     * Mensajes de "obligatorio" y de rango: son literalmente los que muestra el
     * formulario del panel de administración (frontend/src/lib/movieValidation.ts),
     * para que el administrador lea lo mismo lo detecte el cliente o el
     * servidor. Antes estas restricciones no tenían mensaje y salía el de
     * Hibernate Validator, que depende del idioma de la JVM ("must not be
     * blank" en un servidor en inglés).
     */

    /** Error cuando falta {@code title}. */
    public static final String TITLE_REQUIRED_MESSAGE = "El título es obligatorio";

    /** Error cuando falta {@code description} (en la interfaz se llama «sinopsis»). */
    public static final String DESCRIPTION_REQUIRED_MESSAGE = "La sinopsis es obligatoria";

    /**
     * Error de {@code description} de más de 1000 caracteres. Dice «sinopsis»,
     * como el formulario de películas; antes decía «descripción» y el mismo
     * error se leía distinto según lo detectara el cliente o el servidor (las
     * series y los episodios sí dicen «descripción» en los dos lados).
     */
    public static final String DESCRIPTION_SIZE_MESSAGE = "La sinopsis no puede superar los 1000 caracteres";

    /** Error cuando falta {@code duration}. */
    public static final String DURATION_REQUIRED_MESSAGE = "Indica la duración en minutos";

    /** Error de {@code duration} menor que 1. */
    public static final String DURATION_MIN_MESSAGE = "La duración debe ser un número entero de minutos mayor que 0";

    /** Error cuando falta {@code releaseYear}. */
    public static final String RELEASE_YEAR_REQUIRED_MESSAGE = "Indica el año de estreno";

    /** Error de rango de {@code releaseYear}. */
    public static final String RELEASE_YEAR_RANGE_MESSAGE = "El año debe estar entre 1888 y 2100";

    /** Error cuando falta {@code imageUrl}. */
    public static final String IMAGE_URL_REQUIRED_MESSAGE = "La URL de la portada es obligatoria";

    /** Error cuando falta {@code videoUrl}. */
    public static final String VIDEO_URL_REQUIRED_MESSAGE = "La URL del vídeo es obligatoria";

    /** Error cuando {@code genreIds} falta o está vacía. */
    public static final String GENRES_REQUIRED_MESSAGE = "Elige al menos un género";

    /**
     * Error de un elemento nulo en {@code genreIds} ({@code [1, null]}),
     * compartido con {@link SeriesRequest}. Sin esta regla el nulo llegaba al
     * servicio y a {@code findAllById}, que no admite ids nulos.
     */
    public static final String GENRE_ID_REQUIRED_MESSAGE = "Los identificadores de género no pueden ser nulos";

    /**
     * Máximo de géneros por título (películas y series).
     *
     * <p>
     * Sin tope, un administrador (o un token de administrador robado) podía
     * mandar miles de ids: el servicio los busca con un único
     * {@code WHERE id IN (...)}, que crece con la lista (PostgreSQL rechaza
     * además más de 32767 parámetros en una consulta, con un 500), y la
     * petición entera se procesaba antes de fallar. 20 queda muy por encima de
     * cualquier título real y del número de géneros que maneja el catálogo.
     * </p>
     */
    public static final int MAX_GENRES = 20;

    /** Error de cardinalidad de {@code genreIds} (contrato con el frontend). */
    public static final String GENRES_SIZE_MESSAGE =
            "Una película puede tener como máximo " + MAX_GENRES + " géneros";

    /**
     * Longitud máxima de {@code imageUrl} y {@code videoUrl}. Coincide con las
     * columnas {@code movies.image_url} y {@code movies.video_url}
     * ({@code VARCHAR(500)}).
     */
    public static final int URL_MAX_LENGTH = 500;

    /** Error de formato de {@code imageUrl} (contrato con el frontend). */
    public static final String IMAGE_URL_FORMAT_MESSAGE =
            "La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)";

    /** Error de longitud de {@code imageUrl} (contrato con el frontend). */
    public static final String IMAGE_URL_SIZE_MESSAGE =
            "La URL de la imagen no puede superar los " + URL_MAX_LENGTH + " caracteres";

    /** Error de formato de {@code videoUrl} (contrato con el frontend). */
    public static final String VIDEO_URL_FORMAT_MESSAGE = "La URL del vídeo debe empezar por https://";

    /** Error de longitud de {@code videoUrl} (contrato con el frontend). */
    public static final String VIDEO_URL_SIZE_MESSAGE =
            "La URL del vídeo no puede superar los " + URL_MAX_LENGTH + " caracteres";
}
