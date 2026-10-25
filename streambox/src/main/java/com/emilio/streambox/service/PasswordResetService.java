package com.emilio.streambox.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.email.PublicUrlProperties;
import com.emilio.streambox.entity.AccountTokenPurpose;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.InvalidResetTokenException;
import com.emilio.streambox.repository.AccountTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.security.refresh.RefreshTokenService;

/**
 * «He olvidado mi contraseña»: envía el enlace para elegir una contraseña
 * nueva ({@code POST /api/auth/password-reset}) y la fija con el token de ese
 * enlace ({@code POST /api/auth/password-reset/confirm}).
 *
 * <h2>Qué resuelve</h2>
 * <p>
 * Sin esto, quien olvidaba su contraseña perdía la cuenta. Y cierra el riesgo
 * <b>M2</b> (<i>pre-account hijacking</i>, ver {@link RegistrationService}):
 * quien registraba primero el email de otra persona con su propia contraseña
 * podía retenerlo. Ahora el dueño del buzón fija su propia contraseña; al
 * hacerlo la cuenta queda verificada (lo acaba de demostrar), se invalidan
 * todos los enlaces que quedaran vivos y se cierran todas las sesiones.
 * </p>
 *
 * <h2>Pedir el enlace no revela nada</h2>
 * <p>
 * Responde siempre el mismo 202, y <b>tarda lo mismo</b>: el trabajo (buscar
 * la cuenta, el límite silencioso, emitir el token) va en el ejecutor de los
 * correos ({@link EmailDispatcher#runInBackground}), como el reenvío de la
 * verificación ({@link EmailVerificationService#resend}, donde está explicado
 * por qué no un «suelo» de tiempo). Vale para cualquier cuenta: verificada,
 * pendiente, heredada o ADMIN. Una pendiente también, a propósito: es justo la
 * cuenta que un atacante pudo registrar con el email de la víctima.
 * </p>
 *
 * <h2>Los tokens</h2>
 * <p>
 * Los de {@link AccountTokens} (32 bytes aleatorios, solo su SHA-256 en la
 * base de datos), con propósito {@link AccountTokenPurpose#PASSWORD_RESET} y
 * vida {@code streambox.email.password-reset-ttl} (1 h). Pedir otro invalida
 * los anteriores de restablecimiento (no los de verificación). El límite
 * silencioso por cuenta usa las propiedades {@code streambox.email.resend.*}
 * (3 por hora, 60 s entre dos) pero <b>solo cuenta los tokens de
 * restablecimiento</b>: pedir este enlace no gasta los reenvíos de la
 * verificación ni al revés.
 * </p>
 *
 * <h2>Confirmar: el token no se gasta si la contraseña no vale</h2>
 * <p>
 * El orden importa. Primero se busca el token vigente <b>sin gastarlo</b> y se
 * comprueba la contraseña nueva con la {@link PasswordPolicy} contra el nombre
 * y el email de <b>esa</b> cuenta: si no la cumple, 400
 * {@code VALIDATION_ERROR} en {@code newPassword} y el usuario la corrige con
 * el mismo enlace (gastarlo le obligaría a pedir otro correo por una errata).
 * Después se calcula el hash BCrypt (unos 100 ms) <b>fuera</b> de la
 * transacción, para no tener una conexión del pool ocupada mientras tanto. Y
 * solo entonces, en una transacción con la fila del usuario bloqueada, se
 * consume el token de forma atómica y se cambia todo lo demás.
 * </p>
 *
 * <h2>Concurrencia</h2>
 * <p>
 * Dos confirmaciones con el mismo token (doble clic, dos pestañas) pasan las
 * dos la lectura inicial, pero en la transacción la segunda espera al bloqueo
 * de la fila del usuario y, cuando le llega, el {@code consume} ya devuelve 0:
 * recibe el 400 {@code INVALID_RESET_TOKEN} y no cambia nada. Lo prueba
 * {@code PostgresPasswordResetConcurrencyIntegrationTest} con hilos de verdad.
 * </p>
 *
 * <h2>Lo que no puede hacer</h2>
 * <p>
 * Los JWT de acceso ya emitidos son stateless y siguen valiendo hasta que
 * caducan (15 minutos como mucho); las sesiones ya no se pueden renovar. Es la
 * misma limitación que en el cambio de contraseña del perfil
 * ({@link PasswordChangeService}).
 * </p>
 */
