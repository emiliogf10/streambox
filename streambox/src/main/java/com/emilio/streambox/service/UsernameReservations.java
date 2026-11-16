package com.emilio.streambox.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.repository.UsernameReservationRepository;

/**
 * Nombres de usuario <b>reservados</b> por el registro: sobre todo por uno que
 * no creó ninguna cuenta porque el email ya tenía una (para eso existen) y,
 * desde la incidencia de la carrera del nombre, por <b>todo</b> registro que
 * responde 202 (ver «Concurrencia»).
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
 * <h2>Por qué se guarda en la base de datos (incidencia NV-2)</h2>
 * <p>
 * La garantía depende de que la reserva dure <b>exactamente</b> lo mismo que
 * el nombre de la cuenta pendiente con la que se compara, y esa cuenta vive en
 * la base de datos. Antes la reserva vivía en memoria (dos contadores como los
 * del login) y había dos formas de perderla antes de tiempo: un reinicio
 * (despliegue o caída) y el tope de claves de los contadores
 * ({@code streambox.security.rate-limit.max-keys}), que al llenarse olvidaba
 * la reserva más antigua. El atacante podía provocar lo segundo con registros
 * de relleno y detectar lo primero con un nombre «canario»; perdida la
 * reserva, el registro (202 frente a 409) y «Editar perfil» (200 frente a
 * 409) volvían a delatar los emails sondeados en las 24 h anteriores. Ahora
 * está en {@link UsernameReservationRepository} (tablas de la {@code V13}):
 * sobrevive a los reinicios, la comparten todas las réplicas y no tiene tope.
 * La limpieza de las caducadas la hace {@link UsernameReservationCleanupConfig};
 * una caducada no cuenta aunque siga en la tabla.
 * </p>
 *
 * <h2>Concurrencia: la fila del nombre es su cerrojo</h2>
 * <p>
 * El nombre de una cuenta pendiente lo protege el {@code UNIQUE} de
 * {@code users.username}; el de una reserva, otra tabla. Nada en la base de
 * datos los excluía mutuamente y una reserva sin confirmar es invisible para
 * las demás transacciones. Así, con un registro {@code (N, V)} y, a la vez,
 * otro {@code (N, Z)} con un email nuevo o un {@code PATCH} a {@code N} desde
 * una cuenta propia, los dos salían bien («202 y 202», «202 y 200») solo si
 * {@code V} tenía cuenta (si no, el {@code UNIQUE} frenaba a uno): otra vez el
 * oráculo de emails.
 * </p>
 * <p>
 * Ahora todo lo que puede dar un nombre a alguien empieza <b>reclamando la
 * fila de ese nombre</b> en {@code username_reservations}, antes de leer
 * nada: el registro con {@link #reserve} (en todos sus caminos, también el que
 * crea la cuenta) y «Editar perfil» con {@link #lockForRename}. La fila queda
 * bloqueada hasta el final de la transacción, así que dos peticiones sobre el
 * mismo nombre van una detrás de otra y la segunda decide con lo que confirmó
 * la primera (la reserva o la cuenta pendiente): el resultado es siempre el
 * de alguno de los dos órdenes posibles, nunca una mezcla.
 * </p>
 * <p>
 * <b>Sin interbloqueos nuevos:</b> cada transacción reclama <b>un solo</b>
 * nombre y lo hace <b>antes</b> de bloquear ninguna fila de {@code users}
 * (el registro las bloquea después, en orden de id). Quien espera por un
 * nombre no retiene nada que otro necesite, así que no puede formar parte de
 * un ciclo de esperas.
 * </p>
 * <p>
 * <b>La cuenta pendiente nueva también reserva su nombre</b> (dueño: su
 * email). Es la misma fila que sirvió de cerrojo, y dura <b>exactamente</b> lo
 * mismo que el nombre de la pendiente: el registro toma un solo instante al
 * principio de su transacción y lo usa para la reserva
 * ({@link #reserve(String, String, Instant)}) y como {@code users.created_at}
 * de la cuenta, así que la reserva caduca en el mismo instante en que la
 * pendiente libera su nombre ({@link EmailVerificationService#releasesUsername}).
 * Antes, {@code created_at} lo ponía {@code @CreationTimestamp} unos
 * milisegundos después (y con la hora del sistema): con un email nuevo el
 * nombre quedaba ocupado un poco más que con uno registrado (incidencia
 * BAJA-A). Efecto: el registro deja el mismo rastro, y durante el mismo tiempo,
 * tenga o no cuenta el email. «Editar perfil», en cambio, no deja ninguna
 * reserva ({@link #releaseRenameLock}).
 * </p>
 * <p>
 * <b>El registro no suelta el nombre mientras decide</b> (incidencia
 * MEDIA-A): si la cuenta que mira cambia, o pierde la carrera por el email
 * contra otro registro, repite su decisión volviendo a un punto de guardado
 * tomado después de reclamar el nombre ({@link TransactionSavepoints}), sin
 * deshacer la transacción. Si la deshiciera, quien esperaba por el nombre
 * entraría en medio, y eso solo pasaba cuando el email no tenía cuenta
 * verificada.
 * </p>
 *
 * <h2>Solo el hash del email</h2>
 * <p>
 * Del email solo se guarda su SHA-256 ({@link UsernameReservationRepository#emailHash}):
 * basta para la única pregunta que se le hace («¿es este el email de la
 * reserva?») y la tabla no acumula en claro los emails que se han probado,
 * que serían justo la lista de emails ajenos que alguien quiso sondear.
 * </p>
 *
 * <h2>Transacciones</h2>
 * <p>
 * El repositorio usa la transacción de quien llama: una reserva hecha dentro
 * del registro se confirma o se deshace con él (si el registro falla, no queda
 * una reserva de una petición que no respondió 202). Fuera de una transacción,
 * {@link #reserve} abre la suya (la aplicación ya no lo hace: el registro
 * siempre reserva dentro de la suya). «Ahora» sale siempre del
 * {@link Clock} de la aplicación, nunca de la base de datos.
 * </p>
 */
