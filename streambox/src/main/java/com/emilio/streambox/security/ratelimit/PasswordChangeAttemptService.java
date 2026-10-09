package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Service;

import com.emilio.streambox.exception.TooManyRequestsException;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounter.Reservation;

/**
 * Limita los intentos de cambiar la contraseña con la contraseña actual
 * incorrecta ({@code PUT /api/users/me/password}), por cuenta.
 *
 * <p>
 * <b>Por qué hace falta.</b> Ese endpoint compara la contraseña actual con la
 * de la cuenta: sin límite sería un oráculo para adivinarla. Quien robe una
 * sesión (un JWT copiado, un ordenador desbloqueado) no conoce la contraseña,
 * pero podría probar miles por segundo desde la sesión, sin pasar por el
 * bloqueo del login; si acertara, la cambiaría y echaría al titular.
 * </p>
 *
 * <p>
 * <b>Límites.</b> Los mismos que el bloqueo del login
 * ({@code streambox.security.rate-limit.lockout.max-failures} y
 * {@code .window}: 5 fallos cada 15 minutos), en un contador propio cuya clave
 * es el id del usuario (sale del token, nunca del cliente). Son contadores
 * separados: los fallos aquí no bloquean el login de la cuenta, y al revés.
 * Al agotarlos se responde 429 {@code RATE_LIMIT_EXCEEDED} con
 * {@code Retry-After} (no {@code ACCOUNT_LOCKED}: la cuenta puede seguir
 * iniciando sesión).
 * </p>
 *
 * <p>
 * <b>Reserva antes de comprobar</b>, igual que {@link LoginAttemptService}: el
 * intento se apunta de forma atómica <em>antes</em> de BCrypt, así que cien
 * peticiones simultáneas no pueden probar más de 5 contraseñas. Si la
 * contraseña es correcta, se borran los fallos; si un acierto simultáneo
 * borró la reserva de un fallo, {@link #recordFailure(Attempt)} la vuelve a
 * apuntar.
 * </p>
 *
 * <p>
 * Limitaciones: en memoria, como el resto de contadores (se pierde al
 * reiniciar y no se comparte entre réplicas; ver {@link SlidingWindowCounter}).
 * </p>
 */
@Service
public class PasswordChangeAttemptService {

    private final int maxFailures;

    /** Fallos por cuenta (clave: id del usuario). */
    private final SlidingWindowCounter failures;

    /**
     * @param properties límites configurados (se usan los del bloqueo del login)
     * @param clock      reloj de la aplicación
     */
    public PasswordChangeAttemptService(RateLimitProperties properties, Clock clock) {
        RateLimitProperties.Lockout lockout = properties.lockout();
        this.maxFailures = lockout.maxFailures();
        this.failures = new SlidingWindowCounter(lockout.window(), clock, properties.maxKeys());
    }

    /**
     * Reserva un intento antes de comprobar la contraseña actual.
     *
     * @param userId id del usuario autenticado
     * @return intento reservado, que se cierra con {@link #recordFailure(Attempt)}
     *         o {@link #recordSuccess(Attempt)}
     * @throws TooManyRequestsException si ya se han agotado los intentos
     */
    public Attempt reserve(Long userId) {
        String key = String.valueOf(userId);
        Reservation reservation = failures.reserve(key, maxFailures)
                .orElseThrow(() -> tooManyAttempts(key));
        return new Attempt(key, reservation);
    }

    /**
     * Confirma que el intento ha fallado (ya contaba desde la reserva; si un
     * acierto simultáneo lo borró, se vuelve a apuntar).
     *
     * @param attempt intento devuelto por {@link #reserve(Long)}
     */
    public void recordFailure(Attempt attempt) {
        failures.confirm(attempt.key(), attempt.reservation(), maxFailures);
    }

    /**
     * La contraseña actual era correcta: se borran los fallos de la cuenta.
     *
     * @param attempt intento devuelto por {@link #reserve(Long)}
     */
    public void recordSuccess(Attempt attempt) {
        failures.reset(attempt.key());
    }

    private TooManyRequestsException tooManyAttempts(String key) {
        // Redondeo hacia arriba a segundos y mínimo 1 s, como el login.
        long seconds = (failures.retryAfter(key).toMillis() + 999) / 1000;
        Duration retryAfter = Duration.ofSeconds(Math.max(seconds, 1));
        return new TooManyRequestsException(
                "Demasiados intentos con la contraseña actual incorrecta. Inténtalo de nuevo en "
                        + LoginAttemptService.minutes(retryAfter) + ".",
                retryAfter);
    }

    /**
     * Intento reservado.
     *
     * @param key         clave del contador (id del usuario)
     * @param reservation reserva en el contador
     */
    public record Attempt(String key, Reservation reservation) {
    }
}
