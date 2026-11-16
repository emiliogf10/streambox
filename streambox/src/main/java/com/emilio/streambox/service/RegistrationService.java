package com.emilio.streambox.service;

import java.sql.Savepoint;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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
 *       el enlace de verificación. Su nombre queda además reservado 24 h, como
 *       en los demás caminos (ver «Concurrencia»).</li>
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
 *       que sea de una pendiente creada por el registro hace 24 h o más, que
 *       se borra y lo libera (igual que en «Editar perfil»). Esta <b>regla por
 *       nombre</b> no mira los enlaces pedidos después del alta, a diferencia
 *       de la regla por email ({@link EmailVerificationService#releasesUsername},
 *       incidencia MEDIA-1). Si el nombre es de la misma cuenta que el email,
 *       no se borra por el nombre: decide la regla por email. El 409
 *       depende <b>solo del nombre</b>, nunca del email: si el registro con el
 *       nombre de alguien respondiera distinto según el email enviado, se
 *       podría adivinar el email de cada nombre.</li>
 *   <li><b>Nombre reservado</b> ({@link UsernameReservations}): cuando el email
 *       ya tenía cuenta no se crea ninguna, pero el nombre enviado queda
 *       reservado lo mismo que si se hubiera creado (la vida del enlace) y da
 *       el mismo 409. Sin la reserva, repetir el registro con ese nombre daba
 *       409 si el email era nuevo y 202 si ya tenía cuenta: un oráculo de
 *       emails registrados en dos peticiones. Todo registro que responde 202
 *       reserva su nombre (también el que crea la cuenta), y la reserva y la
 *       cuenta nueva usan el mismo instante, así que el nombre queda libre a
 *       la vez en los dos casos (incidencia BAJA-A).</li>
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
 * <b>Lo primero, el nombre.</b> Antes de leer nada, la transacción reclama la
 * fila del nombre en {@code username_reservations}
 * ({@link UsernameReservations#reserve}), que queda bloqueada hasta el final.
 * «Editar perfil» hace lo mismo al cambiar de nombre. Así, dos peticiones que
 * piden el mismo nombre se ejecutan una detrás de otra y la segunda ve la
 * reserva o la cuenta que confirmó la primera. Antes, un registro con un email
 * que ya tenía cuenta y otro simultáneo con el mismo nombre y un email nuevo
 * (o un cambio de nombre) podían salir bien los dos, porque la reserva sin
 * confirmar del primero era invisible; con un email nuevo, el {@code UNIQUE}
 * de {@code users.username} frenaba a uno: la diferencia delataba el email.
 * </p>
 * <p>
 * Después, las filas de {@code users} implicadas (dueño del email y dueño del
 * nombre) se bloquean en orden de id ({@code PESSIMISTIC_WRITE}): dos registros
 * a la vez sobre las mismas cuentas esperan uno al otro sin poder bloquearse
 * mutuamente. Reclamar el nombre <b>antes</b> que ninguna fila de
 * {@code users}, y uno solo por transacción, no añade ciclos: quien espera un
 * nombre no retiene nada.
 * </p>
 * <p>
 * <b>La decisión se repite sin soltar el nombre</b> (incidencia MEDIA-A). Dos
 * cosas pueden cambiar mientras el registro decide; las que afectan al email
 * solo pasan si <b>no</b> tiene una cuenta verificada (una verificada no se
 * borra ni se crea a la vez), y por eso, si soltaban el nombre, se notaba
 * desde fuera:
 * </p>
 * <ul>
 *   <li>una de las filas <b>desaparece o cambia de nombre</b> mientras se
 *       espera su bloqueo (otro registro sustituyó la pendiente del email, o
 *       el dueño del nombre se lo cambió);</li>
 *   <li>otro registro con el mismo email <em>nuevo</em> crea su cuenta a la
 *       vez (no había fila que bloquear): la cuenta se inserta con
 *       {@code ON CONFLICT DO NOTHING}
 *       ({@link UserRepository#insertPendingUserIfAbsent}), la base de datos
 *       espera a que el otro confirme y no inserta nada.</li>
 * </ul>
 * <p>
 * En los dos casos se vuelve a un punto de guardado tomado <b>justo después de
 * reclamar el nombre</b> ({@link TransactionSavepoints}) y se repite la
 * decisión con el estado nuevo (como mucho {@value #MAX_ATTEMPTS} veces); en
 * el segundo, ya con la cuenta del otro a la vista, sigue el camino «el email
 * ya tiene cuenta» (202 y el nombre reservado). Antes se deshacía toda la
 * transacción (el primer caso para repetirla; el segundo, por la violación de
 * {@code uk_users_email}) y, con ella, la reserva del nombre: quien esperaba
 * por ese nombre (p. ej. un {@code PATCH /api/users/me} del atacante) entraba
 * en ese hueco y salía bien (200), cuando con el email verificado habría dado
 * 409. Ese 200 delataba que el email no tenía cuenta.
 * </p>
 * <p>
 * <b>Sin interbloqueos nuevos.</b> Volver al punto de guardado suelta también
 * los bloqueos de filas de {@code users} tomados después (en PostgreSQL), así
 * que cada intento empieza reteniendo solo el nombre, como la transacción al
 * empezar, y vuelve a bloquear en orden de id. La espera del {@code INSERT}
 * por el email de otro registro ya existía con el {@code INSERT} de JPA; ese
 * otro registro bloqueó sus filas antes de insertar, así que no puede estar
 * esperando ninguna de las de este.
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
 * ({@code AccountEmails#verification}). La solución es «He olvidado mi
 * contraseña» ({@link PasswordResetService}): el dueño del buzón fija su
 * propia contraseña con un enlace que solo llega a su correo, la cuenta queda
 * verificada y se cierran todas sus sesiones, lo que deja fuera al atacante.
 * Mientras ese enlace esté vigente, la cuenta pendiente no se puede sustituir
 * con otro registro con su email ({@link EmailVerificationService#isReplaceable});
 * pasadas 24 h desde el alta, quien pida su <b>nombre</b> sí la borra
 * ({@link EmailVerificationService#releasesUsername}), lo que a la víctima le
 * deja el email libre para registrarse.
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
    private final TransactionSavepoints savepoints;
    private final Clock clock;

    /**
     * Intentos de la decisión del registro dentro de su transacción (ver
     * «Concurrencia»): el primero y hasta tres repeticiones si las cuentas
     * cambian mientras se decide. Cada repetición es barata (no suelta el
     * nombre ni vuelve a cifrar la contraseña).
     *
     * <h2>Por qué cuatro (incidencia BAJA-1)</h2>
     * <p>
     * Mientras este registro R (nombre N, email V) tiene el cerrojo de N, solo
     * hay tres cambios que lanzan {@link AccountsChangedException}, y cada uno
     * puede pasar <b>como mucho una vez</b>:
     * </p>
     * <ul>
     *   <li><b>Del lado del nombre, uno:</b> el dueño de N se lo cambia o
     *       desaparece. Después nadie más puede quedarse con N: para dárselo a
     *       una cuenta hay que pasar por su cerrojo, que tiene R.</li>
     *   <li><b>Del lado del email, dos:</b> (1) desaparece la pendiente de V
     *       (p. ej. el atacante pide con otra cuenta el nombre de una pendiente
     *       suya con V creada hace 24 h o más, y la borra
     *       {@link EmailVerificationService#deleteIfUsernameReleased}); y (2) R
     *       pierde la carrera del {@code INSERT … ON CONFLICT} frente a otro
     *       registro con V. Tras (2), la cuenta de V es nueva: su enlace está
     *       vigente y su nombre es suyo 24 h, así que ya no se puede borrar
     *       mientras R decide y no hay un tercer cambio de este lado.</li>
     * </ul>
     * <p>
     * Con tres intentos, un atacante podía encadenar los tres cambios (uno en
     * cada intento) y R acababa en 500; con V verificado solo el primero es
     * posible y R respondía 202: 500 frente a 202 era un oráculo de emails
     * registrados. Con cuatro, el cuarto intento ya no puede ver ningún cambio
     * provocable: agotarlos solo es posible por un fallo (p. ej. una fila que
     * desaparece sin motivo), y entonces el 500 es legítimo.
     * </p>
     */
    static final int MAX_ATTEMPTS = 4;

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
     * @param usernameReservations     reservas de nombre (y cerrojo del nombre)
     * @param savepoints               puntos de guardado para repetir la decisión
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
            UsernameReservations usernameReservations,
            TransactionSavepoints savepoints) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.emailDispatcher = emailDispatcher;
        this.publicUrl = publicUrl;
        this.noticeLimits = emailProperties.resend();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.usernameReservations = usernameReservations;
        this.savepoints = savepoints;
        this.clock = clock;
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
     *                                    mientras se decide en los
     *                                    {@value #MAX_ATTEMPTS} intentos (500)
     */
    public AcceptedResponse register(CreateUserRequest request) {

        String email = UserService.normalizeEmail(request.getEmail());
        String username = UserService.normalizeUsername(request.getUsername());
        // Siempre, exista o no el email (ver «Mismo coste» en la clase).
        String passwordHash = passwordEncoder.encode(request.getPassword());

        transactionTemplate.executeWithoutResult(status -> registerInTransaction(email, username, passwordHash));
        return AcceptedResponse.REGISTRATION;
    }

    /**
     * Parte transaccional del registro: reclama el nombre y, sin soltarlo,
     * decide (ver los casos y «Concurrencia» en la clase).
     *
     * <p>
     * <b>Un solo «ahora»</b> para la reserva del nombre, para la fecha de alta
     * de la cuenta que se cree y para su primer token: así la pendiente libera
     * su nombre justo cuando caduca la reserva (incidencia BAJA-A, ver
     * {@link EmailVerificationService#releasesUsername}) y su primer token es
     * de su alta aunque las esperas de los cerrojos se alarguen (ver
     * {@link EmailVerificationService#issueForNewAccount}). Se trunca a
     * microsegundos, la precisión con la que guardan las fechas PostgreSQL y
     * H2: si no, el valor guardado podría quedar unos nanosegundos por delante
     * o por detrás del que se compara en Java.
     * </p>
     *
     * @throws IllegalStateException si las cuentas implicadas cambian en los
     *         {@value #MAX_ATTEMPTS} intentos de la decisión (500)
     */
    private void registerInTransaction(String email, String username, String passwordHash) {

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // Lo primero, antes de leer nada: reclamar el nombre (ver
        // «Concurrencia»). Deja la reserva hecha para todos los caminos que
        // responden 202 (también el que crea la cuenta); los que acaban en 409
        // la deshacen con la transacción.
        boolean reservedBefore = !usernameReservations.reserve(username, email, now);

        // Justo después: lo que haga la decisión se puede deshacer sin soltar
        // el nombre (MEDIA-A).
        Savepoint beforeDecision = savepoints.create();
        for (int attempt = 1;; attempt++) {
            try {
                decide(email, username, passwordHash, reservedBefore, now);
                return;
            } catch (AccountsChangedException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw new IllegalStateException("El registro ha encontrado cuentas cambiando en "
                            + MAX_ATTEMPTS + " intentos seguidos", e);
                }
                // Deshace borrados y bloqueos de filas de este intento; el
                // nombre sigue reclamado. El siguiente lee el estado nuevo.
                savepoints.rollbackTo(beforeDecision);
            }
        }
    }

    /**
     * Un intento de la decisión, con el nombre ya reclamado.
     *
     * <p>
     * Nada de lo que tiene efectos fuera de la base de datos (correos, límite
     * de avisos en memoria) ocurre antes del último punto en el que puede
     * saltar {@link AccountsChangedException}: así, repetir el intento no deja
     * un correo programado de un intento descartado.
     * </p>
     *
     * @throws AccountsChangedException si una de las cuentas implicadas
     *         desapareció o cambió mientras se esperaba su bloqueo, o si otro
     *         registro creó a la vez la cuenta de este email
     */
    private void decide(String email, String username, String passwordHash, boolean reservedBefore,
            Instant now) {

        Locked locked = lockInIdOrder(userRepository.findByEmail(email), userRepository.findByUsername(username),
                email, username);
        Optional<User> owner = locked.owner();
        Optional<User> holder = locked.holder();

        if (holder.isPresent()) {
            boolean sameAccount = owner.isPresent() && owner.get().getId().equals(holder.get().getId());
            if (sameAccount) {
                checkOwnUsernameReleased(holder.get());
            } else {
                freeUsernameOrFail(holder.get());
            }
        }
        if (reservedBefore) {
            // Ya había una reserva vigente del nombre (de cualquier email):
            // mismo 409 que si el nombre lo tuviera una cuenta (ver la clase).
            // Si se acaba de borrar una sustituible, el 409 deshace el borrado.
            throw new UserAlreadyExistsException(UserService.USERNAME_TAKEN_MESSAGE);
        }

        if (owner.isPresent()) {
            User existing = owner.get();
            // En los tres caminos que no crean cuenta, el nombre queda tan
            // ocupado como si se hubiera creado (oráculo del nombre, ver la
            // clase): la reserva del principio se confirma con el 202 y, si algo
            // falla después, se deshace con todo lo demás.
            if (existing.isEmailVerified()) {
                notifyAlreadyRegistered(email);
                return;
            }
            if (!emailVerificationService.isReplaceable(existing)) {
                // Pendiente con su enlace vigente, o heredada: se le reenvía el
                // enlace (con el límite silencioso) y no se toca nada más.
                emailVerificationService.issueIfAllowed(existing);
                return;
            }
            if (userRepository.deleteIfEmailNotVerified(existing.getId()) != 1) {
                // No debería pasar con la fila bloqueada, pero si se verificó
                // entre medias, es una cuenta existente y confirmada.
                notifyAlreadyRegistered(email);
                return;
            }
        }

        createPendingAccount(email, username, passwordHash, now);
    }

    /**
     * Crea la cuenta pendiente y emite su primer enlace.
     *
     * <p>
     * Si la base de datos no la inserta es que otra transacción, que ya ha
     * confirmado, tiene una cuenta con este email (otro registro simultáneo
     * con el mismo email nuevo: aquí no había fila que bloquear) o con este
     * nombre (solo si alguien se lo dio sin pasar por su cerrojo, como el
     * administrador inicial). No es un error: se repite la decisión, que ya
     * la ve y responde lo mismo que si hubiera llegado antes (el 202 de «el
     * email ya tiene cuenta» o el 409 del nombre). Antes, aquí saltaba la
     * violación de {@code uk_users_email} y se deshacía toda la transacción
     * (MEDIA-A).
     * </p>
     *
     * @throws AccountsChangedException si ya hay una cuenta con ese email o nombre
     */
    private void createPendingAccount(String email, String username, String passwordHash, Instant now) {

        if (userRepository.insertPendingUserIfAbsent(username, email, passwordHash, now) != 1) {
            throw new AccountsChangedException();
        }
        User created = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("La cuenta recién creada no aparece por su email"));
        // Mismo instante que la cuenta: si el token usara el reloj y las esperas
        // de los cerrojos pasaran de 5 min, la cuenta contaría como heredada.
        emailVerificationService.issueForNewAccount(created, now);
    }

    /**
     * El nombre de usuario lo tiene <b>otra</b> cuenta: si ya no le pertenece
     * (pendiente creada por el registro hace 24 h o más) se borra y el nombre
     * queda libre; si no, 409. La regla es la misma que en «Editar perfil»
     * ({@link EmailVerificationService#deleteIfUsernameReleased}) y no mira los
     * enlaces pedidos después del alta (incidencia MEDIA-1, ver
     * {@link EmailVerificationService#releasesUsername}).
     */
    private void freeUsernameOrFail(User holder) {
        if (!emailVerificationService.deleteIfUsernameReleased(holder)) {
            throw new UserAlreadyExistsException(UserService.USERNAME_TAKEN_MESSAGE);
        }
    }

    /**
     * El nombre lo tiene la <b>misma</b> cuenta que el email (se repite el
     * registro con los dos). La respuesta depende solo del nombre, como con
     * cualquier otro email: 409 si la cuenta lo conserva
     * ({@link EmailVerificationService#releasesUsername}) y, si no, sigue el
     * camino del email.
     *
     * <p>
     * Aquí no se borra nada: lo decide la <b>regla por email</b> más abajo
     * ({@link EmailVerificationService#isReplaceable}). Si la cuenta tiene un
     * enlace vigente (p. ej. uno de «He olvidado mi contraseña» que el titular
     * pidió pasadas las 24 h), se conserva y se le reenvía el enlace; borrarla
     * aquí dejaría sin valor ese enlace con solo repetir el registro con su
     * email. Para el resto de emails el nombre ya está libre (se respondería
     * 202), así que tampoco aquí se responde 409.
     * </p>
     */
    private void checkOwnUsernameReleased(User account) {
        if (!emailVerificationService.releasesUsername(account)) {
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
     * lanza {@link AccountsChangedException} para repetir la decisión:
     * decidir con la fila de antes podría borrar o rechazar con datos que ya
     * no son ciertos.
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
                    .orElseThrow(AccountsChangedException::new));
        }
        Optional<User> lockedOwner = owner.map(user -> fresh.get(user.getId()));
        Optional<User> lockedHolder = holder.map(user -> fresh.get(user.getId()));
        if (lockedOwner.filter(user -> !email.equals(user.getEmail())).isPresent()
                || lockedHolder.filter(user -> !username.equals(user.getUsername())).isPresent()) {
            throw new AccountsChangedException();
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
     * Dueño del email y dueño del nombre, ya bloqueados y releídos.
     *
     * @param owner  cuenta con el email del registro, si existe
     * @param holder cuenta con el nombre del registro, si existe
     */
    private record Locked(Optional<User> owner, Optional<User> holder) {
    }

    /**
     * Las cuentas del registro cambiaron mientras se decidía: una desapareció
     * o cambió mientras se esperaba su bloqueo, u otro registro creó a la vez
     * la de este email. Vuelve al punto de guardado para repetir la decisión
     * sin soltar el nombre (ver «Concurrencia» en la clase). Nunca sale de
     * {@link #register}.
     */
    static final class AccountsChangedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AccountsChangedException() {
            // Sin mensaje ni traza: es control de flujo, no un error.
            super(null, null, false, false);
        }
    }
}
