package com.emilio.streambox.dto;

/**
 * DTO con los datos de un género cinematográfico que se devuelven al cliente.
 *
 * @param id   identificador del género
 * @param name nombre del género
 */
public record GenreResponse(Long id, String name) {
}
