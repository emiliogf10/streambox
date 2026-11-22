package com.emilio.streambox.dto;

/**
 * Tipo de un elemento de «Seguir viendo» ({@link ContinueWatchingItemResponse#type()}):
 * decide qué campos trae y adónde lleva su tarjeta.
 */
public enum ContinueWatchingType {

    /** Una película: {@code id} es el de la película; sin datos de serie. */
    MOVIE,

    /** Un episodio: {@code id} es el del episodio; con temporada, número y serie. */
    EPISODE
}
