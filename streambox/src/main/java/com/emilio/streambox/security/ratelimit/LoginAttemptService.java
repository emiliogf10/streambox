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
 * <li>{@link #reserveAttempt(String, String)} antes de comprobar la contraseña:
 * si la cuenta ya está bloqueada (para esa IP, ver más abajo) lanza
 * {@link AccountLockedException}; si no, apunta el intento <b>como si fuera a
 * fallar</b> y lo devuelve.</li>
 * <li>Si la contraseña es incorrecta, {@link #recordFailure(LoginAttempt)}
 * devuelve los intentos que quedan o, si este fallo agota el máximo, lanza
 * {@link AccountLockedException}.</li>
 * <li>Si es correcta, {@link #recordSuccess(LoginAttempt)} borra los fallos
 * acumulados y anota la IP como «conocida» de la cuenta.</li>
 * </ol>
 *
 * <p>
 * Una cuenta pendiente de confirmar el correo con la contraseña correcta se
 * cierra con {@link #recordFailure(LoginAttempt)}, como una contraseña
 * incorrecta: no hay un tercer desenlace. Así el login responde lo mismo que
 * a un fallo (y no delata que el registro creó esa cuenta con la contraseña
 * de quien lo intenta; ver {@code AuthenticationService}).
 * </p>
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
 * <h2>IP conocida: el bloqueo no deja fuera al titular (hallazgo NV-2)</h2>
 * <p>
 * Un bloqueo por email que rechaza el login aunque la contraseña sea correcta
 * se puede <b>renovar</b>: un anónimo que falle 5 veces cada 15 minutos
 * (unas 20 peticiones por hora) mantiene la cuenta, también la de un
 * administrador, bloqueada indefinidamente. Se mitiga así:
 * </p>
 * <ul>
 * <li>Una IP pasa a ser «conocida» de una cuenta <b>solo tras un login
 * correcto</b> (contraseña verificada); ver {@link KnownIpRegistry} (máximo
 * de IPs por cuenta, caducidad y memoria acotada, configurables en
 * {@code streambox.security.rate-limit.lockout.*}).</li>
 * <li>Una IP <b>desconocida</b> usa el contador de la cuenta, como siempre. Sus
 * fallos bloquean a las demás IPs desconocidas, pero <b>no afectan a las IPs
 * conocidas</b>.</li>
 * <li>Una IP <b>conocida</b> usa su <b>propio contador</b> (email + IP) con los
 * mismos límites: adivinar contraseñas desde ella sigue limitado a 5 fallos
 * cada 15 minutos, y sus fallos <b>no bloquean la cuenta</b> para las demás
 * IPs. Un login correcto reinicia el contador contra el que se reservó.</li>
 * <li>Respuestas indistinguibles <b>desde la misma IP de origen</b>: para esa
 * IP, las respuestas (401 con {@code remainingAttempts}, 429
 * {@code ACCOUNT_LOCKED} con el mismo mensaje) y el trabajo son idénticos
 * para un email que existe o no y para una IP conocida o no, de modo que quien
 * consulta desde un solo origen no puede deducir nada de ello. Un email
 * inexistente nunca tiene IPs conocidas, porque solo se anotan tras un login
 * correcto. Lo que <b>no</b> se garantiza es la indistinguibilidad al
 * <b>cruzar dos orígenes</b> bajo una IP compartida (ver las limitaciones).</li>
 * <li>La IP es {@code request.getRemoteAddr()} agrupada con
 * {@link ClientAddress#counterKey(String)} (IPv6 por /64), el mismo criterio que
 * el límite por IP del filtro; nunca se lee {@code X-Forwarded-For} a mano.</li>
 * <li>Cambiar la contraseña o cerrar sesión en todos los dispositivos
 * <b>olvida las IPs conocidas</b> de la cuenta (el cambio de contraseña
 * conserva solo la IP de quien lo hace): quien entró con la contraseña robada
 * no se lleva 30 días de IP conocida. Ver {@link #forgetKnownIps(String)} y
 * {@link #forgetKnownIpsExcept(String, String)}.</li>
 * <li>Restablecer la contraseña con el enlace del correo olvida las IPs
 * conocidas y además <b>borra todos los fallos</b> de la cuenta
 * ({@link #clearFailures(String)}): el titular que se quedó fuera entra en
 * seguida con la contraseña nueva.</li>
 * </ul>
 *
 * <p>
 * <b>Limitaciones conocidas</b> (se aceptan, ver el hallazgo NV-2):
 * </p>
 * <ul>
 * <li>El estado (contadores e IPs conocidas) está <b>en memoria</b>: se pierde
 * al reiniciar y no se comparte entre réplicas, igual que los demás
 * contadores. Tras un reinicio nadie es «conocido» hasta su próximo login
 * correcto.</li>
 * <li>Un primer login desde una <b>IP nueva</b> (otro dispositivo, otra red)
 * durante un bloqueo de la cuenta sigue rechazado hasta que caduque: la
 * contraseña correcta no basta para entrar desde una IP desconocida, porque
 * entonces el bloqueo no protegería de la fuerza bruta.</li>
 * <li>Detrás de un proxy o NAT que agrupe a varios clientes (o un /64 con
 * varios usuarios), la «IP conocida» es la del proxy: cualquiera tras él que
 * comparta la IP del titular comparte su contador, y sus fallos pueden dejar
 * fuera al titular en esa IP durante la ventana.</li>
 * <li><b>IP compartida (CGNAT móvil, red de empresa o campus, salida de VPN,
 * un /64 compartido).</b> Quien comparta salida con el titular tiene una IP
 * que <i>sí</i> es conocida para la cuenta, y de ahí salen dos efectos
 * acotados. (a) <b>Oráculo de «login correcto reciente»:</b> llenando el
 * contador de la cuenta desde otra IP y comprobando si desde la IP compartida
 * recibe 401 (conocida: usa su contador) o 429 (desconocida: usa el de la
 * cuenta), averigua si esa IP tuvo un login correcto reciente en esa cuenta.
 * Exige co-localización con la víctima y unos 6 intentos por sonda (5 para
 * llenar el contador de la cuenta más el de comprobación). Lo que filtra es
 * solo que esa cuenta existe y se usó desde esa IP; no hay fuga de
 * contraseñas. (b) <b>Doble
 * presupuesto de fuerza bruta en esa IP:</b> hay dos contadores distintos
 * (el de la cuenta, 5 fallos, mientras la IP era desconocida; y el de
 * (email, IP), otros 5, desde que el titular la «conoce»), de modo que en una
 * misma ventana puede gastar hasta 10 fallos en lugar de 5. Se acepta porque
 * requiere compartir IP con el titular y el límite por IP y la política de
 * contraseñas siguen aplicándose.</li>
 * <li>Alguien puede seguir bloqueando a propósito la cuenta para las IPs
 * desconocidas fallando su login; el bloqueo es temporal (los fallos caducan
 * solos pasada la ventana) y el titular entra desde sus IPs conocidas.</li>
 * </ul>
 */
@Service
public class LoginAttemptService {

    /**
     * Separador entre el email y la IP en la clave del contador de IP conocida.
     * No puede aparecer en una IP, así que la clave es inequívoca aunque el
     * email contenga este carácter.
     */
    private static final char KEY_SEPARATOR = '|';

    private final int maxFailures;

    /** Fallos por cuenta (clave: email); lo usan las IPs desconocidas. */
    private final SlidingWindowCounter failures;

    /** Fallos por cuenta e IP conocida (clave: email + IP), con los mismos límites. */
    private final SlidingWindowCounter knownIpFailures;

    private final KnownIpRegistry knownIps;

    /**
     * @param properties límites configurados
     * @param clock      reloj de la aplicación
     */
    public LoginAttemptService(RateLimitProperties properties, Clock clock) {
        RateLimitProperties.Lockout lockout = properties.lockout();
        this.maxFailures = lockout.maxFailures();
        this.failures = new SlidingWindowCounter(lockout.window(), clock, properties.maxKeys());
        this.knownIpFailures = new SlidingWindowCounter(lockout.window(), clock, properties.maxKeys());
        this.knownIps = new KnownIpRegistry(
                properties.maxKeys(), lockout.maxKnownIps(), lockout.knownIpTtl(), clock);
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
     * <p>
     * Si la IP es «conocida» de la cuenta se reserva en el contador propio de
     * (email, IP); si no, en el de la cuenta (ver la explicación de la clase).
     * </p>
     *
     * @param email email con el que se intenta iniciar sesión
     * @param ip    dirección remota del cliente ({@code getRemoteAddr()}); si es
     *              {@code null} se trata como desconocida
     * @return intento reservado, que se cierra con
     *         {@link #recordFailure(LoginAttempt)} o {@link #recordSuccess(LoginAttempt)}
     * @throws AccountLockedException si la cuenta ya está bloqueada para esa IP
     *                                (aunque la contraseña fuera a ser correcta)
     */
    public LoginAttempt reserveAttempt(String email, String ip) {
        String key = normalize(email);
        String client = ClientAddress.counterKey(ip);
        boolean known = knownIps.isKnown(key, client);

        SlidingWindowCounter counter = known ? knownIpFailures : failures;
        String counterKey = counterKey(key, client, known);
        Reservation reservation = counter.reserve(counterKey, maxFailures)
                .orElseThrow(() -> alreadyLocked(counter, counterKey));
        return new LoginAttempt(key, client, known, reservation);
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
     * @param attempt intento devuelto por {@link #reserveAttempt(String, String)}
     * @return intentos que quedan antes del bloqueo (siempre &ge; 1)
     * @throws AccountLockedException si este fallo es el que agota los
     *                                intentos o, en una carrera, si al volver a
     *                                apuntarlo la cuenta ya estaba bloqueada
     */
    public int recordFailure(LoginAttempt attempt) {
        SlidingWindowCounter counter = counterOf(attempt);
        String counterKey = counterKeyOf(attempt);
        int position = counter.confirm(counterKey, attempt.reservation(), maxFailures);
        if (position == 0) {
            // Solo en una carrera: un acierto borró la reserva y, antes de
            // volver a apuntarla, otros fallos ya habían llenado la ventana.
            throw alreadyLocked(counter, counterKey);
        }
        int remaining = maxFailures - position;
        if (remaining <= 0) {
            Duration retryAfter = retryAfter(counter, counterKey);
            throw new AccountLockedException(
                    "Has superado el número máximo de intentos. La cuenta queda bloqueada durante "
                            + minutes(retryAfter) + ".",
                    retryAfter);
        }
        return remaining;
    }

    /**
     * Borra los fallos acumulados (incluido el intento reservado) tras un
     * login correcto y anota la IP como «conocida» de la cuenta (o renueva su
     * plazo).
     *
     * <p>
     * Solo se borra el contador contra el que se reservó el intento: el de la
     * cuenta si la IP era desconocida, el de (email, IP) si era conocida.
     * También borra las reservas de otros intentos que estén en curso en ese
     * contador; si alguno de ellos falla después,
     * {@link #recordFailure(LoginAttempt)} lo vuelve a apuntar como un fallo
     * posterior a este acierto.
     * </p>
     *
     * <p>
     * Hay que llamarlo <b>únicamente</b> con la contraseña verificada de una
     * cuenta que existe: es lo que impide que un intento fallido, o un email
     * inexistente, «conozcan» una IP.
     * </p>
     *
     * @param attempt intento devuelto por {@link #reserveAttempt(String, String)}
     */
    public void recordSuccess(LoginAttempt attempt) {
        counterOf(attempt).reset(counterKeyOf(attempt));
        knownIps.remember(attempt.key(), attempt.ip());
    }

    /**
     * Olvida todas las IPs conocidas de la cuenta. Se llama tras
     * «cerrar sesión en todos los dispositivos».
     *
     * <p>
     * <b>Por qué.</b> Una IP conocida se libra del bloqueo de la cuenta y tiene
     * su propio contador durante 30 días. Si quien la «conoció» era un intruso
     * (entró con la contraseña robada), expulsarlo de las sesiones no basta:
     * desde su IP seguiría probando contraseñas con su propio presupuesto de
     * fallos, sin sufrir el bloqueo de la cuenta. Al olvidarlas, todas sus IPs
     * vuelven a usar el contador de la cuenta.
     * </p>
     *
     * <p>
     * <b>No se re-registra la IP de la petición</b>: {@code logout-all} solo
     * prueba que se tiene un JWT válido, no la contraseña, y la regla de
     * {@link KnownIpRegistry} es que una IP solo se «conoce» con la contraseña
     * verificada (si no, quien robara la cookie haría conocida su IP).
     * </p>
     *
     * <p>
     * Los contadores (email, IP) de esas IPs no se borran: dejan de usarse en
     * cuanto la IP es desconocida y caducan solos en la ventana; borrarlos no
     * protegería nada y solo podría devolver intentos a alguien.
     * </p>
     *
     * @param email email de la cuenta (se normaliza como en el login)
     */
    public void forgetKnownIps(String email) {
        knownIps.forget(normalize(email));
    }

    /**
     * Olvida todas las IPs conocidas de la cuenta salvo la de esta petición,
     * que queda como la única conocida. Se llama tras un <b>cambio de
     * contraseña confirmado</b>.
     *
     * <p>
     * Olvidar las demás tiene el mismo motivo que
     * {@link #forgetKnownIps(String)}: un intruso que entró con la contraseña
     * antigua no conserva su IP conocida.
     * </p>
     *
     * <p>
     * <b>Por qué se conserva la IP de quien la cambia.</b> El cambio exige la
     * contraseña actual verificada con BCrypt desde esa IP, la misma prueba que
     * un login correcto (la única condición para «conocer» una IP), así que no
     * se abre ningún camino nuevo. Y protege al titular: el intruso recién
     * expulsado podría vengarse bloqueando la cuenta con 5 fallos desde
     * cualquier IP desconocida; si el titular quedara sin IPs conocidas, ese
     * bloqueo le impediría volver a entrar durante la ventana aunque acabe de
     * cambiar la contraseña.
     * </p>
     *
     * @param email email de la cuenta (se normaliza como en el login)
     * @param ip    dirección remota de la petición ({@code getRemoteAddr()});
     *              se agrupa con {@link ClientAddress#counterKey(String)} como en
     *              el login; si es {@code null} solo se olvidan las demás
     */
    public void forgetKnownIpsExcept(String email, String ip) {
        knownIps.forgetAllBut(normalize(email), ClientAddress.counterKey(ip));
    }

    /**
     * Borra todos los fallos de login de la cuenta: los del contador de la
     * cuenta (el bloqueo que sufren las IPs desconocidas) y los de
     * <b>todas</b> sus IPs, conocidas o ya olvidadas. Se llama tras un
     * <b>restablecimiento de contraseña confirmado</b> («He olvidado mi
     * contraseña»), junto con {@link #forgetKnownIpsExcept(String, String)}
     * (se conserva la IP de quien confirmó, como en el cambio de contraseña).
     *
     * <p>
     * <b>Por qué.</b> Quien confirma el restablecimiento ha demostrado que
     * controla el buzón de la cuenta (el token solo viaja en ese correo) y
     * acaba de fijar la contraseña. Es justo el caso del titular que se ha
     * quedado fuera: a menudo llega aquí <i>después</i> de agotar sus
     * intentos, o porque un intruso le bloquea la cuenta a propósito. Si los
     * fallos siguieran contando, la contraseña nueva le daría un 429
     * {@code ACCOUNT_LOCKED} durante la ventana (15 minutos), y como el
     * restablecimiento olvida sus IPs conocidas, tampoco se libraría desde
     * ellas.
     * </p>
     *
     * <p>
     * <b>Por qué no abre nada a un atacante.</b> Solo lo provoca un token
     * válido del correo, de un solo uso, cuya emisión está limitada por IP y,
     * en silencio, por cuenta. Quien lo tiene ya controla el buzón y puede
     * fijar la contraseña que quiera: devolverle intentos de login no le da
     * nada que no tenga. Las reservas de intentos que estén en curso se
     * borran también; si alguna falla después,
     * {@link #recordFailure(LoginAttempt)} la vuelve a apuntar como un fallo
     * posterior (igual que tras un login correcto).
     * </p>
     *
     * <p>
     * <b>Por qué también los contadores (email, IP)</b>, a diferencia de
     * {@link #forgetKnownIps(String)}. Una IP olvidada deja de usar su
     * contador, pero si el titular vuelve a iniciar sesión desde ella dentro de
     * la ventana, la IP vuelve a ser conocida y se encontraría con los fallos
     * antiguos (p. ej. los del intruso desde esa misma red): bloqueado en
     * cuanto falle una vez. Tras un restablecimiento la cuenta empieza de cero.
     * No depende de las IPs que se recuerden ahora, así que el orden respecto a
     * {@link #forgetKnownIps(String)} da igual.
     * </p>
     *
     * <p>
     * <b>Coste.</b> Los contadores (email, IP) se buscan recorriendo sus claves
     * ({@link SlidingWindowCounter#resetMatching}), como mucho
     * {@code max-keys}: aceptable para una operación así de rara. La clave de
     * cada una es el email, el separador y la IP; como la IP no puede contener
     * el separador, se compara hasta el <b>último</b> separador, y el email de
     * otra cuenta que contenga este como prefijo (p. ej.
     * {@code a@x.test|b@x.test}) no se ve afectado.
     * </p>
     *
     * @param email email de la cuenta (se normaliza como en el login)
     */
    public void clearFailures(String email) {
        String key = normalize(email);
        failures.reset(key);
        knownIpFailures.resetMatching(counterKey -> {
            int separator = counterKey.lastIndexOf(KEY_SEPARATOR);
            return separator == key.length() && counterKey.startsWith(key);
        });
    }

    private SlidingWindowCounter counterOf(LoginAttempt attempt) {
        return attempt.knownIp() ? knownIpFailures : failures;
    }

    private static String counterKeyOf(LoginAttempt attempt) {
        return counterKey(attempt.key(), attempt.ip(), attempt.knownIp());
    }

    private static String counterKey(String email, String ip, boolean knownIp) {
        return knownIp ? email + KEY_SEPARATOR + ip : email;
    }

    /** Excepción de una cuenta que ya estaba bloqueada antes de este intento. */
    private AccountLockedException alreadyLocked(SlidingWindowCounter counter, String counterKey) {
        Duration retryAfter = retryAfter(counter, counterKey);
        return new AccountLockedException(
                "La cuenta está bloqueada temporalmente por demasiados intentos fallidos. "
                        + "Inténtalo de nuevo en " + minutes(retryAfter) + ".",
                retryAfter);
    }

    /**
     * Tiempo hasta que se libere un intento, redondeado hacia arriba a
     * segundos (lo que se envía en {@code Retry-After}) y de al menos 1 s.
     */
    private Duration retryAfter(SlidingWindowCounter counter, String counterKey) {
        long seconds = (counter.retryAfter(counterKey).toMillis() + 999) / 1000;
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
     * @param key         email normalizado
     * @param ip          clave de la IP del cliente ({@link ClientAddress}); puede
     *                    ser {@code null}
     * @param knownIp     si la IP era «conocida» de la cuenta al reservar: decide
     *                    contra qué contador se reservó y se cierra el intento
     *                    (se fija al reservar para no mezclar contadores si
     *                    cambia mientras se comprueba la contraseña)
     * @param reservation reserva en el contador correspondiente
     */
    public record LoginAttempt(String key, String ip, boolean knownIp, Reservation reservation) {

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
