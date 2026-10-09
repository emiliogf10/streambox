package com.emilio.streambox.security.refresh;

/**
 * Tokens que el controlador de autenticación debe entregar en cookies tras un
 * login o un refresh.
 *
 * @param accessToken  JWT de acceso nuevo (cookie {@code streambox_token});
 *                     siempre presente
 * @param refreshToken refresh token nuevo (cookie {@code streambox_refresh}), o
 *                     {@code null} si no cambia: ocurre en la gracia de un
 *                     refresh simultáneo, cuando el navegador ya tiene la
 *                     cookie del sucesor y no hay que pisarla
 */
public record SessionTokens(String accessToken, IssuedRefreshToken refreshToken) {

    /**
     * Representación sin los tokens (un {@code record} los mostraría todos).
     *
     * @return texto con los tokens enmascarados
     */
    @Override
    public String toString() {
        return "SessionTokens[accessToken=******, refreshToken=" + refreshToken + "]";
    }
}
