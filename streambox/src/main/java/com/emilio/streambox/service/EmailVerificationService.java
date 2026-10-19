package com.emilio.streambox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.email.PublicUrlProperties;
import com.emilio.streambox.entity.AccountToken;
import com.emilio.streambox.entity.AccountTokenPurpose;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidVerificationTokenException;
import com.emilio.streambox.repository.AccountTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.security.refresh.RefreshTokenService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Verificación del correo: emite los tokens de los enlaces, los confirma y
 * reenvía el correo.
 *
 * <h2>Los tokens</h2>
 * <ul>
 *   <li><b>32 bytes de {@link SecureRandom}</b> (256 bits) en Base64URL sin
 *       relleno: 43 caracteres que van tal cual en una URL. Adivinar uno es
 *       imposible en la práctica, con o sin límite de intentos.</li>
 *   <li>En la base de datos <b>solo su SHA-256</b> (hexadecimal en minúsculas),
 *       como los refresh tokens: con una copia de la tabla no se puede
 *       verificar ninguna cuenta. Un hash rápido basta porque el token es
 *       aleatorio y largo, no una contraseña.</li>
 *   <li>{@code createdAt} y {@code expiresAt} salen del mismo {@link Clock}
 *       (la tabla exige {@code expires_at > created_at}).</li>
 *   <li>Emitir uno <b>invalida los anteriores</b> de la cuenta, con la fila
 *       del usuario bloqueada ({@code PESSIMISTIC_WRITE}): dos reenvíos a la
 *       vez esperan uno al otro y no pueden dejar dos enlaces vigentes.</li>
 * </ul>
 *
 * <h2>Límite silencioso por cuenta</h2>
 * <p>
 * Como mucho {@code streambox.email.resend.max-per-window} correos por ventana
 * (3 por hora; el del registro cuenta) y al menos
 * {@code streambox.email.resend.min-interval} entre dos (60 s). Pasado el
 * límite no se envía nada, pero la respuesta es el mismo 202: un 429 por email
 * revelaría que ese email tiene una cuenta pendiente. Se cuenta con los tokens
 * de la base de datos ({@code countIssuedSince}), así que sobrevive a los
 * reinicios.
 * </p>
 */