@Service
public class PasswordResetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordResetService.class);

    /** Campo del formulario al que se asocian los errores de la contraseña nueva (lo espera el frontend). */
    static final String NEW_PASSWORD_FIELD = "newPassword";

    private static final AccountTokenPurpose PURPOSE = AccountTokenPurpose.PASSWORD_RESET;

    private final AccountTokens accountTokens;
    private final AccountTokenRepository accountTokenRepository;
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptService loginAttemptService;
    private final EmailDispatcher emailDispatcher;
    private final EmailProperties emailProperties;
    private final PublicUrlProperties publicUrl;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;

    /**
     * Crea el servicio.
     *
     * @param accountTokens            emisión de los tokens y límite silencioso
     * @param accountTokenRepository   tokens de un solo uso
     * @param userRepository           cuentas
     * @param emailVerificationService bloqueo de la fila del usuario (regla común)
     * @param passwordEncoder          BCrypt
     * @param refreshTokenService      revoca todas las sesiones al cambiar la contraseña
     * @param loginAttemptService      olvida las «IPs conocidas» y borra los fallos de login
     * @param emailDispatcher          envía los correos tras el commit
     * @param emailProperties          vida del enlace y límites de correos por cuenta
     * @param publicUrl                URL pública con la que se construyen los enlaces
     * @param clock                    reloj de la aplicación
     * @param transactionManager       gestor de transacciones
     */
    public PasswordResetService(
            AccountTokens accountTokens,
            AccountTokenRepository accountTokenRepository,
            UserRepository userRepository,
            EmailVerificationService emailVerificationService,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            LoginAttemptService loginAttemptService,
            EmailDispatcher emailDispatcher,
            EmailProperties emailProperties,
            PublicUrlProperties publicUrl,
            Clock clock,
            PlatformTransactionManager transactionManager) {

        this.accountTokens = accountTokens;
        this.accountTokenRepository = accountTokenRepository;
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.emailDispatcher = emailDispatcher;
        this.emailProperties = emailProperties;
        this.publicUrl = publicUrl;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    // ------------------------------------------------------------------
    // Pedir el enlace
    // ------------------------------------------------------------------

    /**
     * Pide el correo con el enlace para elegir una contraseña nueva.
     *
     * <p>
     * Aquí solo se normaliza el email (su longitud es un 400 que depende solo
     * del texto enviado) y se encola el trabajo: la respuesta es la misma, y
     * tarda lo mismo, exista o no la cuenta y se envíe o no el correo (ver la
     * clase). Con {@code streambox.email.async=false} (los tests) el ejecutor
     * corre en el mismo hilo y, al responder, el correo ya está en la bandeja.
     * </p>
     *
     * @param rawEmail email recibido (ya validado en forma)
     * @return la respuesta 202 fija
     * @throws InvalidParameterException si el email normalizado supera los 100
     *         caracteres (400, como el registro)
     */
    public AcceptedResponse request(String rawEmail) {

        String email = UserService.normalizeEmail(rawEmail);
        emailDispatcher.runInBackground("enlace para restablecer la contraseña", () -> requestNow(email));
        return AcceptedResponse.PASSWORD_RESET;
    }

    /**
     * Trabajo de la petición (en el ejecutor de los correos, con su propia
     * transacción): si el email es de una cuenta, bloquea su fila y, si el
     * límite silencioso lo permite, invalida los enlaces de restablecimiento
     * anteriores, emite uno nuevo y programa el correo. Sin cuenta, o si
     * desaparece mientras se espera el bloqueo, no hace nada.
     *
     * @param email email normalizado
     */
    void requestNow(String email) {
        transactionTemplate.executeWithoutResult(status -> userRepository.findByEmail(email)
                .flatMap(emailVerificationService::lockForUpdate)
                .ifPresent(this::issueIfAllowed));
    }

    /**
     * Emite el enlace y programa su correo si el límite silencioso de la
     * cuenta lo permite (con la fila ya bloqueada: dos peticiones simultáneas
     * esperan una a la otra y no pueden dejar dos enlaces vigentes).
     */
    private void issueIfAllowed(User user) {

        Long userId = user.getId();
        Instant now = clock.instant();
        if (!accountTokens.withinSilentLimit(userId, PURPOSE, now, emailProperties.resend())) {
            LOGGER.info("Enlace para restablecer la contraseña de la cuenta {} omitido: límite por cuenta", userId);
            return;
        }
        Duration ttl = emailProperties.passwordResetTtl();
        String token = accountTokens.issue(userId, PURPOSE, now, ttl);
        emailDispatcher.sendAfterCommit(AccountEmails.passwordReset(user.getEmail(), user.getUsername(), publicUrl,
                token, ttl));
        LOGGER.info("Enlace para restablecer la contraseña emitido a la cuenta {}", userId);
    }

    // ------------------------------------------------------------------
    // Confirmar
    // ------------------------------------------------------------------

    /**
     * Fija la contraseña nueva con el token del enlace.
     *
     * <p>
     * En la transacción (fila del usuario bloqueada): consume el token, guarda
     * el hash nuevo, <b>marca el correo como verificado</b> (quien tiene el
     * token controla el buzón: es lo que cierra M2), invalida <b>todos</b> los
     * tokens vigentes de la cuenta (de los dos propósitos: p. ej. un enlace de
     * verificación que pidió quien registró ese email) y revoca <b>todas</b>
     * sus sesiones. Si algo falla, no cambia nada y el token sigue vigente.
     * </p>
     *
     * <p>
     * Tras el commit (si se deshace, todo debe seguir como estaba):
     * </p>
     * <ul>
     *   <li>{@link LoginAttemptService#forgetKnownIpsExcept}: olvida las «IPs
     *       conocidas» de la cuenta (un intruso que entró con la contraseña
     *       antigua no conserva la suya) salvo la de esta petición, como el
     *       cambio de contraseña del perfil (decisión del orquestador). Quien
     *       confirma ha demostrado controlar el buzón, una prueba al menos tan
     *       fuerte como la contraseña; si se quedara sin ninguna IP conocida,
     *       cualquiera podría bloquearle la cuenta con 5 fallos antes de su
     *       primer login con la contraseña nueva.</li>
     *   <li>{@link LoginAttemptService#clearFailures}: borra los fallos y el
     *       bloqueo de la cuenta. Quien llega aquí suele venir de agotar sus
     *       intentos (o de que alguien se los agote): con la contraseña nueva
     *       debe poder entrar ya, no dentro de 15 minutos.</li>
     *   <li>El aviso «Tu contraseña de StreamBox ha cambiado» (lo programa la
     *       transacción para después del commit).</li>
     * </ul>
     *
     * <p>
     * No inicia sesión: el usuario entra después con la contraseña nueva. Así
     * el enlace, que viaja por correo, no se convierte en una forma de entrar
     * sin conocerla.
     * </p>
     *
     * @param token       token del enlace, tal como llega
     * @param newPassword contraseña nueva (longitud ya validada)
     * @param clientIp    dirección remota de la petición ({@code getRemoteAddr()}):
     *                    queda como la única «IP conocida» de la cuenta
     * @throws InvalidResetTokenException si el token no tiene formato de token,
     *         no existe, caducó, ya se usó o se invalidó (mismo error en todos
     *         los casos); la contraseña no cambia
     * @throws InvalidParameterException  si la contraseña nueva no cumple la
     *         política (400 en {@code newPassword}); el token no se gasta
     */
    public void confirm(String token, String newPassword, String clientIp) {

        if (!AccountTokens.isWellFormed(token)) {
            // Sin consultar la base de datos: no puede ser un token propio.
            throw new InvalidResetTokenException();
        }
        String hash = AccountTokens.sha256Hex(token);

        ResetTarget target = readOnlyTransactionTemplate.execute(status -> findTarget(hash))
                .orElseThrow(InvalidResetTokenException::new);
        checkPolicy(newPassword, target.username(), target.email());
        String newHash = passwordEncoder.encode(newPassword);

        String email = transactionTemplate.execute(
                status -> replacePassword(hash, target.userId(), newPassword, newHash));
        loginAttemptService.forgetKnownIpsExcept(email, clientIp);
        loginAttemptService.clearFailures(email);
    }

    /**
     * Cuenta dueña de un token <b>vigente</b> de restablecimiento, sin gastarlo.
     *
     * <p>
     * Esta lectura no decide nada definitivo (otra petición puede consumir el
     * token justo después): solo sirve para validar la contraseña contra los
     * datos de la cuenta antes de gastar el token. Lo que decide es el
     * {@code consume} de {@link #replacePassword}.
     * </p>
     */
    private Optional<ResetTarget> findTarget(String hash) {
        Instant now = clock.instant();
        return accountTokenRepository.findByTokenHashAndPurpose(hash, PURPOSE)
                .filter(found -> found.getUsedAt() == null && found.getExpiresAt().isAfter(now))
                .map(found -> {
                    User user = found.getUser();
                    return new ResetTarget(user.getId(), user.getUsername(), user.getEmail());
                });
    }

    /**
     * Parte transaccional de la confirmación (ver {@link #confirm}).
     *
     * @return el email de la cuenta, para lo que se hace tras el commit
     */
    private String replacePassword(String hash, Long userId, String newPassword, String newHash) {

        Instant now = clock.instant();
        // Primero el bloqueo: dos confirmaciones con el mismo token quedan en
        // fila aquí y la segunda ve el token ya consumido.
        User locked = userRepository.findForUpdateById(userId).orElseThrow(InvalidResetTokenException::new);
        // Otra vez la política, con los datos de la fila bloqueada: el nombre
        // pudo cambiar («Editar perfil») desde la lectura. Es barato (sin
        // BCrypt) y, si falla, la transacción se deshace sin gastar el token.
        checkPolicy(newPassword, locked.getUsername(), locked.getEmail());
        if (accountTokenRepository.consume(hash, PURPOSE, now) != 1) {
            throw new InvalidResetTokenException();
        }

        locked.setPassword(newHash);
        // Se escribe ya (con @DynamicUpdate, un UPDATE solo de la contraseña):
        // las sentencias masivas de abajo vacían la sesión de Hibernate y la
        // entidad quedaría desacoplada con el cambio sin guardar.
        userRepository.flush();
        String email = locked.getEmail();

        userRepository.markEmailVerified(userId, now);
        int invalidated = accountTokenRepository.invalidateAllActiveTokens(userId, now);
        int revoked = refreshTokenService.revokeAllSessions(userId);
        emailDispatcher.sendAfterCommit(AccountEmails.passwordChanged(email, publicUrl, now));

        LOGGER.info("Contraseña restablecida de la cuenta {}: {} enlaces invalidados y {} refresh tokens revocados",
                userId, invalidated, revoked);
        return email;
    }

    /** Comprueba la contraseña nueva con la política contra los datos de la cuenta. */
    private static void checkPolicy(String newPassword, String username, String email) {
        PasswordPolicy.findContentViolation(newPassword, username, email)
                .ifPresent(message -> {
                    throw new InvalidParameterException(NEW_PASSWORD_FIELD, message);
                });
    }

    /**
     * Datos de la cuenta dueña del token que hacen falta antes de gastarlo.
     *
     * @param userId   id de la cuenta
     * @param username nombre de usuario (para la política de contraseñas)
     * @param email    email (para la política de contraseñas)
     */
    private record ResetTarget(Long userId, String username, String email) {
    }
}
