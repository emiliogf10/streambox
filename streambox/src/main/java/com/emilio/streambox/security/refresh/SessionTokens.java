package com.emilio.streambox.security.refresh;

import com.emilio.streambox.security.device.IssuedDeviceToken;

/**
 * Tokens que el controlador debe entregar en cookies tras un login, un
 * refresh o un cambio de contraseña.
 *
 * @param accessToken  JWT de acceso nuevo (cookie {@code streambox_token});
 *                     siempre presente
 * @param refreshToken refresh token nuevo (cookie {@code streambox_refresh}), o
 *                     {@code null} si no cambia: ocurre en la gracia de un
 *                     refresh simultáneo, cuando el navegador ya tiene la
 *                     cookie del sucesor y no hay que pisarla
 * @param deviceToken  cookie de dispositivo ({@code streambox_device}) emitida
 *                     o renovada, o {@code null} si no hay que tocarla: solo la
 *                     traen el login correcto y el cambio de contraseña, que
 *                     prueban la contraseña; el refresh no (ver
 *                     {@code DeviceCookieService})
 */
public record SessionTokens(String accessToken, IssuedRefreshToken refreshToken, IssuedDeviceToken deviceToken) {

    /**
     * Tokens sin cookie de dispositivo (el refresh).
     *
     * @param accessToken  JWT de acceso nuevo
     * @param refreshToken refresh token nuevo, o {@code null} si no cambia
     */
    public SessionTokens(String accessToken, IssuedRefreshToken refreshToken) {
        this(accessToken, refreshToken, null);
    }

    /**
     * Representación sin los tokens (un {@code record} los mostraría todos).
     *
     * @return texto con los tokens enmascarados
     */
    @Override
    public String toString() {
        return "SessionTokens[accessToken=******, refreshToken=" + refreshToken
                + ", deviceToken=" + deviceToken + "]";
    }
}
