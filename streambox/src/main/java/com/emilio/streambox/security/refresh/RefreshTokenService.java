package com.emilio.streambox.security.refresh;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

/**
 * Emite, rota y revoca los refresh tokens (tarea 29).
 *
 * <h2>Qué es y por qué existe</h2>
 * <p>
 * El JWT de acceso dura minutos ({@code jwt.access-token-ttl}): si alguien lo
 * copia, le sirve muy poco tiempo. Para no pedir la contraseña cada 15 minutos,
 * el login entrega además un refresh token, con el que
 * {@code POST /api/auth/refresh} obtiene un JWT nuevo. A diferencia del JWT,
 * el refresh token se guarda en la base de datos ({@code refresh_tokens}), así
 * que se puede revocar: el logout cierra la sesión de verdad.
 * </p>
 *
 * <h2>Formato</h2>
 * <p>
 * Opaco: 32 bytes de {@link SecureRandom} (256 bits) en Base64URL sin relleno.
 * No es un JWT porque no tiene que llevar datos: el servidor lo busca en la
 * tabla. En la base de datos solo está su SHA-256 en hexadecimal: quien lea la
 * tabla (una copia de seguridad) no puede usarlo. Un hash rápido basta porque
 * el token es aleatorio (no se puede adivinar por diccionario como una
 * contraseña).
 * </p>
 *
 * <h2>Rotación y detección de robo</h2>
 * <ul>
 *   <li>Cada login abre una <b>familia</b> con un tope absoluto
 *       ({@code streambox.auth.refresh.family-ttl}, 30 días).</li>
 *   <li>Cada refresh <b>rota</b>: el token usado queda revocado y enlazado a su
 *       sucesor, que vive {@code ttl} (7 días) sin pasar del tope.</li>
 *   <li>Si llega un token ya revocado, alguien tiene una copia (el legítimo
 *       siempre usa el último): se revoca <b>toda la familia</b>, y el ladrón y
 *       la víctima tienen que volver a iniciar sesión. Se registra a
 *       {@code WARN} con el usuario y la familia, nunca el token (solo si
 *       quedaba algún token vivo; ver {@code revokeFamilyOnReuse}).</li>
 *   <li>{@link #revokeAllSessions(Long)} cierra todas las familias del
 *       usuario («cerrar sesión en todos los dispositivos» y cambio de
 *       contraseña).</li>
 *   <li><b>Gracia</b> ({@code reuse-grace}, 10 s): dos pestañas que despiertan
 *       a la vez mandan el mismo token; la segunda llega cuando la primera ya
 *       lo ha rotado. Si el token se rotó hace menos de la gracia y su sucesor
 *       sigue vivo, no es robo: se le da solo un JWT nuevo, sin rotar ni
 *       cambiar la cookie del refresh (el navegador ya tiene la del sucesor,
 *       que le llegó con la respuesta de la primera pestaña).</li>
 * </ul>
 *
 * <h2>Concurrencia</h2>
 * <p>
 * {@link RefreshTokenRepository#findByTokenHashForUpdate(String)} bloquea la
 * fila hasta el final de la transacción: dos refresh simultáneos con el mismo
 * token no pueden rotar los dos; el segundo espera y ve el token ya rotado
 * (gracia). Dentro de la transacción no hay llamadas externas (firmar el JWT
 * es solo cálculo), para que el bloqueo dure poco. Revocar una familia
 * (reutilización o logout) mientras otra petición rota su token vigente se
 * resuelve con dos pasadas, ver {@code revokeWholeFamily}.
 * </p>
 *
 * <p>
 * Todos los fallos lanzan {@link SessionExpiredException} (401
 * {@code SESSION_EXPIRED}, mismo cuerpo siempre). El método de refresh no
 * deshace la transacción con esa excepción: la revocación de la familia por
 * reutilización tiene que confirmarse aunque la respuesta sea un error.
 * </p>
 */
