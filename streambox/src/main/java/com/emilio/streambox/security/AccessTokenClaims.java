package com.emilio.streambox.security;

/**
 * Lo que {@link JwtService#parseAccessToken(String)} saca de un JWT de acceso
 * ya validado (firma HS256, emisor, caducidad y tipo del claim {@code ver}).
 *
 * <p>
 * Es un {@code record} y no dos métodos sueltos ({@code extractEmail},
 * {@code extractVersion}) para que el token se valide <b>una sola vez</b> y
 * quien lo use no pueda quedarse con el email olvidando comparar la versión:
 * el filtro recibe las dos cosas juntas.
 * </p>
 *
 * @param email        {@code subject} del token (puede venir vacío en un token
 *                     firmado a mano; el filtro lo trata como no válido)
 * @param tokenVersion claim {@code ver}: versión de los tokens de la cuenta
 *                     cuando se emitió; solo vale si coincide con
 *                     {@code users.token_version}
 */
public record AccessTokenClaims(String email, int tokenVersion) {
}
