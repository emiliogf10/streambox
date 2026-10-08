package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/**
 * «IP conocida» del bloqueo de cuentas (hallazgo NV-2 de la auditoría): el
 * bloqueo por email ya no puede mantenerse indefinidamente contra el titular.
 *
 * <p>
 * Antes, un anónimo con 5 peticiones cada 15 minutos (unas 20 por hora) dejaba
 * bloqueada una cuenta de forma indefinida: el bloqueo rechaza el login
 * aunque la contraseña sea correcta, y los fallos del atacante renuevan la
 * ventana (reproducido con 24 h simuladas: 288 de 288 intentos del titular
 * rechazados). Con los valores de producción (5 fallos / 15 minutos) y un
 * reloj controlado, estos tests fijan el contrato nuevo y fallan con el
 * servicio anterior.
 * </p>
 */
class LoginAttemptServiceKnownIpTest {

    private static final String OWNER = "admin@example.test";
    private static final String OTHER_ACCOUNT = "otro@example.test";

    /** IP del titular (la primera con la que inicia sesión). */
    private static final String HOME = "203.0.113.10";
    /** Segunda IP del titular. */
    private static final String OFFICE = "203.0.113.11";
    /** IP del atacante: nunca inicia sesión con éxito. */
    private static final String ATTACKER = "198.51.100.66";
    /** IP del titular que nunca ha usado antes (otro dispositivo, otra red). */
    private static final String NEW_NETWORK = "192.0.2.77";

    private final MutableClock clock = new MutableClock();

    /** Valores de producción: 5 fallos / 15 min, 5 IPs por cuenta, 30 días. */
    private LoginAttemptService service = service(100_000, 5);

    private LoginAttemptService service(int maxKeys, int maxKnownIps) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule(100, Duration.ofMinutes(1));
        return new LoginAttemptService(
                new RateLimitProperties(rule, rule,
                        new RateLimitProperties.Lockout(5, Duration.ofMinutes(15), maxKnownIps, Duration.ofDays(30)),
                        maxKeys),
                clock);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Login correcto del titular desde la IP: la «conoce». */
    private void ownerLogsIn(String ip) {
        service.recordSuccess(service.reserveAttempt(OWNER, ip));
    }

    /** Un intento fallido; devuelve los intentos restantes (lanza si bloquea). */
    private int fail(String email, String ip) {
        return service.recordFailure(service.reserveAttempt(email, ip));
    }

    /** Un fallo del atacante, ignorando el 429 del que agota los intentos. */
    private void attackerFailure() {
        try {
            fail(OWNER, ATTACKER);
        } catch (AccountLockedException expectedOnFifth) {
            // El quinto fallo devuelve el 429: la cuenta queda bloqueada para esa IP.
        }
    }

    private boolean canReserve(String email, String ip) {
        try {
            service.reserveAttempt(email, ip);
            return true;
        } catch (AccountLockedException locked) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // El ataque de la auditoría
    // ------------------------------------------------------------------

    /**
     * Reproduce el ataque de la auditoría (5 fallos cada 15 min 1 s durante 24
     * horas simuladas, con sondeos del titular entre medias): el titular entra
     * desde su IP conocida en TODOS los sondeos, y desde una IP nueva sigue
     * bloqueado (el bloqueo sigue protegiendo de la fuerza bruta).
     */
    @Test
    void conUnAtaqueSostenidoElTitularSigueEntrandoDesdeSuIpConocida() {
        ownerLogsIn(HOME);

        int probesFromKnownIp = 0;
        int enteredFromKnownIp = 0;
        int probesFromNewIp = 0;
        int blockedFromNewIp = 0;
        Duration elapsed = Duration.ZERO;

        while (elapsed.compareTo(Duration.ofHours(24)) < 0) {
            for (int i = 0; i < 5; i++) {
                attackerFailure();
            }
            for (Duration offset : new Duration[] {Duration.ofMinutes(1), Duration.ofMinutes(6), Duration.ofMinutes(7)}) {
                clock.advance(offset);
                elapsed = elapsed.plus(offset);

                probesFromKnownIp++;
                if (canReserve(OWNER, HOME)) {
                    enteredFromKnownIp++;
                    ownerLogsIn(HOME);
                }
                probesFromNewIp++;
                if (!canReserve(OWNER, NEW_NETWORK)) {
                    blockedFromNewIp++;
                }
            }
            Duration rest = Duration.ofMinutes(1).plusSeconds(1);
            clock.advance(rest);
            elapsed = elapsed.plus(rest);
        }

        assertEquals(probesFromKnownIp, enteredFromKnownIp, "El titular debe entrar siempre desde su IP conocida");
        assertEquals(probesFromNewIp, blockedFromNewIp, "Una IP nueva sigue bloqueada mientras dure el ataque");
        assertDoesNotThrow(() -> service.reserveAttempt(OTHER_ACCOUNT, ATTACKER),
                "Otra cuenta no se ve afectada");
    }

    // ------------------------------------------------------------------
    // Contadores independientes
    // ------------------------------------------------------------------

    /** Los fallos de una IP desconocida no afectan a las IPs conocidas. */
    @Test
    void losFallosDeUnaIpDesconocidaNoAfectanALasIpsConocidas() {
        ownerLogsIn(HOME);
        ownerLogsIn(OFFICE);

        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }

        assertEquals(false, canReserve(OWNER, ATTACKER), "El contador de la cuenta está agotado");
        assertEquals(false, canReserve(OWNER, NEW_NETWORK));
        assertEquals(4, fail(OWNER, HOME), "El titular tiene sus 5 intentos intactos");
        assertEquals(4, fail(OWNER, OFFICE), "También desde su otra IP conocida");
    }

