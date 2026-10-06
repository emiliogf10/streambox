package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando no se encuentra un episodio dentro de la serie
 * indicada en la ruta (HTTP 404, {@code RESOURCE_NOT_FOUND}).
 *
 * <p>
 * Un episodio que existe pero pertenece a <em>otra</em> serie se trata igual
 * que uno inexistente: en {@code /api/series/1/episodes/7}, el episodio 7 solo
 * "existe" si es de la serie 1.
 * </p>
 */
public class EpisodeNotFoundException extends ResourceNotFoundException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje descriptivo del error
     */
    public EpisodeNotFoundException(String message) {
        super(message);
    }
}
