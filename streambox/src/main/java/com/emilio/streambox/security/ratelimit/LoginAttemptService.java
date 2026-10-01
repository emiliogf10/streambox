package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.emilio.streambox.exception.TooManyRequestsException;

/**
 * Bloquea temporalmente una cuenta tras varios intentos de login fallidos.
 *
 * <p>
 * Complementa el límite por IP: un atacante que rote de IP sigue chocando
 * con el límite por cuenta. Los fallos se cuentan por email tanto si la
 * cuenta existe como si no, de modo que el bloqueo no revela qué emails
 * están registrados.
 * </p>
 *
 * <p>
 * Contrapartida conocida: alguien puede bloquear a propósito la cuenta de
 * otra persona fallando su login. Se acepta porque el bloqueo es temporal
 * (los fallos caducan solos pasada la ventana).
 * </p>
 */
@Service
public class LoginAttemptService {

    private final int maxFailures;
    private final SlidingWindowCounter failures;

    /**
     * @param properties límites configurados
     * @param clock      reloj de la aplicación
     */
    public LoginAttemptService(RateLimitProperties properties, Clock clock) {
        this.maxFailures = properties.lockout().maxFailures();
        this.failures = new SlidingWindowCounter(properties.lockout().window(), clock);
    }

    /**
     * Comprueba que la cuenta no está bloqueada.
     *
     * @param email email con el que se intenta iniciar sesión
     * @throws TooManyRequestsException si la cuenta está bloqueada
     */
    public void checkNotLocked(String email) {
        String key = normalize(email);
        if (failures.count(key) >= maxFailures) {
            throw new TooManyRequestsException(
                    "Demasiados intentos fallidos. Inténtalo de nuevo más tarde.",
                    ceilToSeconds(failures.retryAfter(key)));
        }
    }

    /**
     * Anota un intento fallido.
     *
     * @param email email con el que se intentó iniciar sesión
     */
    public void recordFailure(String email) {
        failures.record(normalize(email));
    }

    /**
     * Borra los fallos acumulados tras un login correcto.
     *
     * @param email email del usuario autenticado
     */
    public void recordSuccess(String email) {
        failures.reset(normalize(email));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static Duration ceilToSeconds(Duration duration) {
        long seconds = (duration.toMillis() + 999) / 1000;
        return Duration.ofSeconds(Math.max(seconds, 1));
    }
}