    /**
     * Adivinar contraseñas desde una IP conocida sigue limitado a 5 fallos cada
     * 15 minutos en SU contador: el 5.º da el 429 y durante el bloqueo se
     * rechaza incluso la contraseña correcta, igual que antes.
     */
    @Test
    void losFallosDesdeUnaIpConocidaSeLimitanEnSuPropioContador() {
        ownerLogsIn(HOME);

        assertEquals(4, fail(OWNER, HOME));
        assertEquals(3, fail(OWNER, HOME));
        assertEquals(2, fail(OWNER, HOME));
        assertEquals(1, fail(OWNER, HOME));
        AccountLockedException locked = assertThrows(AccountLockedException.class, () -> fail(OWNER, HOME));
        assertEquals(Duration.ofMinutes(15), locked.getRetryAfter());

        assertThrows(AccountLockedException.class, () -> service.reserveAttempt(OWNER, HOME));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertDoesNotThrow(() -> service.reserveAttempt(OWNER, HOME));
    }

    /** Los fallos de una IP conocida no bloquean la cuenta para las demás IPs. */
    @Test
    void losFallosDeUnaIpConocidaNoBloqueanLaCuentaParaLasDemas() {
        ownerLogsIn(HOME);
        ownerLogsIn(OFFICE);

        for (int i = 0; i < 4; i++) {
            fail(OWNER, HOME);
        }
        assertThrows(AccountLockedException.class, () -> fail(OWNER, HOME));

        assertEquals(4, fail(OWNER, OFFICE), "Otra IP conocida no se ve afectada");
        assertEquals(4, fail(OWNER, NEW_NETWORK), "Una IP desconocida usa el contador de la cuenta, intacto");
    }

    /** Un login correcto reinicia el contador contra el que se reservó, y solo ese. */
    @Test
    void unExitoReiniciaSoloElContadorContraElQueSeReservo() {
        ownerLogsIn(HOME);
        fail(OWNER, ATTACKER);                       // cuenta: 1 fallo
        fail(OWNER, HOME);                           // IP conocida: 1 fallo
        fail(OWNER, HOME);                           // IP conocida: 2 fallos

        ownerLogsIn(HOME);                           // reinicia el de la IP conocida

        assertEquals(4, fail(OWNER, HOME), "El contador de la IP conocida se reinició");
        assertEquals(3, fail(OWNER, ATTACKER), "El de la cuenta no: sigue con el fallo anterior");
    }

    // ------------------------------------------------------------------
    // Cuándo una IP pasa a ser «conocida»
    // ------------------------------------------------------------------

    /**
     * Solo un login correcto «conoce» una IP. Cinco fallos desde la IP del
     * atacante la dejan bloqueada, y seguir intentándolo no la convierte en
     * conocida; reservar sin cerrar el intento tampoco.
     */
    @Test
    void unIntentoFallidoNoConoceLaIp() {
        service.reserveAttempt(OWNER, ATTACKER);     // reservado y sin cerrar
        for (int i = 0; i < 4; i++) {
            attackerFailure();
        }
        attackerFailure();

        assertEquals(false, canReserve(OWNER, ATTACKER), "Sigue sujeta al bloqueo de la cuenta");
        assertEquals(false, canReserve(OWNER, ATTACKER));
    }

    /** Un email que no existe nunca llega a {@code recordSuccess}: no tiene IPs conocidas. */
    @Test
    void unEmailInexistenteNuncaTieneIpsConocidas() {
        String ghost = "fantasma@example.test";
        for (int i = 0; i < 4; i++) {
            fail(ghost, ATTACKER);
        }

        assertThrows(AccountLockedException.class, () -> fail(ghost, ATTACKER));
        assertEquals(false, canReserve(ghost, ATTACKER));
        assertEquals(false, canReserve(ghost, HOME));
    }