@Service
public class RefreshTokenService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenService.class);

    /** Bytes aleatorios de cada token (256 bits). */
    private static final int TOKEN_BYTES = 32;

    /** Longitud del token en Base64URL sin relleno: ceil(32 * 4 / 3). */
    static final int TOKEN_LENGTH = 43;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final HexFormat HEX = HexFormat.of();

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /**
     * Crea el servicio.
     *
     * @param refreshTokenRepository repositorio de los refresh tokens
     * @param userRepository         repositorio de usuarios (referencia al dueño)
     * @param jwtService             emite los JWT de acceso
     * @param properties             vidas, gracia y limpieza
     * @param clock                  reloj de la aplicación (fechas de los tokens)
     */
    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            JwtService jwtService,
            RefreshTokenProperties properties,
            Clock clock) {

        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Abre una familia nueva para un login correcto.
     *
     * @param userId id del usuario que acaba de autenticarse
     * @return el primer token de la familia, en claro, y su vida
     */
    @Transactional
    public IssuedRefreshToken startFamily(Long userId) {

        Instant now = clock.instant();
        Instant familyExpiresAt = now.plus(properties.familyTtl());
        return issue(userRepository.getReferenceById(userId), UUID.randomUUID(), now, familyExpiresAt).issued();
    }

    /**
     * Renueva la sesión con un refresh token: rota y emite un JWT nuevo.
     *
     * @param presentedToken valor de la cookie {@code streambox_refresh} (puede
     *                       ser {@code null} si no venía)
     * @return el JWT nuevo y el refresh token sucesor; en la gracia de un
     *         refresh simultáneo, solo el JWT ({@code refreshToken} a
     *         {@code null})
     * @throws SessionExpiredException si el token falta, no tiene el formato
     *         esperado, no existe, ha caducado (él o su familia), está revocado
     *         o se ha reutilizado (en este caso, además, se revoca la familia)
     */
    @Transactional(noRollbackFor = SessionExpiredException.class)
    public SessionTokens refresh(String presentedToken) {

        String hash = hashOrNull(presentedToken);
        if (hash == null) {
            throw new SessionExpiredException();
        }
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(SessionExpiredException::new);
        Instant now = clock.instant();

        if (token.getRevokedAt() != null) {
            if (isConcurrentRefresh(token, now)) {
                return new SessionTokens(jwtService.generateToken(token.getUser()), null);
            }
            revokeFamilyOnReuse(token, now);
            throw new SessionExpiredException();
        }

        if (!now.isBefore(token.getExpiresAt()) || !now.isBefore(token.getFamilyExpiresAt())) {
            throw new SessionExpiredException();
        }

        User user = token.getUser();
        NewToken successor = issue(user, token.getFamilyId(), now, token.getFamilyExpiresAt());
        // El INSERT del sucesor ya se ha hecho (id IDENTITY); el UPDATE del
        // viejo (revocado + enlace) lo escribe Hibernate al confirmar, en la
        // misma transacción: o se rota del todo o no se rota.
        token.setRevokedAt(now);
        token.setReplacedBy(successor.entity());
        return new SessionTokens(jwtService.generateToken(user), successor.issued());
    }

    /**
     * Revoca la familia del token (logout). No falla nunca por el token: si
     * falta, no tiene formato válido o no existe, no hace nada; el logout es
     * idempotente.
     *
     * @param presentedToken valor de la cookie {@code streambox_refresh}, o {@code null}
     */
    @Transactional
    public void revokeFamilyOf(String presentedToken) {

        String hash = hashOrNull(presentedToken);
        if (hash == null) {
            return;
        }
        refreshTokenRepository.findByTokenHashForUpdate(hash)
                .ifPresent(token -> revokeWholeFamily(token.getFamilyId(), clock.instant()));
    }

    /**
     * Cierra <b>todas</b> las sesiones del usuario: revoca los tokens activos
     * de todas sus familias ({@code POST /api/auth/logout-all} y cambio de
     * contraseña).
     *
     * <p>
     * Dos pasadas, por la misma carrera de READ COMMITTED que
     * {@code revokeWholeFamily}: si otra petición está rotando un token del
     * usuario (B → C), la primera sentencia espera a B y lo salta (ya
     * revocado), pero no ve C, insertado después de empezar; la segunda, que
     * empieza cuando la rotación ya ha confirmado, sí lo revoca. Una rotación
     * que empiece después de la primera pasada encuentra su token ya
     * bloqueado por esta transacción, espera y lo lee revocado.
     * </p>
     *
     * <p>
     * Lo que no cubre: un <b>login</b> que termine a la vez abre una familia
     * nueva que puede quedar fuera (no hay ninguna fila previa que bloquear).
     * Es un login con credenciales válidas en ese instante, no una sesión
     * anterior que sobreviva. Tampoco revoca los JWT de acceso ya emitidos:
     * son stateless y siguen valiendo hasta caducar (15 minutos como mucho),
     * pero ya no se pueden renovar.
     * </p>
     *
     * <p>
     * Se une a la transacción de quien llama (el cambio de contraseña revoca y
     * abre la familia nueva en la misma). Las sentencias masivas vacían la
     * sesión de Hibernate: las entidades cargadas antes quedan desacopladas.
     * </p>
     *
     * @param userId id del usuario (sale del token, nunca del cliente)
     * @return número de tokens revocados entre las dos pasadas
     */
    @Transactional
    public int revokeAllSessions(Long userId) {

        Instant now = clock.instant();
        int revoked = refreshTokenRepository.revokeAllByUserId(userId, now);
        return revoked + refreshTokenRepository.revokeAllByUserId(userId, now);
    }

    /**
     * Revoca todos los tokens activos de la familia, también el sucesor que
     * una rotación simultánea esté creando en ese momento.
     *
     * <p>
     * <b>Por qué dos pasadas.</b> En READ COMMITTED (el nivel por defecto de
     * PostgreSQL), cada sentencia ve los datos confirmados al empezar. Si otra
     * transacción está rotando el token vigente de la familia (B → C), el
     * primer {@code UPDATE} espera al bloqueo de B y, cuando la rotación
     * confirma, PostgreSQL vuelve a evaluar B (ya revocado: lo salta), pero C
     * se insertó después de empezar la sentencia y no lo ve: C quedaría
     * <b>vivo</b> pese a la revocación (una reutilización detectada o un
     * logout no cerrarían la sesión de quien tuviera C). La segunda sentencia
     * empieza cuando la rotación ya ha confirmado, así que ve C y lo revoca.
     * </p>
     *
     * <p>
     * No hace falta repetir más veces: una rotación siempre bloquea el token
     * que rota desde su primera sentencia, así que el primer {@code UPDATE}
     * espera a cualquier rotación en curso de esta familia, y una rotación
     * nueva del sucesor exigiría que su dueño recibiera C y volviera a llamar
     * en los microsegundos que separan las dos sentencias. Si no había
     * ninguna rotación en curso, la segunda pasada no cambia nada.
     * </p>
     *
     * @param familyId familia que se revoca
     * @param now      instante de la revocación
     * @return número de tokens revocados entre las dos pasadas
     */
    private int revokeWholeFamily(UUID familyId, Instant now) {

        int revoked = refreshTokenRepository.revokeFamily(familyId, now);
        return revoked + refreshTokenRepository.revokeFamily(familyId, now);
    }

    /**
     * Borra los tokens que ya no sirven: los de familias caducadas y los
     * caducados hace más de {@code cleanup.retention}. Lo llama la tarea
     * programada ({@link RefreshTokenCleanupConfig}).
     *
     * @return número de tokens borrados
     */
    @Transactional
    public int deleteExpired() {

        Instant now = clock.instant();
        return refreshTokenRepository.deleteExpired(now, now.minus(properties.cleanup().retention()));
    }

    /**
     * Token recién guardado: la entidad (para enlazarla como sucesor) y el
     * valor en claro para la cookie. Es un valor de retorno y no un campo
     * porque el servicio es un singleton compartido por todas las peticiones.
     */
    private record NewToken(RefreshToken entity, IssuedRefreshToken issued) {
    }

    /**
     * Crea y guarda un token de la familia.
     */
    private NewToken issue(User user, UUID familyId, Instant now, Instant familyExpiresAt) {

        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String value = ENCODER.encodeToString(bytes);

        Instant expiresAt = min(now.plus(properties.ttl()), familyExpiresAt);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(sha256Hex(value));
        token.setFamilyId(familyId);
        token.setCreatedAt(now);
        token.setExpiresAt(expiresAt);
        token.setFamilyExpiresAt(familyExpiresAt);
        RefreshToken saved = refreshTokenRepository.save(token);

        return new NewToken(saved, new IssuedRefreshToken(value, Duration.between(now, expiresAt)));
    }

    /**
     * Gracia de las pestañas simultáneas: el token se rotó (tiene sucesor, no
     * lo revocó un logout ni una reutilización) hace menos de
     * {@code reuse-grace} y el sucesor sigue vivo.
     */
    private boolean isConcurrentRefresh(RefreshToken token, Instant now) {

        RefreshToken successor = token.getReplacedBy();
        if (successor == null || !now.isBefore(token.getRevokedAt().plus(properties.reuseGrace()))) {
            return false;
        }
        return successor.getRevokedAt() == null
                && now.isBefore(successor.getExpiresAt())
                && now.isBefore(successor.getFamilyExpiresAt());
    }

    /**
     * Uso de un token ya revocado (fuera de la gracia). Se revoca la familia
     * entera y, si quedaba algún token vivo, se avisa a {@code WARN}, con el
     * usuario y la familia para poder investigar, nunca con el token ni su
     * hash.
     *
     * <p>
     * <b>Por qué solo avisa si revoca algo.</b> Si la familia tenía un token
     * vivo, alguien la estaba usando con un token más nuevo que el presentado:
     * hay dos copias de la sesión, posible robo. Si ya no quedaba ninguno, la
     * sesión estaba cerrada (logout, «cerrar sesión en todos los
     * dispositivos», cambio de contraseña o un robo ya detectado) y lo normal
     * es que sea el propio dispositivo, que aún no lo sabe, el que intenta
     * renovar: no hay nada que proteger y un {@code WARN} sería una falsa
     * alarma por cada dispositivo tras cerrar todas las sesiones. Va a
     * {@code DEBUG}. La respuesta es el mismo 401 en los dos casos.
     * </p>
     */
    private void revokeFamilyOnReuse(RefreshToken token, Instant now) {

        // getId() de la referencia LAZY no lanza consulta. Se lee antes de la
        // revocación masiva, que vacía la sesión de Hibernate.
        Long userId = token.getUser().getId();
        UUID familyId = token.getFamilyId();
        int revoked = revokeWholeFamily(familyId, now);
        if (revoked > 0) {
            LOGGER.warn("Reutilización de un refresh token ya revocado (posible robo): se revoca la familia {} "
                    + "del usuario {} ({} tokens activos revocados)", familyId, userId, revoked);
        } else {
            LOGGER.debug("Refresh con un token de una sesión ya cerrada (familia {} del usuario {})",
                    familyId, userId);
        }
    }

    /**
     * SHA-256 en hexadecimal en minúsculas del token, o {@code null} si el
     * valor no tiene la forma de un token propio (así no se consulta la base
     * de datos por cualquier cosa que llegue en la cookie).
     */
    static String hashOrNull(String presentedToken) {

        return hasTokenFormat(presentedToken) ? sha256Hex(presentedToken) : null;
    }

    /**
     * Indica si un valor tiene la forma de un token emitido por este servicio:
     * exactamente {@value #TOKEN_LENGTH} caracteres Base64URL sin relleno.
     *
     * <p>
     * Es pública para que {@code AuthController} rechace un refresh sin cookie
     * (o con un valor imposible) <b>antes</b> de llamar a {@link #refresh}:
     * ese método es transaccional, y solo abrir la transacción ya toma una
     * conexión del pool aunque no llegue a consultar nada. Cada visita anónima
     * hace un refresh sin cookie (JavaScript no puede saber si la cookie
     * HttpOnly existe), así que ese caso no debe costar ni una conexión.
     * </p>
     *
     * @param presentedToken valor de la cookie, o {@code null}
     * @return {@code true} si el valor puede ser un token propio (aunque no
     *         exista o esté revocado: eso solo lo sabe la base de datos)
     */
    public static boolean hasTokenFormat(String presentedToken) {

        if (presentedToken == null || presentedToken.length() != TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < presentedToken.length(); i++) {
            char c = presentedToken.charAt(i);
            boolean base64Url = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!base64Url) {
                return false;
            }
        }
        return true;
    }

    /**
     * SHA-256 en hexadecimal en minúsculas (el formato que exige el
     * {@code CHECK} de la tabla).
     */
    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            // Todas las JVM deben incluir SHA-256 (especificación de MessageDigest).
            throw new IllegalStateException("La JVM no tiene SHA-256", e);
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
