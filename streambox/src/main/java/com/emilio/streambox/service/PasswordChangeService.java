package com.emilio.streambox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.CurrentPasswordIncorrectException;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.TooManyRequestsException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.security.ratelimit.PasswordChangeAttemptService;
import com.emilio.streambox.security.ratelimit.PasswordChangeAttemptService.Attempt;
import com.emilio.streambox.security.refresh.IssuedRefreshToken;
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.emilio.streambox.security.refresh.SessionTokens;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * Cambio de contraseña del usuario autenticado
 * ({@code PUT /api/users/me/password}).
 *
 * <h2>Qué hace, en orden</h2>
 * <ol>
 *   <li>Comprueba la contraseña nueva con la {@link PasswordPolicy} (contra el
 *       nombre y el email de la cuenta). No gasta intento: no revela nada de
 *       la contraseña actual.</li>
 *   <li>Reserva un intento ({@link PasswordChangeAttemptService}: 5 fallos
 *       cada 15 minutos por cuenta) y compara la contraseña actual con BCrypt.
 *       Sin ese límite, quien robara una sesión podría adivinar la contraseña
 *       por fuerza bruta desde ella.</li>
 *   <li>Solo entonces comprueba que la nueva es distinta de la actual (antes
 *       sería un oráculo: diría si la nueva <em>es</em> la actual).</li>
 *   <li>En una transacción: guarda el hash nuevo, <b>revoca todas las
 *       sesiones</b> del usuario y abre una familia nueva para la sesión
 *       actual.</li>
 * </ol>
 *
 * <h2>Las demás sesiones se cierran; la actual sigue (decisión del autor)</h2>
 * <p>
 * Si la contraseña se cambia porque alguien la conocía, sus sesiones deben
 * caer. La sesión actual «sigue» porque recibe un JWT y un refresh token
 * nuevos de una familia nueva: el usuario no nota nada.
 * </p>
 *
 * <p>
 * <b>Por qué se rota también la sesión actual en lugar de conservar su
 * familia.</b> Hay dos motivos. (1) El navegador no envía la cookie
 * {@code streambox_refresh} a esta ruta ({@code Path=/api/auth}), así que el
 * servidor no sabría qué familia es «la actual». (2) Aunque la supiera,
 * conservarla dejaría vivo un refresh token copiado de esta misma sesión
 * (justo el caso de quien cambia la contraseña porque sospecha). Con la
 * familia nueva, cualquier copia anterior deja de servir. Un cliente con
 * {@code Authorization: Bearer} recibe las mismas cookies nuevas; si no las
 * usa, su sesión termina cuando caduque su JWT.
 * </p>
 *
 * <h2>Lo que no puede hacer</h2>
 * <p>
 * Los JWT de acceso ya emitidos a las otras sesiones son stateless y siguen
 * valiendo hasta que caducan (15 minutos como mucho): durante ese rato esas
 * sesiones aún pueden hacer peticiones, pero ya no renovarse. Cerrarlas al
 * instante exigiría guardar en la cuenta una marca de «credenciales cambiadas
 * en» y rechazar los JWT emitidos antes (columna nueva).
 * </p>
 *
 * <h2>Transacciones y concurrencia</h2>
 * <p>
 * BCrypt (unos 100 ms por comparación) se ejecuta <b>fuera</b> de la
 * transacción, para no tener una conexión del pool ocupada mientras tanto; por
 * eso se usa un {@link TransactionTemplate} solo para las escrituras. Dentro,
 * la fila del usuario se bloquea y se comprueba que su hash sigue siendo el
 * que se verificó: si dos cambios de contraseña se cruzan, el segundo espera
 * al primero y, como su «contraseña actual» ya no lo es, recibe el 400
 * {@code CURRENT_PASSWORD_INCORRECT} en lugar de sobrescribirla. Guardar el
 * hash, revocar y abrir la familia nueva van en la misma transacción: o se
 * hace todo o nada (no puede quedar la contraseña cambiada con las sesiones
 * antiguas vivas).
 * </p>
 */
