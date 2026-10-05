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
 * @param genreIds    identificadores de los géneros de la película (al menos uno)
 */
public record MovieRequest(

        @NotBlank
        @Size(max = 150, message = "El título no puede superar los 150 caracteres")
        String title,

        @NotBlank
        @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
        String description,

        @NotNull
        @Min(1)
        Integer duration,

        @NotNull
        @Min(1888)
        @Max(2100)
        Integer releaseYear,

        @Schema(description = "URL de la portada. Debe ser una URL https:// absoluta, con host y sin "
                + "credenciales (usuario:clave@), o una portada propia del frontend con la forma "
                + "/covers/<archivo>, donde el archivo empieza por letra o número y solo contiene "
                + "letras, números, punto, guion y guion bajo. Solo caracteres ASCII visibles "
                + "(sin espacios); máximo 500 caracteres.",
                example = "https://image.tmdb.org/t/p/w500/portada.jpg")
        @NotBlank
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.IMAGE_URL_SIZE_MESSAGE)
        @HttpsUrl(allowLocalCovers = true, maxLength = MovieRequest.URL_MAX_LENGTH,
                message = MovieRequest.IMAGE_URL_FORMAT_MESSAGE)
        String imageUrl,

        @Schema(description = "URL del vídeo. Debe ser una URL https:// absoluta, con host y sin "
                + "credenciales (usuario:clave@). Solo caracteres ASCII visibles (sin espacios); "
                + "máximo 500 caracteres.",
                example = "https://videos.streambox.example/watch/dune")
        @NotBlank
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_SIZE_MESSAGE)
        @HttpsUrl(maxLength = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_FORMAT_MESSAGE)
        String videoUrl,

        @NotEmpty
        Set<Long> genreIds) {

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
