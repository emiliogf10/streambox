package com.emilio.streambox.service;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.security.ratelimit.RateLimitProperties;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounter;

/**
 * Nombres de usuario <b>reservados</b> por un registro que no creó ninguna
 * cuenta porque el email ya tenía una.
 *
 * <h2>El problema que resuelve (oráculo del nombre)</h2>
 * <p>
 * El registro responde el mismo 202 exista o no el email, pero sus efectos
 * eran distintos: con un email nuevo se crea una cuenta pendiente y su nombre
 * {@code U} queda ocupado (24 h, lo que dura el enlace); con un email que ya
 * tenía cuenta no se crea nada y {@code U} seguía libre. Un segundo registro
 * con {@code U} respondía 409 en el primer caso y 202 en el segundo: bastaban
 * dos peticiones para saber si un email tenía cuenta. Con esta reserva, el
 * camino «el email ya existe» deja {@code U} igual de ocupado y el segundo
 * registro da el mismo 409 «El nombre de usuario ya está en uso».
 * </p>
 *
 * <h2>Cuánto dura</h2>
 * <p>
 * {@code streambox.email.verification-ttl} (24 h) desde el <b>primer</b>
 * registro que la creó, igual que el nombre de una cuenta pendiente nueva:
 * pasadas 24 h desde su alta, la cuenta sigue en la base de datos, pero su
 * nombre queda libre tanto para otro registro como para «Editar perfil» (los
 * dos la borran al pedir ese nombre, con la misma regla:
 * {@link EmailVerificationService#deleteIfUsernameReleased}), aunque haya pedido
 * otro enlace después (incidencia MEDIA-1). Si solo la borrara el
 * registro, pasadas 24 h «Editar perfil» daría 409 con el nombre de un
 * registro con email nuevo y 200 con el de uno cuya reserva ya caducó: el
 * mismo oráculo. Repetir el registro no alarga la reserva: en el caso del
 * email nuevo, repetirlo con el mismo nombre da 409 y tampoco alarga nada.
 * </p>
 *
 * <h2>El dueño del email no se ve afectado</h2>
 * <p>
 * La reserva recuerda también el email con el que se hizo. La cuenta que ya
 * tenía ese email puede cambiar su nombre a {@code U} en «Editar perfil»
 * ({@link #isReservedForOthers}); el resto de cuentas recibe el 409, porque si
 * no, cambiarse el nombre a {@code U} tras registrarse con el email de otro
 * sería el mismo oráculo con una cuenta propia. El registro no tiene esa
 * excepción: repetir el registro con el mismo email y el mismo nombre da 409
 * también en el caso del email nuevo.
 * </p>
 *
 * <h2>Limitaciones conocidas</h2>
 * <ul>
 *   <li><b>En memoria</b>, como los contadores del login: se pierde al
 *       reiniciar (las cuentas pendientes, en cambio, siguen en la base de
 *       datos) y cada réplica tendría la suya. El atacante no controla los
 *       reinicios, así que no puede aprovecharlo a voluntad.</li>
 *   <li><b>Tope de claves</b> ({@code streambox.security.rate-limit.max-keys}):
 *       al llenarse se olvida la reserva más antigua. Llenarlo exige ese
 *       número de registros (con BCrypt y el límite por IP de cada uno).</li>
 * </ul>
 *
 * <p>
 * Antes había una tercera: un reenvío del enlace (o uno de «He olvidado mi
 * contraseña») alargaba la vida del nombre de una pendiente nueva, pero no la
 * de una reserva, y pedirlo pasadas 24 h distinguía los dos casos (MEDIA-1).
 * Ya no: el nombre de la pendiente caduca a las 24 h de su alta, pida los
 * enlaces que pida ({@link EmailVerificationService#releasesUsername}).
 * </p>
 */
@Component
public class UsernameReservations {

    /**
     * Separador entre nombre y email en las claves de {@link #byNameAndEmail}.
     * El NUL no puede aparecer en ninguno de los dos: el nombre se normaliza
     * convirtiendo los caracteres de control en espacios y el email los
     * rechaza en la validación.
     */
    private static final char SEPARATOR = '\u0000';

    /** Nombres reservados (un evento por nombre durante la vida de la reserva). */
    private final SlidingWindowCounter byName;

    /** Pares nombre + email que hicieron la reserva (para la excepción del dueño). */
    private final SlidingWindowCounter byNameAndEmail;

    /**
     * Crea las reservas.
     *
     * @param emailProperties     vida de la reserva ({@code verification-ttl})
     * @param rateLimitProperties tope de claves de los contadores en memoria
     * @param clock               reloj de la aplicación
     */
    public UsernameReservations(EmailProperties emailProperties, RateLimitProperties rateLimitProperties,
            Clock clock) {
        this.byName = new SlidingWindowCounter(emailProperties.verificationTtl(), clock,
                rateLimitProperties.maxKeys());
        this.byNameAndEmail = new SlidingWindowCounter(emailProperties.verificationTtl(), clock,
                rateLimitProperties.maxKeys());
    }

    /**
     * Reserva un nombre a nombre de un email. Si ya estaba reservado, no lo
     * alarga (ver «Cuánto dura»).
     *
     * @param username nombre normalizado
     * @param email    email normalizado del registro
     */
    public void reserve(String username, String email) {
        // tryAcquire con máximo 1: registra el evento solo si no había uno,
        // así la reserva caduca a las 24 h del primero y la cola no crece.
        byName.tryAcquire(username, 1);
        byNameAndEmail.tryAcquire(key(username, email), 1);
    }

    /**
     * Indica si el nombre está reservado (para el registro, sin excepciones).
     *
     * @param username nombre normalizado
     * @return {@code true} si hay una reserva vigente
     */
    public boolean isReserved(String username) {
        return byName.count(username) > 0;
    }

    /**
     * Indica si el nombre está reservado para alguien que no es la cuenta de
     * ese email (para «Editar perfil»).
     *
     * @param username nombre normalizado
     * @param email    email de la cuenta que quiere usar el nombre
     * @return {@code true} si hay una reserva vigente y no la hizo ese email
     */
    public boolean isReservedForOthers(String username, String email) {
        return isReserved(username) && byNameAndEmail.count(key(username, email)) == 0;
    }

    private static String key(String username, String email) {
        return username + SEPARATOR + email;
    }
}
