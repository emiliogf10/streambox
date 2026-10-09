package com.emilio.streambox.security.refresh;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Reloj manual y seguro entre hilos para los tests del refresh: solo avanza
 * con {@link #advance(Duration)}. Empieza en un segundo exacto para que los
 * instantes guardados en la base de datos (precisión de microsegundos) sean
 * iguales a los calculados en el test.
 */
final class ManualClock extends Clock {

    private volatile Instant now = Instant.parse("2026-03-01T09:00:00Z");

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
