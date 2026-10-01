package com.emilio.streambox.dto;

/**
 * DTO devuelto tras un inicio de sesión correcto.
 *
 * @param token token JWT que el cliente debe enviar en la cabecera
 *              {@code Authorization: Bearer <token>}
 */
public record LoginResponse(String token) {
}