@Service
public class EmailVerificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailVerificationService.class);

    /** Bytes aleatorios de cada token. */
    static final int TOKEN_BYTES = 32;

    /** Longitud del token en Base64URL sin relleno (32 bytes → 43 caracteres). */
    static final int TOKEN_LENGTH = 43;

    /** Forma exacta de un token propio: lo demás se rechaza sin ir a la base de datos. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{" + TOKEN_LENGTH + "}");

    /**
     * Margen entre la creación de una cuenta y su primer token para considerar
     * que la creó el registro con verificación (ver {@link #isReplaceable}). En
     * el registro los dos se escriben en la misma transacción, con milisegundos
     * de diferencia; una cuenta heredada (anterior a la V9) recibe su primer
     * enlace como pronto cuando se despliega esta versión.
     */
    static final Duration REGISTRATION_TOKEN_WINDOW = Duration.ofMinutes(5);

    private static final AccountTokenPurpose PURPOSE = AccountTokenPurpose.EMAIL_VERIFICATION;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountTokenRepository accountTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final EmailDispatcher emailDispatcher;
    private final EmailProperties emailProperties;
    private final PublicUrlProperties publicUrl;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Crea el servicio.
     *
     * @param accountTokenRepository tokens de un solo uso
     * @param userRepository         cuentas
     * @param refreshTokenService    cierra las sesiones anteriores a la verificación
     * @param loginAttemptService    olvida las «IPs conocidas» de la cuenta al verificarla
     * @param emailDispatcher        envía los correos tras el commit
     * @param emailProperties        vida del enlace y límites de reenvío
     * @param publicUrl              URL pública con la que se construye el enlace
     * @param clock                  reloj de la aplicación
     * @param transactionManager     gestor de transacciones (confirmación)
     */
    public EmailVerificationService(
            AccountTokenRepository accountTokenRepository,
            UserRepository userRepository,
            RefreshTokenService refreshTokenService,
            LoginAttemptService loginAttemptService,
            EmailDispatcher emailDispatcher,
            EmailProperties emailProperties,
            PublicUrlProperties publicUrl,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        this.accountTokenRepository = accountTokenRepository;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.emailDispatcher = emailDispatcher;
        this.emailProperties = emailProperties;
        this.publicUrl = publicUrl;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Confirma el correo con el token del enlace.
     *
     * <p>
     * En una transacción: consume el token (un único {@code UPDATE}
     * condicional: si dos peticiones llegan a la vez con el mismo, solo una
     * gana), marca la cuenta como verificada, invalida sus demás enlaces y
     * <b>revoca todas sus sesiones</b> (una cuenta heredada pudo iniciar sesión
     * antes de la V9; tras demostrar quién es el dueño del buzón, ninguna
     * sesión anterior debe seguir). Después del commit olvida sus «IPs
     * conocidas» del login, por el mismo motivo.
     * </p>
     *
     * <p>
     * No inicia sesión: el usuario va al login con su contraseña. Así el
     * enlace, que viaja por correo, no se convierte en una forma de entrar.
     * </p>
     *
     * @param token token del enlace, tal como llega
     * @throws InvalidVerificationTokenException si no tiene formato de token,
     *         no existe, caducó o ya se usó (mismo error en todos los casos)
     */
    public void confirm(String token) {

        if (token == null || !TOKEN_FORMAT.matcher(token).matches()) {
            // Sin consultar la base de datos: no puede ser un token propio.
            throw new InvalidVerificationTokenException();
        }
        String hash = sha256Hex(token);

        String email = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            if (accountTokenRepository.consume(hash, PURPOSE, now) != 1) {
                throw new InvalidVerificationTokenException();
            }
            Long userId = accountTokenRepository.findUserIdByTokenHashAndPurpose(hash, PURPOSE)
                    .orElseThrow(InvalidVerificationTokenException::new);
            userRepository.markEmailVerified(userId, now);
            accountTokenRepository.invalidateActiveTokens(userId, PURPOSE, now);
            int revoked = refreshTokenService.revokeAllSessions(userId);
            LOGGER.info("Correo verificado de la cuenta {} ({} refresh tokens revocados)", userId, revoked);
            // Se lee al final: las sentencias masivas de arriba vacían la
            // sesión de Hibernate y una entidad leída antes quedaría desacoplada.
            return userRepository.findById(userId).map(User::getEmail).orElse(null);
        });
        if (email != null) {
            loginAttemptService.forgetKnownIps(email);
        }
    }

    /**
     * Reenvía el correo de confirmación a una cuenta pendiente.
     *
     * <p>
     * La respuesta es la misma siempre, exista o no la cuenta, esté o no
     * verificada y se haya enviado o no (límite silencioso): solo cambia lo
     * que pasa por dentro.
     * </p>
     *
     * <p>
     * <b>Y tarda lo mismo.</b> Aquí solo se normaliza el email (su longitud es
     * un 400 que depende solo del texto enviado) y se encola el trabajo en el
     * ejecutor de los correos ({@link EmailDispatcher#runInBackground}): buscar
     * la cuenta, bloquearla, mirar el límite, invalidar los enlaces anteriores
     * y guardar el nuevo. Antes se hacía en la petición: un email sin cuenta o
     * ya verificado costaba una consulta y uno pendiente unas seis, y esa
     * diferencia de tiempo delataba qué emails tienen una cuenta pendiente.
     * Se prefirió esto a un «suelo» de tiempo fijo (responder siempre tras,
     * p. ej., 300 ms): un suelo ocupa un hilo de Tomcat esperando en cada
     * petición, hay que elegirlo mayor que el peor caso (con la base de datos
     * lenta, el camino largo lo supera y vuelve a notarse) y sigue haciendo el
     * trabajo con una conexión del pool por petición. Encolado, el camino
     * largo no se ve desde fuera y el ejecutor (2 hilos) limita también cuántas
     * conexiones puede ocupar una avalancha de reenvíos.
     * </p>
     *
     * <p>
     * Con {@code streambox.email.async=false} (los tests) el ejecutor corre en
     * el mismo hilo: al responder, el trabajo ya está hecho y el correo en la
     * bandeja, así que el comportamiento se puede comprobar igual.
     * </p>
     *
     * @param rawEmail email recibido (ya validado en forma)
     * @return la respuesta 202 fija
     * @throws com.emilio.streambox.exception.InvalidParameterException si el
     *         email normalizado supera los 100 caracteres (400, como el registro)
     */
    public AcceptedResponse resend(String rawEmail) {

        String email = UserService.normalizeEmail(rawEmail);
        emailDispatcher.runInBackground("reenvío del correo de verificación", () -> resendNow(email));
        return AcceptedResponse.RESEND;
    }

    /**
     * Trabajo del reenvío (en el ejecutor de los correos, con su propia
     * transacción): si el email es de una cuenta pendiente, la bloquea y le
     * emite otro enlace (con el límite silencioso).
     *
     * <p>
     * Si la cuenta desaparece entre la lectura y el bloqueo (otro registro la
     * sustituyó), se trata como «sin cuenta»: no se envía nada. Antes, el
     * {@code refresh} con bloqueo lanzaba {@code EntityNotFoundException}.
     * </p>
     *
     * @param email email normalizado
     */
    void resendNow(String email) {
        transactionTemplate.executeWithoutResult(status -> userRepository.findByEmail(email)
                .filter(user -> !user.isEmailVerified())
                .flatMap(this::lockForUpdate)
                .filter(user -> !user.isEmailVerified())
                .ifPresent(this::issueIfAllowed));
    }

    /**
     * Emite el primer token de una cuenta recién creada y programa su correo.
     * Sin límite: es el correo del propio registro.
     *
     * @param user cuenta (ya guardada en esta transacción)
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueForNewAccount(User user) {
        issue(user, clock.instant());
    }

    /**
     * Emite un token nuevo (invalidando los anteriores) y programa su correo,
     * <b>si el límite silencioso de la cuenta lo permite</b>.
     *
     * <p>
     * Quien llama debe tener bloqueada la fila del usuario en esta transacción
     * ({@link #lockForUpdate}): así las comprobaciones del límite y la emisión
     * no se cruzan con otro reenvío.
     * </p>
     *
     * @param user cuenta pendiente (bloqueada)
     * @return {@code true} si se ha emitido y se enviará el correo
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean issueIfAllowed(User user) {

        Long userId = user.getId();
        Instant now = clock.instant();
        EmailProperties.Resend limits = emailProperties.resend();
        boolean tooSoon = !limits.minInterval().isZero()
                && accountTokenRepository.countIssuedSince(userId, PURPOSE, now.minus(limits.minInterval())) > 0;
        boolean tooMany = accountTokenRepository.countIssuedSince(userId, PURPOSE, now.minus(limits.window()))
                >= limits.maxPerWindow();
        if (tooSoon || tooMany) {
            LOGGER.info("Reenvío del correo de verificación de la cuenta {} omitido: límite por cuenta", userId);
            return false;
        }
        issue(user, now);
        return true;
    }

    /**
     * Indica si una cuenta pendiente se puede <b>sustituir</b> por un registro
     * nuevo con su email o su nombre: la creó el registro con verificación
     * (su primer token es de cuando se creó) y ya no tiene ningún enlace
     * vigente (todos caducaron).
     *
     * <p>
     * <b>Por qué no basta con «pendiente y sin enlace vigente».</b> Las cuentas
     * USER anteriores a la V9 quedaron pendientes y pueden tener favoritos,
     * «me gusta» y años de uso. Pedir un enlace para una de ellas le crea un
     * token; si al caducar se pudiera sustituir, cualquiera que supiera su
     * email podría borrarla registrándose 24 h después. Por eso se exige que el
     * primer token sea de su creación ({@link #REGISTRATION_TOKEN_WINDOW}): una
     * cuenta heredada no lo cumple nunca. Si alguna vez se borran tokens
     * antiguos ({@code deleteExpired}), una cuenta sin tokens tampoco lo cumple:
     * el fallo va hacia el lado seguro (no se borra).
     * </p>
     *
     * @param user cuenta (con la fila ya bloqueada en esta transacción)
     * @return {@code true} si se puede borrar para registrar otra en su lugar
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isReplaceable(User user) {

        if (user.isEmailVerified() || user.getCreatedAt() == null) {
            return false;
        }
        boolean createdByRegistration = accountTokenRepository.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(
                user.getId(), PURPOSE, user.getCreatedAt().plus(REGISTRATION_TOKEN_WINDOW));
        return createdByRegistration && !accountTokenRepository.hasActiveToken(user.getId(), PURPOSE, clock.instant());
    }

    /**
     * Borra una cuenta pendiente <b>si es sustituible</b> ({@link #isReplaceable})
     * para liberar su nombre de usuario. Es la regla común de los dos sitios
     * donde se puede pedir un nombre: el registro
     * ({@link RegistrationService}) y «Editar perfil»
     * ({@link UserService#updateProfile}).
     *
     * <p>
     * <b>Por qué los dos.</b> Si solo el registro liberara el nombre de una
     * pendiente caducada, pasadas 24 h «Editar perfil» respondería 409 con el
     * nombre de un registro hecho con un email nuevo (la pendiente sigue en la
     * base de datos) y 200 con el de uno hecho con un email que ya tenía cuenta
     * (su reserva en memoria, {@link UsernameReservations}, ya caducó): un
     * oráculo de emails registrados. Aplicando la misma regla, los dos casos
     * dan 200.
     * </p>
     *
     * <p>
     * El borrado lleva su propia condición ({@code email_verified_at IS NULL},
     * ver {@code UserRepository#deleteIfEmailNotVerified}) y vacía la sesión de
     * Hibernate: lo que quien llama tuviera cargado queda desacoplado.
     * </p>
     *
     * @param user cuenta (con la fila ya bloqueada en esta transacción,
     *             {@link #lockForUpdate})
     * @return {@code true} si se ha borrado y su nombre queda libre;
     *         {@code false} si no es sustituible (verificada, con un enlace
     *         vigente o heredada) o ya no estaba pendiente
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteIfReplaceable(User user) {
        return isReplaceable(user) && userRepository.deleteIfEmailNotVerified(user.getId()) == 1;
    }

    /**
     * Bloquea la fila del usuario hasta el final de la transacción
     * ({@code SELECT ... FOR UPDATE}) y devuelve su estado <b>actual</b>, por
     * si otra transacción la cambió (p. ej. la verificó) entre la lectura y el
     * bloqueo.
     *
     * <p>
     * <b>Vacío si la fila ya no existe</b> (otra transacción la borró mientras
     * esta esperaba el bloqueo: un registro que sustituyó una cuenta pendiente
     * caducada). Antes se hacía con {@code EntityManager.refresh(user,
     * PESSIMISTIC_WRITE)}, que en ese caso lanza
     * {@code EntityNotFoundException}: el registro o el reenvío acababan en un
     * 500. Ahora decide quien llama (el registro reintenta; el reenvío lo trata
     * como «sin cuenta»).
     * </p>
     *
     * <p>
     * La entidad recibida se <b>desacopla</b> antes de la consulta: si siguiera
     * en la sesión de Hibernate, la consulta bloquearía la fila pero devolvería
     * esa misma instancia con los datos leídos antes del bloqueo. Quien llama
     * debe usar la instancia devuelta, no la que pasó (que ya no se guarda).
     * </p>
     *
     * @param user cuenta leída en esta transacción
     * @return la cuenta, bloqueada y releída, o vacío si ya no existe
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<User> lockForUpdate(User user) {
        entityManager.detach(user);
        return userRepository.findForUpdateById(user.getId());
    }

    /**
     * Invalida los enlaces vigentes, guarda el token nuevo y programa su correo
     * (con el nombre de la cuenta, ver {@link AccountEmails#verification}).
     */
    private void issue(User user, Instant now) {

        Long userId = user.getId();
        accountTokenRepository.invalidateActiveTokens(userId, PURPOSE, now);

        String token = newToken();
        AccountToken entity = new AccountToken();
        // Referencia sin cargar el usuario (ni depender de que la entidad que
        // tenga quien llama siga en la sesión de Hibernate).
        entity.setUser(userRepository.getReferenceById(userId));
        entity.setPurpose(PURPOSE);
        entity.setTokenHash(sha256Hex(token));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(emailProperties.verificationTtl()));
        accountTokenRepository.save(entity);

        emailDispatcher.sendAfterCommit(AccountEmails.verification(user.getEmail(), user.getUsername(), publicUrl,
                token, emailProperties.verificationTtl()));
    }

    /**
     * Genera un token: {@value #TOKEN_BYTES} bytes aleatorios en Base64URL sin
     * relleno.
     *
     * @return token de {@value #TOKEN_LENGTH} caracteres
     */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256 de un token en hexadecimal en minúsculas (lo que guarda
     * {@code account_tokens.token_hash}).
     *
     * @param token token en claro
     * @return 64 caracteres hexadecimales
     */
    static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM está obligada a tener SHA-256.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
