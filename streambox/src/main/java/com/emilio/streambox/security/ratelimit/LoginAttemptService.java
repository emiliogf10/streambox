package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounter.Reservation;

/**
 * Bloquea temporalmente una cuenta tras varios intentos de login fallidos e
 * informa de cuántos intentos quedan.
 *
 * <p>
 * Complementa el límite por IP: un atacante que rote de IP sigue chocando
 * con el límite por cuenta. Los fallos se cuentan por email (normalizado)
 * tanto si la cuenta existe como si no, de modo que ni el bloqueo ni los
 * intentos restantes revelan qué emails están registrados.
 * </p>
 *
 * <h2>Cómo se usa</h2>
 * <ol>
 * <li>{@link #reserveAttempt(String)} antes de comprobar la contraseña: si la
 * cuenta ya está bloqueada lanza {@link AccountLockedException}; si no, apunta
 * el intento <b>como si fuera a fallar</b> y lo devuelve.</li>
 * <li>Si la contraseña es incorrecta, {@link #recordFailure(LoginAttempt)}
 * devuelve los intentos que quedan o, si este fallo agota el máximo, lanza
 * {@link AccountLockedException}.</li>
 * <li>Si es correcta, {@link #recordSuccess(LoginAttempt)} borra los fallos
 * acumulados.</li>
 * </ol>
 *
 * <p>
 * <b>Por qué se reserva antes de comprobar la contraseña.</b> Antes se
 * consultaba si la cuenta estaba bloqueada y, tras comprobar la contraseña
 * (unos 100 ms de BCrypt), se apuntaba el fallo. En ese hueco, cien peticiones
 * simultáneas veían todas "0 fallos" y probaban cien contraseñas, saltándose
 * el límite de 5. Reservando el intento con una operación atómica
 * ({@link SlidingWindowCounter#reserve(String, int)}), como mucho
 * {@code maxFailures} peticiones llegan a comprobar la contraseña en cada
 * ventana, por muchas que lleguen a la vez, y cada una ocupa un puesto
 * distinto, así que los intentos restantes nunca salen 0 ni negativos en un 401.
 * </p>
 *
 * <p>
 * <b>Por qué los intentos restantes se calculan al fallar, no al reservar.</b>
 * Mientras se comprueba la contraseña pueden pasar cosas: un login correcto
 * simultáneo borra todos los fallos (también las reservas en curso), o caduca
 * un fallo antiguo. Antes se decidía solo con el número obtenido al reservar y,
 * con un máximo de 5, si de cinco intentos simultáneos el primero acertaba, los
 * otros cuatro respondían 3, 2, 1 y un 429 {@code ACCOUNT_LOCKED} con la cuenta
 * sin bloquear, y además no quedaban contados. Ahora
 * {@link SlidingWindowCounter#confirm(String, Reservation, int)} da la posición
 * real del intento en la ventana y, si un login correcto lo borró, lo vuelve a
 * apuntar como un fallo posterior a ese acierto: en el ejemplo, los cuatro
 * fallos responden 4, 3, 2 y 1 y cuentan para el siguiente bloqueo.
 * </p>
 *
 * <p>
 * <b>Ventana deslizante.</b> El bloqueo dura hasta que el fallo más antiguo
 * sale de la ventana (15 minutos por defecto). Si los fallos fueron seguidos,
 * son 15 minutos; si estaban repartidos, menos. Al terminar se libera un solo
 * intento: si vuelve a fallar, la cuenta se bloquea de nuevo hasta que caduque
 * el siguiente fallo. En ningún caso hay más de {@code maxFailures} fallos en
 * una misma ventana.
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
     * Reserva un intento de login para el email antes de comprobar la
     * contraseña.
     *
     * <p>
     * El intento queda apuntado como fallo hasta que se llame a
     * {@link #recordSuccess(LoginAttempt)}. Si la comprobación se interrumpe
     * por un error inesperado, cuenta como fallo: se prefiere equivocarse del
     * lado seguro.
     * </p>
     *
     * @param email email con el que se intenta iniciar sesión
     * @return intento reservado, que se cierra con
     *         {@link #recordFailure(LoginAttempt)} o {@link #recordSuccess(LoginAttempt)}
     * @throws AccountLockedException si la cuenta ya está bloqueada (aunque la
     *                                contraseña fuera a ser correcta)
     */
    public LoginAttempt reserveAttempt(String email) {
        String key = normalize(email);
        Reservation reservation = failures.reserve(key, maxFailures)
                .orElseThrow(() -> alreadyLocked(key));
        return new LoginAttempt(key, reservation);
    }

    /**
     * Confirma que el intento reservado ha fallado.
     *
     * <p>
     * El fallo ya se contó al reservar; aquí se decide la respuesta a partir
     * de la posición <b>actual</b> del intento en la ventana (ver la
     * explicación de la clase). Si un login correcto simultáneo borró la
     * reserva, el fallo se vuelve a apuntar.
     * </p>
     *
     * @param attempt intento devuelto por {@link #reserveAttempt(String)}
     * @return intentos que quedan antes del bloqueo (siempre &ge; 1)
     * @throws AccountLockedException si este fallo es el que agota los
     *                                intentos o, en una carrera, si al volver a
     *                                apuntarlo la cuenta ya estaba bloqueada
     */
    public int recordFailure(LoginAttempt attempt) {
        int position = failures.confirm(attempt.key(), attempt.reservation(), maxFailures);
        if (position == 0) {
            // Solo en una carrera: un acierto borró la reserva y, antes de
            // volver a apuntarla, otros fallos ya habían llenado la ventana.
            throw alreadyLocked(attempt.key());
        }
        int remaining = maxFailures - position;
        if (remaining <= 0) {
            Duration retryAfter = retryAfter(attempt.key());
            throw new AccountLockedException(
                    "Has superado el número máximo de intentos. La cuenta queda bloqueada durante "
                            + minutes(retryAfter) + ".",
                    retryAfter);
        }
        return remaining;
    }

    /**
     * Borra los fallos acumulados (incluido el intento reservado) tras un
     * login correcto.
     *
     * <p>
     * También borra las reservas de otros intentos que estén en curso; si
     * alguno de ellos falla después, {@link #recordFailure(LoginAttempt)} lo
     * vuelve a apuntar como un fallo posterior a este acierto.
     * </p>
     *
     * @param attempt intento devuelto por {@link #reserveAttempt(String)}
     */
    public void recordSuccess(LoginAttempt attempt) {
        failures.reset(attempt.key());
    }

    /** Excepción de una cuenta que ya estaba bloqueada antes de este intento. */
    private AccountLockedException alreadyLocked(String key) {
        Duration retryAfter = retryAfter(key);
        return new AccountLockedException(
                "La cuenta está bloqueada temporalmente por demasiados intentos fallidos. "
                        + "Inténtalo de nuevo en " + minutes(retryAfter) + ".",
                retryAfter);
    }

    /**
     * Tiempo hasta que se libere un intento, redondeado hacia arriba a
     * segundos (lo que se envía en {@code Retry-After}) y de al menos 1 s.
     */
    private Duration retryAfter(String key) {
        long seconds = (failures.retryAfter(key).toMillis() + 999) / 1000;
        return Duration.ofSeconds(Math.max(seconds, 1));
    }

    /**
     * Duración legible en minutos, redondeando hacia arriba: con 14 min y 1 s
     * se dice "15 minutos", para no prometer menos espera de la real.
     */
    static String minutes(Duration duration) {
        long minutes = Math.max((duration.toSeconds() + 59) / 60, 1);
        return minutes == 1 ? "1 minuto" : minutes + " minutos";
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Intento de login reservado.
     *
     * @param key         email normalizado (clave del contador)
     * @param reservation reserva en el contador de fallos
     */
    public record LoginAttempt(String key, Reservation reservation) {

        /**
         * @return número de orden del intento en la ventana en el momento de
         *         reservarlo (de 1 al máximo); los intentos restantes se
         *         calculan al fallar, con la posición de ese momento
         */
        public int number() {
            return reservation.number();
        }
    }
}