    /** La IP desconocida/nula se trata como desconocida (contador de la cuenta). */
    @Test
    void sinIpSeUsaElContadorDeLaCuenta() {
        ownerLogsIn(HOME);

        assertEquals(4, fail(OWNER, null));
        service.recordSuccess(service.reserveAttempt(OWNER, null));      // no "conoce" nada

        for (int i = 0; i < 4; i++) {
            fail(OWNER, null);
        }
        assertThrows(AccountLockedException.class, () -> fail(OWNER, null));
        assertEquals(4, fail(OWNER, HOME), "La IP conocida sigue intacta");
    }

    /** Dos cuentas no comparten IPs conocidas: conocer una IP en una no la conoce en la otra. */
    @Test
    void lasIpsConocidasSonPorCuenta() {
        ownerLogsIn(HOME);

        for (int i = 0; i < 4; i++) {
            fail(OTHER_ACCOUNT, ATTACKER);
        }
        assertThrows(AccountLockedException.class, () -> fail(OTHER_ACCOUNT, ATTACKER));

        assertEquals(false, canReserve(OTHER_ACCOUNT, HOME),
                "HOME solo es conocida para el titular de OWNER, no para OTHER_ACCOUNT");
    }

    /** El email se normaliza igual que antes: mayúsculas y espacios no cambian la cuenta. */
    @Test
    void lasIpsConocidasSeAsocianAlEmailNormalizado() {
        service.recordSuccess(service.reserveAttempt("  ADMIN@Example.Test ", HOME));

        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }

