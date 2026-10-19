package com.emilio.streambox.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.email.PublicUrlProperties;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.RateLimitProperties;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounter;

/**
 * Registro de cuentas nuevas ({@code POST /api/users}) <b>sin revelar qué
 * emails tienen cuenta</b>.
 *
 * <h2>El problema que resuelve</h2>
 * <p>
 * Antes, el registro respondía 409 «El correo electrónico ya está en uso»: con
 * eso cualquiera podía comprobar si un email tenía cuenta en StreamBox. Y como
 * no había verificación, cualquiera podía ocupar el email de otra persona.
 * Ahora el registro responde <b>siempre</b> 202 con el mismo cuerpo
 * ({@link AcceptedResponse#REGISTRATION}), y lo que cambia según el caso es el
 * correo que recibe el buzón, que solo lee su dueño.
 * </p>
 *
 * <h2>Qué pasa en cada caso</h2>
 * <ul>
 *   <li><b>Email nuevo</b>: cuenta {@code USER} pendiente + token + correo con
 *       el enlace de verificación.</li>
 *   <li><b>Email de una cuenta verificada</b>: correo «Ya tienes una cuenta»
 *       (sin enlace) y ningún cambio. La contraseña enviada se descarta.</li>
 *   <li><b>Email de una cuenta pendiente</b>: se reenvía el enlace (con el
 *       límite silencioso de {@link EmailVerificationService#issueIfAllowed}).
 *       Solo si la creó el registro con verificación y todos sus enlaces han
 *       caducado ({@link EmailVerificationService#isReplaceable}), se
 *       <b>sustituye</b> por la nueva: quien la creó nunca demostró que el
 *       email era suyo. Una cuenta heredada (USER anterior a la V9, que puede
 *       tener favoritos y años de uso) <b>nunca</b> se borra.</li>
 *   <li><b>Nombre de usuario ocupado</b>: 409 «El nombre de usuario ya está en
 *       uso», como siempre (el nombre no es secreto: se ve en la app), salvo
 *       que sea de una cuenta sustituible, que se borra y lo libera (igual
 *       que en «Editar perfil»). El 409
 *       depende <b>solo del nombre</b>, nunca del email: si el registro con el
 *       nombre de alguien respondiera distinto según el email enviado, se
 *       podría adivinar el email de cada nombre.</li>
 *   <li><b>Nombre reservado</b> ({@link UsernameReservations}): cuando el email
 *       ya tenía cuenta no se crea ninguna, pero el nombre enviado queda
 *       reservado lo mismo que si se hubiera creado (la vida del enlace) y da
 *       el mismo 409. Sin la reserva, repetir el registro con ese nombre daba
 *       409 si el email era nuevo y 202 si ya tenía cuenta: un oráculo de
 *       emails registrados en dos peticiones.</li>
 * </ul>
 *
 * <h2>Mismo coste en todos los caminos</h2>
 * <p>
 * La contraseña se cifra con BCrypt (unos 100 ms) <b>siempre</b>, antes de
 * mirar la base de datos, aunque el email ya exista y el hash no se use: si
 * solo se cifrara al crear la cuenta, el tiempo de respuesta delataría los
 * emails registrados. Por lo mismo, los correos salen después del commit y en
 * otro hilo ({@link EmailDispatcher}). Va fuera de la transacción, para no
 * tener una conexión del pool ocupada mientras tanto.
 * </p>
 *
 * <h2>Concurrencia</h2>
 * <p>
 * Las filas implicadas (dueño del email y dueño del nombre) se bloquean en
 * orden de id ({@code PESSIMISTIC_WRITE}): dos registros a la vez sobre las
 * mismas cuentas esperan uno al otro sin poder bloquearse mutuamente. Si dos
 * registros con un email o un nombre <em>nuevos</em> llegan a la vez (no hay
 * fila que bloquear), el {@code UNIQUE} de la base de datos frena al segundo y
 * {@link #translateRace} lo traduce: por el email, el mismo 202 (la otra
 * petición ya envió su correo) y el nombre queda reservado, como en cualquier
 * registro con un email que ya tiene cuenta; por el nombre, el 409 de siempre.
 * </p>
 * <p>
 * Si una de esas filas <b>desaparece o cambia de nombre</b> mientras se espera
 * su bloqueo (otro registro la sustituyó), la transacción se deshace y se
 * repite <b>una vez</b> desde el principio con el estado nuevo
 * ({@link #MAX_ATTEMPTS}). Antes, el bloqueo con {@code refresh} lanzaba
 * {@code EntityNotFoundException} y el registro respondía 500.
 * </p>
 *
 * <h2>Riesgo conocido: cuentas creadas con el email de otro</h2>
 * <p>
 * Cualquiera puede registrar el email de otra persona con una contraseña
 * suya. La cuenta queda pendiente y no se puede usar, pero si la víctima pulsa
 * el enlace del correo (que es legítimo) la verifica y el atacante entra con
 * su contraseña (<i>pre-account hijacking</i>). Y mientras el atacante pida
 * reenvíos, el enlace no caduca y la víctima no puede registrarse con su
 * email (su registro solo le reenvía el enlace de esa cuenta). Mitigación
 * actual: el correo de verificación dice el <b>nombre de usuario</b> de la
 * cuenta y pide no abrir el enlace si no lo reconoce
 * ({@code AccountEmails#verification}). La solución completa llegará con «He
 * olvidado mi contraseña» (anotada en {@code docs/PLAN_DE_ACCION.md}): el
 * dueño del buzón podrá fijar su propia contraseña, lo que deja fuera al
 * atacante.
 * </p>
 */
