package com.emilio.streambox.security.refresh;

import java.time.Duration;

/**
 * Refresh token recién emitido, tal como se entrega al navegador.
 *
 * <p>
 * Es el único sitio donde existe el token en claro: en la base de datos solo
 * se guarda su SHA-256. Por eso {@link #toString()} no lo muestra.
 * </p>
 *
 * @param value  token opaco (32 bytes aleatorios en Base64URL sin relleno, 43
 *               caracteres); va en la cookie {@code streambox_refresh}
 * @param maxAge vida que le queda (el {@code Max-Age} de la cookie): la del
 *               token, que nunca pasa del tope de su familia
 */
public record IssuedRefreshToken(String value, Duration maxAge) {

    /**
     * Representación sin el token.
     *
     * @return texto con la vida restante y el token enmascarado
     */
    @Override
    public String toString() {
        return "IssuedRefreshToken[value=******, maxAge=" + maxAge + "]";
    }
}