        assertDoesNotThrow(() -> service.reserveAttempt(OWNER, HOME));
    }

    /** Una IPv6 dentro del mismo /64 que la conocida también lo es (direcciones temporales). */
    @Test
    void lasIpv6DelMismoPrefijo64CompartenElEstadoDeIpConocida() {
        ownerLogsIn("2001:db8:1:2:aaaa:bbbb:cccc:1");

        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }

        assertDoesNotThrow(() -> service.reserveAttempt(OWNER, "2001:db8:1:2:1111:2222:3333:4444"));
        assertEquals(false, canReserve(OWNER, "2001:db8:1:3:aaaa:bbbb:cccc:1"), "Otro /64 es otra IP");
    }

    // ------------------------------------------------------------------
    // Caducidad y expulsión
    // ------------------------------------------------------------------

    /** Una IP conocida caduca a los 30 días de su último login correcto. */
    @Test
    void laIpConocidaCaducaTrasElPlazo() {
        ownerLogsIn(HOME);
        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }
        assertDoesNotThrow(() -> service.reserveAttempt(OWNER, HOME));

        // Justo antes del plazo sigue siendo conocida (con el bloqueo de la cuenta renovado por el atacante).
        clock.advance(Duration.ofDays(30).minusMinutes(1));
        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }
        assertEquals(true, canReserve(OWNER, HOME));

        // Cumplido el plazo desde el último login correcto, vuelve a ser desconocida: bloqueada.
        clock.advance(Duration.ofMinutes(1));
        assertEquals(false, canReserve(OWNER, HOME));
    }

    /** Un login correcto dentro del plazo renueva la caducidad. */
    @Test
    void unLoginCorrectoRenuevaLaCaducidadDeLaIp() {
        ownerLogsIn(HOME);

        clock.advance(Duration.ofDays(20));
        ownerLogsIn(HOME);
        clock.advance(Duration.ofDays(20));            // 40 días desde el primero, 20 desde el segundo

        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }
        assertEquals(true, canReserve(OWNER, HOME));
    }

    /** Se recuerdan como mucho 5 IPs por cuenta: la sexta expulsa a la más antigua. */
    @Test
    void laSextaIpExpulsaALaMasAntigua() {
        for (int i = 1; i <= 5; i++) {
            ownerLogsIn("203.0.113." + i);
        }
        ownerLogsIn("203.0.113.6");                    // expulsa a la .1

        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }
        assertEquals(false, canReserve(OWNER, "203.0.113.1"), "La más antigua se olvidó");
        for (int i = 2; i <= 6; i++) {
            assertEquals(true, canReserve(OWNER, "203.0.113." + i), "203.0.113." + i + " sigue conocida");
        }
    }

    /** Volver a entrar desde una IP la hace la más reciente: no es la próxima en salir. */
    @Test
    void unLoginRecienteProtegeALaIpDeSerExpulsada() {
        for (int i = 1; i <= 5; i++) {
            ownerLogsIn("203.0.113." + i);
            clock.advance(Duration.ofMinutes(1));
        }
        ownerLogsIn("203.0.113.1");                    // la .1 pasa a ser la más reciente
        ownerLogsIn("203.0.113.6");                    // expulsa a la .2

        for (int i = 0; i < 5; i++) {
            attackerFailure();
        }
        assertEquals(true, canReserve(OWNER, "203.0.113.1"));
        assertEquals(false, canReserve(OWNER, "203.0.113.2"));
    }

    // ------------------------------------------------------------------
    // Indistinguibilidad desde una misma IP de origen
    // ------------------------------------------------------------------

    /**
     * El 429 de UNA petición no revela si la IP era conocida: el mensaje y el
     * {@code Retry-After} del bloqueo son idénticos para una IP conocida
     * (bloqueada en su contador) y una desconocida (bloqueada en el de la
     * cuenta), y también para un email que no existe. Lo que este test NO
     * cubre (ni el diseño garantiza) es cruzar dos orígenes bajo una IP
     * compartida con el titular: ver las limitaciones de
     * {@link LoginAttemptService}.
     */
    @Test
    void elBloqueoEsIndistinguibleParaIpConocidaDesconocidaYEmailInexistente() {
        ownerLogsIn(HOME);
        String ghost = "fantasma@example.test";

        AccountLockedException knownIp = lockAndCatch(OWNER, HOME);
        AccountLockedException unknownIp = lockAndCatch(OWNER, ATTACKER);
        AccountLockedException nonexistent = lockAndCatch(ghost, ATTACKER);

        assertEquals(knownIp.getMessage(), unknownIp.getMessage());
        assertEquals(knownIp.getMessage(), nonexistent.getMessage());
        assertEquals(knownIp.getRetryAfter(), unknownIp.getRetryAfter());
        assertEquals(knownIp.getRetryAfter(), nonexistent.getRetryAfter());

        AccountLockedException knownAgain = assertThrows(AccountLockedException.class,
                () -> service.reserveAttempt(OWNER, HOME));
        AccountLockedException unknownAgain = assertThrows(AccountLockedException.class,
                () -> service.reserveAttempt(OWNER, ATTACKER));
        AccountLockedException nonexistentAgain = assertThrows(AccountLockedException.class,
                () -> service.reserveAttempt(ghost, ATTACKER));
        assertEquals(knownAgain.getMessage(), unknownAgain.getMessage());
        assertEquals(knownAgain.getMessage(), nonexistentAgain.getMessage());
        assertEquals(knownAgain.getRetryAfter(), unknownAgain.getRetryAfter());
        assertEquals(knownAgain.getRetryAfter(), nonexistentAgain.getRetryAfter());
    }

    /** Los intentos restantes de los 401 son los mismos con IP conocida, desconocida y con email inexistente. */
    @Test
    void losIntentosRestantesSonIgualesParaIpConocidaDesconocidaYEmailInexistente() {
        ownerLogsIn(HOME);

        for (int expected = 4; expected >= 1; expected--) {
            assertEquals(expected, fail(OWNER, HOME));
            assertEquals(expected, fail(OWNER, ATTACKER));
            assertEquals(expected, fail("fantasma@example.test", ATTACKER));
        }
    }

    private AccountLockedException lockAndCatch(String email, String ip) {
        for (int i = 0; i < 4; i++) {
            fail(email, ip);
        }
        return assertThrows(AccountLockedException.class, () -> fail(email, ip));
    }

    // ------------------------------------------------------------------
    // Memoria acotada
    // ------------------------------------------------------------------

    /**
     * Solo hay entradas de IPs conocidas para cuentas con algún login
     * correcto: miles de fallos contra emails distintos no las hacen crecer, y
     * el número de cuentas recordadas respeta el tope aunque haya más logins
     * correctos que el tope.
     */
    @Test
    void lasIpsConocidasNoCrecenConIntentosFallidosYRespetanElTope() {
        service = service(3, 5);

        for (int i = 0; i < 2_000; i++) {
            try {
                fail("fallido" + i + "@example.test", "198.51.100." + (i % 200 + 1));
            } catch (AccountLockedException ignored) {
                // no ocurre: un fallo por email
            }
        }
        for (int i = 0; i < 10; i++) {
            LoginAttempt attempt = service.reserveAttempt("titular" + i + "@example.test", HOME);
            service.recordSuccess(attempt);
        }

        // Las tres últimas cuentas siguen conociendo la IP; las anteriores se olvidaron.
        for (int i = 0; i < 10; i++) {
            String email = "titular" + i + "@example.test";
            for (int f = 0; f < 5; f++) {
                try {
                    fail(email, ATTACKER);
                } catch (AccountLockedException ignored) {
                    // el quinto
                }
            }
            assertEquals(i >= 7, canReserve(email, HOME), email);
        }
    }
}
