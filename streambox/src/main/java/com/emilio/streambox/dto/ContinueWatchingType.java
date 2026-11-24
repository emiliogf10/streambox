package com.emilio.streambox.dto;

/**
 * Tipo de un elemento de «Seguir viendo» ({@link ContinueWatchingItemResponse#type()})
 * y de una entrada del historial de visionado ({@link WatchHistoryItemResponse#type()}):
 * decide qué campos trae y adónde lleva su tarjeta.
 *
 * <p>
 * El historial lo reutiliza en vez de tener su propio enumerado porque los
 * valores significan exactamente lo mismo; renombrarlo (p. ej. a
 * {@code WatchItemType}) cambiaría el nombre del esquema publicado en OpenAPI
 * sin aportar nada al cliente.
 * </p>
 */
public enum ContinueWatchingType {

    /** Una película: {@code id} es el de la película; sin datos de serie. */
    MOVIE,

    /** Un episodio: {@code id} es el del episodio; con temporada, número y serie. */
    EPISODE
}
