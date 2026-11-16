package com.emilio.streambox.security.device;

import java.time.Duration;

/**
 * Cookie de dispositivo recién emitida, tal como se entrega al navegador
 * (cookie {@code streambox_device}; ver {@link DeviceCookieService}).
 *
 * <p>
 * No autentica a nadie (no abre sesión), pero da un presupuesto propio de
 * intentos de login para la cuenta: por eso {@link #toString()} no la muestra,
 * igual que los demás tokens.
 * </p>
 *
 * @param value  valor firmado ({@code v1.<payload>.<firma>})
 * @param maxAge vida de la cookie ({@code Max-Age})
 */
public record IssuedDeviceToken(String value, Duration maxAge) {

    /**
     * Representación sin el valor.
     *
     * @return texto con la vida y el valor enmascarado
     */
    @Override
    public String toString() {
        return "IssuedDeviceToken[value=******, maxAge=" + maxAge + "]";
    }
}