@Service
public class PasswordChangeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PasswordChangeService.class);

    /** Campo del formulario al que se asocian los errores de la contraseña nueva. */
    static final String NEW_PASSWORD_FIELD = "newPassword";

    /** Mensaje cuando la contraseña nueva es la misma que la actual. */
    public static final String SAME_PASSWORD_MESSAGE = "La nueva contraseña debe ser distinta de la actual";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordChangeAttemptService attemptService;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Crea el servicio.
     *
     * @param userRepository      repositorio de usuarios
     * @param passwordEncoder     BCrypt
     * @param attemptService      límite de intentos con la contraseña actual incorrecta
     * @param refreshTokenService revoca las sesiones y abre la nueva
     * @param jwtService          emite el JWT de acceso de la sesión actual
     * @param transactionManager  gestor de transacciones (para la parte de escritura)
     */
    public PasswordChangeService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            PasswordChangeAttemptService attemptService,
            RefreshTokenService refreshTokenService,
            JwtService jwtService,
            PlatformTransactionManager transactionManager) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.attemptService = attemptService;
        this.refreshTokenService = refreshTokenService;
        this.jwtService = jwtService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Cambia la contraseña del usuario y cierra todas sus otras sesiones.
     *
     * @param userId          id del usuario autenticado (sale del token, nunca del cliente)
     * @param currentPassword contraseña actual (ya validada en forma)
     * @param newPassword     contraseña nueva (longitud ya validada)
     * @return JWT de acceso y refresh token de la familia nueva de la sesión
     *         actual; el controlador los entrega en cookies
     * @throws InvalidParameterException          si la nueva no cumple la política
     *         o es igual a la actual (400 en {@code newPassword})
     * @throws CurrentPasswordIncorrectException  si la actual no es correcta (400)
     * @throws TooManyRequestsException           si se han agotado los intentos (429)
     * @throws UserNotFoundException              si la cuenta ya no existe
     */
    public SessionTokens changePassword(Long userId, String currentPassword, String newPassword) {

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));

        PasswordPolicy.findContentViolation(newPassword, user.getUsername(), user.getEmail())
                .ifPresent(message -> {
                    throw new InvalidParameterException(NEW_PASSWORD_FIELD, message);
                });

        String verifiedHash = user.getPassword();
        Attempt attempt = attemptService.reserve(userId);
        if (!passwordEncoder.matches(currentPassword, verifiedHash)) {
            attemptService.recordFailure(attempt);
            throw new CurrentPasswordIncorrectException();
        }
        attemptService.recordSuccess(attempt);

        if (passwordEncoder.matches(newPassword, verifiedHash)) {
            throw new InvalidParameterException(NEW_PASSWORD_FIELD, SAME_PASSWORD_MESSAGE);
        }
        String newHash = passwordEncoder.encode(newPassword);

        return transactionTemplate.execute(status -> replaceCredentials(userId, verifiedHash, newHash));
    }

    /**
     * Parte transaccional: guarda el hash nuevo, revoca todas las sesiones y
     * abre la de la sesión actual.
     */
    private SessionTokens replaceCredentials(Long userId, String verifiedHash, String newHash) {

        User locked = entityManager.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (locked == null) {
            throw new UserNotFoundException("Usuario no encontrado");
        }
        if (!verifiedHash.equals(locked.getPassword())) {
            // Otro cambio de contraseña se ha confirmado mientras se
            // comprobaba esta: la «actual» que envió ya no lo es.
            throw new CurrentPasswordIncorrectException();
        }
        locked.setPassword(newHash);
        // Antes de revocar: las sentencias masivas vacían la sesión de
        // Hibernate (la entidad queda desacoplada, aunque sus datos sirven).
        String accessToken = jwtService.generateToken(locked);

        // revokeAllSessions escribe antes el hash nuevo (flushAutomatically).
        int revoked = refreshTokenService.revokeAllSessions(userId);
        IssuedRefreshToken refreshToken = refreshTokenService.startFamily(userId);

        LOGGER.info("Contraseña cambiada por el usuario {}: {} refresh tokens revocados; la sesión actual "
                + "continúa con una familia nueva", userId, revoked);
        return new SessionTokens(accessToken, refreshToken);
    }
}
