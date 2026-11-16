package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/**
 * Contador por dispositivo de {@link LoginAttemptService} (cookie de
 * dispositivo, plan 2.5): con un id de dispositivo ya validado, el intento usa
 * su propio contador con los mismos límites, independiente del de la cuenta y
 * del de las IPs conocidas. Valores de producción (5 fallos / 15 minutos).
 */
class LoginAttemptServiceDeviceTest {

    private static final String OWNER = "titular@device.test";
    private static final String HOME = "203.0.113.30";
    private static final String ATTACKER = "198.51.100.30";
    private static final String DEVICE = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String OTHER_DEVICE = "BBBBBBBBBBBBBBBBBBBBBB";

    private final MutableClock clock = new MutableClock();

    private final LoginAttemptService service = service();

    private LoginAttemptService service() {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule(100, Duration.ofMinutes(1));
        return new LoginAttemptService(new RateLimitProperties(rule, rule, rule, rule, rule, rule, rule,
                new RateLimitProperties.Lockout(5, Duration.ofMinutes(15), 5, Duration.ofDays(30)), 100_000), clock);
    }

    /** Cinco fallos desde la IP sin cookie: el quinto bloquea la cuenta. */
    private void lockAccountFrom(String ip) {
        for (int i = 0; i < 4; i++) {
            service.recordFailure(service.reserveAttempt(OWNER, ip));
        }
        assertThrows(AccountLockedException.class, () -> service.recordFailure(service.reserveAttempt(OWNER, ip)));
    }

    /** Con la cuenta bloqueada, el dispositivo con cookie sigue pudiendo intentar y entrar. */
    @Test
    void elBloqueoDeLaCuentaNoAfectaAlDispositivo() {
        lockAccountFrom(ATTACKER);
        assertThrows(AccountLockedException.class, () -> service.reserveAttempt(OWNER, HOME));

        LoginAttempt attempt = service.reserveAttempt(OWNER, ATTACKER, DEVICE);

        assertEquals(DEVICE, attempt.deviceId());
        assertFalse(attempt.knownIp());
        service.recordSuccess(attempt);
        // El acierto con cookie no desbloquea la cuenta para los demás.
        assertThrows(AccountLockedException.class, () -> service.reserveAttempt(OWNER, "192.0.2.30"));
    }

    /** Con cookie siguen bastando 5 fallos para bloquear ESE dispositivo (fuerza bruta limitada). */
    @Test
    void elDispositivoTieneLosMismosLimites() {
        for (int remaining = 4; remaining >= 1; remaining--) {
            assertEquals(remaining, service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE)));
        }
        assertThrows(AccountLockedException.class,
                () -> service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE)));
        assertThrows(AccountLockedException.class, () -> service.reserveAttempt(OWNER, HOME, DEVICE));

        // Ni la cuenta ni otro dispositivo se ven afectados.
        assertNull(service.reserveAttempt(OWNER, HOME).deviceId());
        assertEquals(OTHER_DEVICE, service.reserveAttempt(OWNER, HOME, OTHER_DEVICE).deviceId());
    }

    /** Un acierto con cookie reinicia solo su contador y anota la IP como conocida. */
    @Test
    void unAciertoReiniciaSoloElContadorDelDispositivoYConoceLaIp() {
        service.recordFailure(service.reserveAttempt(OWNER, ATTACKER));
        service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE));

        service.recordSuccess(service.reserveAttempt(OWNER, HOME, DEVICE));

        assertEquals(4, service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE)));
        // El contador de la cuenta conserva el fallo del atacante (4 → 3).
        assertEquals(3, service.recordFailure(service.reserveAttempt(OWNER, ATTACKER)));
        // La IP desde la que acertó ya es conocida (respaldo sin cookie).
        assertTrue(service.reserveAttempt(OWNER, HOME).knownIp());
    }

    /** El contador del dispositivo caduca con la ventana, como los demás. */
    @Test
    void elBloqueoDelDispositivoCaducaConLaVentana() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE));
        }
        assertThrows(AccountLockedException.class,
                () -> service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE)));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertEquals(4, service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE)));
    }

    /** El mismo id de dispositivo con otro email es otro contador (la clave lleva el email). */
    @Test
    void elContadorEsPorCuentaYDispositivo() {
        for (int i = 0; i < 4; i++) {
            service.recordFailure(service.reserveAttempt(OWNER, HOME, DEVICE));
        }

        assertEquals(4, service.recordFailure(service.reserveAttempt("otra@device.test", HOME, DEVICE)));
    }
}