@Component
public class UsernameReservations {

    private final UsernameReservationRepository repository;

    private final Duration ttl;

    private final Clock clock;

    /**
     * Crea las reservas.
     *
     * @param repository      dónde se guardan (tablas de la {@code V13})
     * @param emailProperties vida de la reserva ({@code verification-ttl})
     * @param clock           reloj de la aplicación
     */
    public UsernameReservations(UsernameReservationRepository repository, EmailProperties emailProperties,
            Clock clock) {
        this.repository = repository;
        this.ttl = emailProperties.verificationTtl();
        this.clock = clock;
    }

    /**
     * Reserva un nombre a nombre de un email. Si ya estaba reservado, no lo
     * alarga (ver «Cuánto dura»): solo añade el email como dueño. Deja el
     * nombre bloqueado hasta el final de la transacción (ver «Concurrencia»):
     * el registro lo llama <b>lo primero</b>.
     *
     * @param username nombre normalizado
     * @param email    email normalizado del registro
     * @return {@code true} si no había ninguna reserva vigente (la ha creado
     *         esta llamada); {@code false} si ya la había, de este email o de
     *         otro
     */
    public boolean reserve(String username, String email) {
        return reserve(username, email, now());
    }

    /**
     * Igual que {@link #reserve(String, String)}, pero con el instante que da
     * quien llama: el registro usa el mismo para la reserva y para la fecha de
     * alta de la cuenta que crea, de modo que las dos liberan el nombre en el
     * mismo instante (ver «Concurrencia», incidencia BAJA-A).
     *
     * @param username nombre normalizado
     * @param email    email normalizado del registro
     * @param now      «ahora», del {@link Clock} de la aplicación
     * @return {@code true} si no había ninguna reserva vigente (la ha creado
     *         esta llamada); {@code false} si ya la había
     */
    public boolean reserve(String username, String email, Instant now) {
        return repository.reserve(username, email, now, ttl);
    }

    /**
     * Bloquea el nombre hasta el final de la transacción <b>sin reservarlo</b>,
     * para «Editar perfil» (ver «Concurrencia»). Lo primero que hace el cambio
     * de nombre, antes de mirar quién tiene el nombre.
     *
     * <p>
     * Si devuelve {@code true}, quien llama debe llamar a
     * {@link #releaseRenameLock} antes de confirmar: si no, la fila que hace
     * de cerrojo quedaría como una reserva sin dueños y el nombre, bloqueado
     * 24 h para todos. Si la transacción se deshace, la fila se va con ella.
     * </p>
     *
     * @param username nombre normalizado
     * @return {@code true} si el nombre no tenía ninguna reserva vigente (y se
     *         ha creado la fila del cerrojo); {@code false} si la tenía (ahora
     *         bloqueada; para saber si es de otro, {@link #isReservedForOthers})
     */
    public boolean lockForRename(String username) {
        return repository.lock(username, now(), ttl);
    }

    /**
     * Quita la fila que creó {@link #lockForRename} para que el cambio de nombre
     * no deje ninguna reserva: si la cuenta vuelve a cambiarse el nombre, el
     * anterior queda libre para los demás en el acto, como siempre. El bloqueo
     * dura igualmente hasta el final de la transacción.
     *
     * @param username nombre normalizado (el mismo de {@link #lockForRename})
     */
    public void releaseRenameLock(String username) {
        repository.deleteLock(username);
    }

    /**
     * Indica si el nombre está reservado (para cualquier email).
     *
     * <p>
     * El registro ya no lo usa para decidir: una lectura así no ve la reserva
     * sin confirmar de una transacción simultánea (ver «Concurrencia»), así
     * que el registro decide con lo que devuelve {@link #reserve}, que reclama
     * la fila antes. Queda como consulta de solo lectura (tests y
     * diagnóstico); no lo uses para dar un nombre por libre.
     * </p>
     *
     * @param username nombre normalizado
     * @return {@code true} si hay una reserva vigente
     */
    public boolean isReserved(String username) {
        return repository.isReserved(username, now());
    }

    /**
     * Indica si el nombre está reservado para alguien que no es la cuenta de
     * ese email (para «Editar perfil»).
     *
     * <p>
     * Llámalo <b>después</b> de {@link #lockForRename}: con la fila ya
     * bloqueada, lo que lee es lo que confirmó el último que la tuvo. Antes
     * del bloqueo no vería la reserva de un registro simultáneo sin confirmar.
     * </p>
     *
     * @param username nombre normalizado
     * @param email    email de la cuenta que quiere usar el nombre
     * @return {@code true} si hay una reserva vigente y no la hizo ese email
     */
    public boolean isReservedForOthers(String username, String email) {
        return repository.isReservedForOthers(username, email, now());
    }

    /**
     * Borra las reservas caducadas (para la limpieza programada,
     * {@link UsernameReservationCleanupConfig}). No cambia ninguna respuesta:
     * una caducada ya no cuenta; solo evita que la tabla crezca para siempre
     * con nombres que nadie vuelve a pedir.
     *
     * @return número de reservas borradas
     */
    public int deleteExpired() {
        return repository.deleteExpired(now());
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
