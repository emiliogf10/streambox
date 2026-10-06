package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando se intenta crear (o mover, al editar) un episodio
 * en una posición ya ocupada de la serie: misma temporada y mismo número
 * (HTTP 409, {@code EPISODE_ALREADY_EXISTS}).
 *
 * <p>
 * La garantía real es la restricción {@code uk_episodes_series_season_episode}
 * de la base de datos; el servicio lo comprueba antes para dar un mensaje
 * claro y traduce también la violación de esa restricción (carrera entre dos
 * altas simultáneas) a esta misma excepción.
 * </p>
 */
public class EpisodeAlreadyExistsException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje que indica qué posición está ocupada
     */
    public EpisodeAlreadyExistsException(String message) {
        super(message);
    }
}
