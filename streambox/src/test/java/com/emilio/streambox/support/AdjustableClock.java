package com.emilio.streambox.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Reloj de los tests de la verificación del correo: va <b>parado</b> y solo
 * avanza con {@link #advance(Duration)}, y se puede volver a la hora real con
 * {@link #reset()}. Seguro entre hilos.
 *
 * <p>
 * <b>Por qué empieza en la hora real</b> (y no en una fecha fija como el
 * {@code ManualClock} del refresh): la fecha de alta de una cuenta
 * ({@code users.created_at}) la pone Hibernate con {@code @CreationTimestamp},
 * que usa la hora del sistema y no el {@link Clock} de la aplicación, mientras
 * que los tokens de verificación usan el {@link Clock}. El registro compara las
 * dos para distinguir una cuenta creada por el propio registro de una heredada
 * (ver {@code EmailVerificationService.isReplaceable}); con un reloj en otra
 * fecha, esa comparación no reflejaría lo que pasa en producción, donde los dos
 * son la misma hora. Se trunca al segundo para que los instantes guardados en
 * la base de datos (microsegundos) coincidan con los calculados en el test.
 * </p>
 *
 * <p>
 * Uso: un campo {@code @TestBean private Clock clock;} con una fábrica estática
 * {@code static Clock clock()} que devuelva la instancia del test, y
 * {@link #reset()} en el {@code @BeforeEach}.
 * </p>
 */
public final class AdjustableClock extends Clock {

    private volatile Instant now = realNow();

    /**
     * Adelanta el reloj.
     *
     * @param duration cuánto se adelanta
     */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    /** Vuelve a la hora real (truncada al segundo) y se queda parado ahí. */
    public void reset() {
        now = realNow();
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

    private static Instant realNow() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