@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final EmailDispatcher emailDispatcher;
    private final PublicUrlProperties publicUrl;
    private final EmailProperties.Resend noticeLimits;
    private final TransactionTemplate transactionTemplate;
    private final UsernameReservations usernameReservations;

    /**
     * Intentos de la transacción del registro: el primero y un reintento si
     * una fila cambió mientras se esperaba su bloqueo (ver «Concurrencia»).
     * Que falle dos veces seguidas exige dos registros simultáneos que
     * sustituyan las mismas cuentas a la vez: es un 500 legítimo.
     */
    static final int MAX_ATTEMPTS = 2;

    /**
     * Límite silencioso de avisos «Ya tienes una cuenta» por email: como mucho
     * uno cada {@code min-interval}. En memoria (estas cuentas no tienen tokens
     * con los que contar); se pierde al reiniciar, lo que solo permite algún
     * aviso de más.
     */
    private final SlidingWindowCounter noticesPerInterval;

    /** Igual, como mucho {@code max-per-window} avisos por {@code window}. */
    private final SlidingWindowCounter noticesPerWindow;

    /**
     * Crea el servicio.
     *
     * @param userRepository           cuentas
     * @param passwordEncoder          BCrypt
     * @param emailVerificationService tokens y correos de verificación
     * @param emailDispatcher          envía los correos tras el commit
     * @param emailProperties          límites de correos por cuenta
     * @param publicUrl                URL pública (enlace del aviso)
     * @param rateLimitProperties      tope de claves de los contadores en memoria
     * @param clock                    reloj de la aplicación
     * @param transactionManager       gestor de transacciones
     * @param usernameReservations     nombres reservados por registros sin cuenta nueva
     */
    public RegistrationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            EmailDispatcher emailDispatcher,
            EmailProperties emailProperties,
            PublicUrlProperties publicUrl,
            RateLimitProperties rateLimitProperties,
            Clock clock,
            PlatformTransactionManager transactionManager,
            UsernameReservations usernameReservations) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.emailDispatcher = emailDispatcher;
        this.publicUrl = publicUrl;
        this.noticeLimits = emailProperties.resend();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.usernameReservations = usernameReservations;
        this.noticesPerWindow = new SlidingWindowCounter(noticeLimits.window(), clock,
                rateLimitProperties.maxKeys());
        this.noticesPerInterval = noticeLimits.minInterval().isZero() ? null
                : new SlidingWindowCounter(noticeLimits.minInterval(), clock, rateLimitProperties.maxKeys());
    }

    /**
     * Registra una cuenta (o, si el email ya tenía una, avisa a su buzón) y
     * responde siempre lo mismo.
     *
     * <p>
     * El email se normaliza (sin espacios y en minúsculas) y el nombre con
     * {@link UserService#normalizeUsername}, como antes. El rol es siempre
     * {@link Role#USER}: los administradores solo se crean con
     * {@code AdminAccountInitializer}.
     * </p>
     *
     * @param request datos de registro (ya validados, contraseña incluida)
     * @return la respuesta 202 fija
     * @throws UserAlreadyExistsException si el nombre de usuario es de otra
     *                                    cuenta que no se puede sustituir, o
     *                                    está reservado (409)
     * @throws InvalidParameterException  si el nombre o el email normalizados no
     *                                    tienen una longitud válida (400)
     * @throws IllegalStateException      si las cuentas implicadas cambian
     *                                    mientras se bloquean en los
     *                                    {@value #MAX_ATTEMPTS} intentos (500)
     */
    public AcceptedResponse register(CreateUserRequest request) {

        String email = UserService.normalizeEmail(request.getEmail());
        String username = UserService.normalizeUsername(request.getUsername());
        // Siempre, exista o no el email (ver «Mismo coste» en la clase).
        String passwordHash = passwordEncoder.encode(request.getPassword());

        for (int attempt = 1;; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(
                        status -> registerInTransaction(email, username, passwordHash));
                return AcceptedResponse.REGISTRATION;
            } catch (AccountChangedWhileLockingException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("El registro ha encontrado cuentas cambiando en "
                            + MAX_ATTEMPTS + " intentos seguidos", e);
                }
                // Se repite con el estado nuevo (la transacción ya se deshizo).
            } catch (DataIntegrityViolationException e) {
                translateRace(e);
                // Perdió la carrera por el email: nadie creó una cuenta con
                // este nombre, pero debe quedar ocupado como en cualquier
                // registro con un email que ya tiene cuenta (ver la clase).
                usernameReservations.reserve(username, email);
                return AcceptedResponse.REGISTRATION;
            }
        }
    }

    /**
     * Parte transaccional del registro (ver los casos en la clase).
     *
     * @throws AccountChangedWhileLockingException si una de las cuentas
     *         implicadas desapareció o cambió mientras se esperaba su bloqueo
     */
    private void registerInTransaction(String email, String username, String passwordHash) {

        Locked locked = lockInIdOrder(userRepository.findByEmail(email), userRepository.findByUsername(username),
                email, username);
        Optional<User> owner = locked.owner();
        Optional<User> holder = locked.holder();

        if (holder.isPresent()) {
            freeUsernameOrFail(holder.get());
            if (owner.isPresent() && owner.get().getId().equals(holder.get().getId())) {
                // Era la misma cuenta (pendiente y caducada): ya está borrada.
                owner = Optional.empty();
            }
        }
        if (usernameReservations.isReserved(username)) {
            // Mismo 409 que si el nombre lo tuviera una cuenta (ver la clase).
            // Si se acaba de borrar una sustituible, el 409 deshace el borrado.
            throw new UserAlreadyExistsException(UserService.USERNAME_TAKEN_MESSAGE);
        }

        if (owner.isPresent()) {
            User existing = owner.get();
            // En los tres caminos que no crean cuenta, el nombre queda tan
            // ocupado como si se hubiera creado (oráculo del nombre, ver la clase).
            if (existing.isEmailVerified()) {
                usernameReservations.reserve(username, email);
                notifyAlreadyRegistered(email);
                return;
            }
            if (!emailVerificationService.isReplaceable(existing)) {
                // Pendiente con su enlace vigente, o heredada: se le reenvía el
                // enlace (con el límite silencioso) y no se toca nada más.
                usernameReservations.reserve(username, email);
                emailVerificationService.issueIfAllowed(existing);
                return;
            }
            if (userRepository.deleteIfEmailNotVerified(existing.getId()) != 1) {
                // No debería pasar con la fila bloqueada, pero si se verificó
                // entre medias, es una cuenta existente y confirmada.
                usernameReservations.reserve(username, email);
                notifyAlreadyRegistered(email);
                return;
            }
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPassword(passwordHash);
        user.setRole(Role.USER);
        // emailVerifiedAt queda a null: la cuenta nace pendiente.
        User saved = userRepository.saveAndFlush(user);
        emailVerificationService.issueForNewAccount(saved);
    }

    /**
     * El nombre de usuario lo tiene otra cuenta (o la del propio email): si es
     * sustituible se borra y el nombre queda libre; si no, 409. La regla es la
     * misma que en «Editar perfil» ({@link EmailVerificationService#deleteIfReplaceable}).
     */
    private void freeUsernameOrFail(User holder) {
        if (!emailVerificationService.deleteIfReplaceable(holder)) {
            throw new UserAlreadyExistsException(UserService.USERNAME_TAKEN_MESSAGE);
        }
    }

    /**
     * Bloquea las filas encontradas, de menor a mayor id para que dos
     * registros cruzados no se bloqueen mutuamente (interbloqueo), y devuelve
     * su estado releído bajo el bloqueo.
     *
     * <p>
     * Si una ya no existe, o ya no tiene el email o el nombre por el que se
     * encontró (el email no cambia nunca; el nombre, con «Editar perfil»), se
     * lanza {@link AccountChangedWhileLockingException} para repetir el
     * registro: decidir con la fila de antes podría borrar o rechazar con
     * datos que ya no son ciertos.
     * </p>
     */
    private Locked lockInIdOrder(Optional<User> owner, Optional<User> holder, String email, String username) {

        Map<Long, User> byId = new TreeMap<>();
        owner.ifPresent(user -> byId.put(user.getId(), user));
        holder.ifPresent(user -> byId.put(user.getId(), user));
        // TreeMap: se recorren de menor a mayor id.
        Map<Long, User> fresh = new HashMap<>();
        for (User user : byId.values()) {
            fresh.put(user.getId(), emailVerificationService.lockForUpdate(user)
                    .orElseThrow(AccountChangedWhileLockingException::new));
        }
        Optional<User> lockedOwner = owner.map(user -> fresh.get(user.getId()));
        Optional<User> lockedHolder = holder.map(user -> fresh.get(user.getId()));
        if (lockedOwner.filter(user -> !email.equals(user.getEmail())).isPresent()
                || lockedHolder.filter(user -> !username.equals(user.getUsername())).isPresent()) {
            throw new AccountChangedWhileLockingException();
        }
        return new Locked(lockedOwner, lockedHolder);
    }

    /**
     * Programa el aviso «Ya tienes una cuenta», salvo que se haya superado el
     * límite silencioso de ese email.
     */
    private void notifyAlreadyRegistered(String email) {
        boolean allowed = (noticesPerInterval == null || noticesPerInterval.tryAcquire(email, 1))
                && noticesPerWindow.tryAcquire(email, noticeLimits.maxPerWindow());
        if (allowed) {
            emailDispatcher.sendAfterCommit(AccountEmails.alreadyRegistered(email, publicUrl));
        }
    }

    /**
     * Traduce la violación de unicidad de un registro que perdió la carrera
     * contra otro simultáneo (ver «Concurrencia» en la clase).
     *
     * <p>
     * La transacción ya se deshizo (y con ella cualquier correo programado),
     * así que aquí solo se decide la respuesta. Se mira el nombre de la
     * restricción ({@code uk_users_email} o {@code uk_users_username}, de la
     * {@code V1}): tras el error no se puede volver a preguntar a la base de
     * datos en esa transacción.
     * </p>
     *
     * <ul>
     *   <li>Email: no se lanza nada (la petición responde el 202 de siempre). La
     *       otra petición creó la cuenta y envió su correo; un 409 aquí
     *       revelaría que el email acaba de registrarse.</li>
     *   <li>Nombre: el 409 de siempre.</li>
     *   <li>Cualquier otra violación, o una de unicidad sin nombre de
     *       restricción (no pasa con PostgreSQL ni con H2), se relanza: 500 y
     *       registro en el log. Disfrazarla de «nombre en uso» podría ser falso
     *       y de 202 dejaría al usuario esperando un correo que no llega.</li>
     * </ul>
     *
     * @param error excepción del {@code INSERT}
     * @throws UserAlreadyExistsException si la violación es la del nombre
     * @throws DataIntegrityViolationException si no es de unicidad o no se sabe de cuál
     */
    static void translateRace(DataIntegrityViolationException error) {

        if (!(error instanceof DuplicateKeyException)
                && !SqlStates.UNIQUE_VIOLATION.equals(SqlStates.find(error))) {
            throw error;
        }
        String constraint = SqlStates.constraintName(error);
        if (constraint != null && constraint.contains("uk_users_email")) {
            return;
        }
        if (constraint != null && constraint.contains("uk_users_username")) {
            throw new UserAlreadyExistsException(UserService.USERNAME_TAKEN_MESSAGE);
        }
        throw error;
    }

    /**
     * Dueño del email y dueño del nombre, ya bloqueados y releídos.
     *
     * @param owner  cuenta con el email del registro, si existe
     * @param holder cuenta con el nombre del registro, si existe
     */
    private record Locked(Optional<User> owner, Optional<User> holder) {
    }

    /**
     * Una cuenta del registro desapareció o cambió mientras se esperaba su
     * bloqueo: deshace la transacción para repetir el registro (ver
     * «Concurrencia» en la clase). Nunca sale de {@link #register}.
     */
    static final class AccountChangedWhileLockingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AccountChangedWhileLockingException() {
            // Sin mensaje ni traza: es control de flujo, no un error.
            super(null, null, false, false);
        }
    }
}
