package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/**
 * Casos límite de {@link LoginAttemptService} que no cubre
 * {@link LoginAttemptServiceTest} (revisión independiente de QA).
 */
class LoginAttemptServiceEdgeCasesTest {

    private static final String EMAIL = "eva@test.com";

    private final MutableClock clock = new MutableClock();

    /**
     * Con un máximo de 1 el primer fallo ya bloquea: nunca puede salir un 401
     * con 0 intentos restantes, ni siquiera en la configuración más estricta.
     */
    @Test
    void conMaximoUnoElPrimerFalloYaBloqueaSinPasarPorUn401ConCero() {
        LoginAttemptService service = service(1);

        LoginAttempt first = service.reserveAttempt(EMAIL);
        AccountLockedException locked = assertThrows(AccountLockedException.class,
                () -> service.recordFailure(first));

        assertEquals(Duration.ofMinutes(15), locked.getRetryAfter());
        assertThrows(AccountLockedException.class, () -> service.reserveAttempt(EMAIL));
    }

    /**
     * Límite exacto de la ventana: los fallos caducan justo a los 15 minutos
     * (no un milisegundo después), que es lo que promete el mensaje.
     */
    @Test
    void losFallosCaducanJustoAlCumplirseLaVentana() {
        LoginAttemptService service = service(5);
        for (int i = 0; i < 4; i++) {
            service.recordFailure(service.reserveAttempt(EMAIL));
        }
        assertThrows(AccountLockedException.class,
                () -> service.recordFailure(service.reserveAttempt(EMAIL)));

        clock.advance(Duration.ofMinutes(15).minusMillis(1));
        AccountLockedException stillLocked =
                assertThrows(AccountLockedException.class, () -> service.reserveAttempt(EMAIL));
        assertEquals(Duration.ofSeconds(1), stillLocked.getRetryAfter());

        clock.advance(Duration.ofMillis(1));
        assertEquals(1, service.reserveAttempt(EMAIL).number());
    }

    /**
     * El bloqueo es por email: los intentos de un email no gastan los de otro
     * aunque compartan el mismo prefijo o difieran solo en el dominio.
     */
    @Test
    void emailsParecidosNoCompartenContador() {
        LoginAttemptService service = service(2);
        service.recordFailure(service.reserveAttempt("eva@test.com"));

        assertEquals(1, service.recordFailure(service.reserveAttempt("eva@test.es")));
        assertEquals(1, service.recordFailure(service.reserveAttempt("eva.m@test.com")));
    }

    private LoginAttemptService service(int maxFailures) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule(100, Duration.ofMinutes(1));
        return new LoginAttemptService(
                new RateLimitProperties(rule, rule, new RateLimitProperties.Lockout(maxFailures, Duration.ofMinutes(15))),
                clock);
    }
}
