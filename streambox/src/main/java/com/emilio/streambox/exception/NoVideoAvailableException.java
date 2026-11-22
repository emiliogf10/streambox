package com.emilio.streambox.exception;

/**
 * Excepción lanzada al guardar el progreso de visionado de una película o un
 * episodio que existe pero <b>no tiene vídeo</b> ({@code videoUrl} nulo; es
 * opcional desde la {@code V6}). HTTP 409 {@code NO_VIDEO_AVAILABLE}.
 *
 * <p>
 * <b>Por qué un 409 y no un 404 ni un 400.</b> El título existe (un 404
 * mentiría) y el cuerpo es correcto (no es un fallo de validación): lo que
 * impide la operación es el estado actual del recurso, que es lo que expresa
 * un 409. Sin vídeo no hay nada que reproducir en la aplicación, así que
 * guardar un progreso no tiene sentido; y si el administrador quitó el vídeo
 * mientras el usuario lo veía, el reproductor sabe por qué dejan de aceptarse
 * sus guardados.
 * </p>
 *
 * <p>
 * No hereda de {@link ResourceNotFoundException}: no es un 404.
 * </p>
 */
public class NoVideoAvailableException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje en español que explica qué título no tiene vídeo
     */
    public NoVideoAvailableException(String message) {
        super(message);
    }
}
