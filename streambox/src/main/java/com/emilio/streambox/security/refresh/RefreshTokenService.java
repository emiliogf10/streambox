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
 *       usuario («cerrar sesión en todos los dispositivos», cambio y
 *       restablecimiento de la contraseña, confirmación del correo) y además
 *       anula al momento todos sus JWT de acceso (sube
 *       {@code users.token_version}).</li>
 *   <li><b>Gracia</b> ({@code reuse-grace}, 10 s): dos pestañas que despiertan
 *       a la vez mandan el mismo token; la segunda llega cuando la primera ya
 *       lo ha rotado. Si el token se rotó hace menos de la gracia y su sucesor
 *       sigue vivo, no es robo: se le da solo un JWT nuevo, sin rotar ni
 *       cambiar la cookie del refresh (el navegador ya tiene la del sucesor,
 *       que le llegó con la respuesta de la primera pestaña).</li>
 *   <li><b>Cuenta pendiente de confirmar el correo:</b> nunca se renueva
 *       (tampoco en la gracia) y se revocan todos sus refresh tokens (sus JWT
 *       ya los rechaza el filtro); ver {@code rejectIfEmailNotVerified}.</li>
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
     * <h3>Versión de tokens: se lee la primera, antes que el sucesor</h3>
     * <p>
     * Justo después de bloquear el token presentado se lee
     * {@code users.token_version} ({@code UserRepository.findTokenVersionById})
     * y el JWT se firma <b>con ese valor</b> en las dos ramas que emiten (la
     * gracia y la rotación normal), nunca con la entidad {@code User}, que se
     * carga más tarde (LAZY).
     * </p>
     *
     * <p>
     * <b>Por qué el orden «versión → sucesor» (BAJA-A).</b> En la gracia, el
     * token presentado (B) ya está revocado y su sucesor (C) lo bloquea solo
     * quien lo revoque: un «cerrar todas las sesiones» simultáneo
     * ({@link #revokeAllSessions(Long)}, transacción T1) no espera a esta
     * petición. En READ COMMITTED cada sentencia ve lo confirmado al empezar,
     * así que lo que importa es en qué momento confirma T1 respecto a las dos
     * lecturas:
     * </p>
     * <ul>
     *   <li><b>Antes de leer la versión:</b> T1 ya había revocado C, así que
     *       al leer el sucesor se ve revocado y no hay gracia (401), aunque la
     *       versión leída sea la nueva: no se emite nada.</li>
     *   <li><b>Entre la versión y el sucesor:</b> C se ve revocado; no hay
     *       gracia.</li>
     *   <li><b>Después de leer el sucesor:</b> hay gracia, pero el JWT lleva la
     *       versión <b>vieja</b>, y el filtro lo rechaza en cuanto T1 confirma.</li>
     * </ul>
     * <p>
     * En ningún caso sale un JWT con la versión nueva para una sesión que T1
     * ha cerrado. Con el orden contrario (el sucesor primero y la versión al
     * cargar el usuario, como antes), si T1 confirmaba entre las dos lecturas
     * se concedía la gracia (C se había visto vivo) <b>y</b> el JWT llevaba la
     * versión nueva: un JWT válido 15 minutos tras «cerrar todas las sesiones».
     * </p>
     *
     * <p>
     * En la rotación normal el orden también es correcto: B está vivo y
     * bloqueado por esta transacción, así que T1 no puede revocarlo (ni
     * confirmar) hasta que esta termine; el JWT lleva la versión vieja y la
     * segunda pasada de T1 revoca el sucesor recién creado. Se lee con una
     * consulta de un solo valor y no con {@code token.getUser()} para que el
     * resultado sea siempre el de la base de datos, aunque un llamante futuro
     * tenga ya el usuario en la sesión de Hibernate.
     * </p>
     *
     * @param presentedToken valor de la cookie {@code streambox_refresh} (puede
     *                       ser {@code null} si no venía)
     * @return el JWT nuevo y el refresh token sucesor; en la gracia de un
     *         refresh simultáneo, solo el JWT ({@code refreshToken} a
     *         {@code null})
     * @throws SessionExpiredException si el token falta, no tiene el formato
     *         esperado, no existe, ha caducado (él o su familia), está revocado
     *         o se ha reutilizado (en este caso, además, se revoca la familia),
     *         o si la cuenta está pendiente de confirmar el correo (se revocan
     *         todas sus sesiones; ver {@code rejectIfEmailNotVerified})
     */
    @Transactional(noRollbackFor = SessionExpiredException.class)
    public SessionTokens refresh(String presentedToken) {

        String hash = hashOrNull(presentedToken);
        if (hash == null) {
            throw new SessionExpiredException();
        }
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(SessionExpiredException::new);
        // Lo primero tras el bloqueo, antes de mirar el sucesor (gracia): ver
        // el Javadoc. getId() de la referencia LAZY no lanza consulta. Sin fila
        // (la cuenta se está borrando) no hay sesión que renovar.
        int tokenVersion = userRepository.findTokenVersionById(token.getUser().getId())
                .orElseThrow(SessionExpiredException::new);
        Instant now = clock.instant();

        if (token.getRevokedAt() != null) {
            if (isConcurrentRefresh(token, now)) {
                User user = token.getUser();
                rejectIfEmailNotVerified(user);
                return new SessionTokens(jwtService.generateToken(user, tokenVersion), null);
            }
            revokeFamilyOnReuse(token, now);
            throw new SessionExpiredException();
        }

        if (!now.isBefore(token.getExpiresAt()) || !now.isBefore(token.getFamilyExpiresAt())) {
            throw new SessionExpiredException();
        }

        User user = token.getUser();
        rejectIfEmailNotVerified(user);
        NewToken successor = issue(user, token.getFamilyId(), now, token.getFamilyExpiresAt());
        // El INSERT del sucesor ya se ha hecho (id IDENTITY); el UPDATE del
        // viejo (revocado + enlace) lo escribe Hibernate al confirmar, en la
        // misma transacción: o se rota del todo o no se rota.
        token.setRevokedAt(now);
        token.setReplacedBy(successor.entity());
        return new SessionTokens(jwtService.generateToken(user, tokenVersion), successor.issued());
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
     * Cierra <b>todas</b> las sesiones del usuario: anula sus JWT de acceso y
     * revoca los refresh tokens activos de todas sus familias.
     *
     * <p>
     * Lo llaman {@code POST /api/auth/logout-all} (el controlador no es
     * transaccional: este método abre la suya), el cambio de contraseña
     * ({@code PasswordChangeService}), el restablecimiento
     * ({@code PasswordResetService}) y la confirmación del correo
     * ({@code EmailVerificationService}); todos, salvo el primero, desde su
     * propia transacción, a la que este método se une. El refresh de una
     * cuenta pendiente <b>no</b> lo usa: solo revoca los refresh, sin subir la
     * versión (ver {@code rejectIfEmailNotVerified}).
     * </p>
     *
     * <h3>JWT de acceso: {@code token_version}</h3>
     * <p>
     * Primero se suma 1 a {@code users.token_version}
     * ({@code UserRepository.incrementTokenVersion}): desde que la transacción
     * confirma, {@code JwtAuthenticationFilter} rechaza todos los JWT emitidos
     * antes (su claim {@code ver} ya no coincide), también el de un intruso al
     * que le quedaran minutos. Está aquí, y no en cada servicio, para que
     * cualquier flujo futuro que «cierre todas las sesiones» no pueda olvidarse
     * de los JWT: revocar los refresh y la versión van siempre juntos y en la
     * misma transacción (o las dos cosas o ninguna).
     * </p>
     *
     * <p>
     * <b>Por qué el incremento va antes de revocar los refresh.</b> El orden de
     * bloqueos queda igual que en los demás flujos que cierran sesiones (los
     * servicios de contraseña bloquean la fila de {@code users} y luego tocan
     * {@code refresh_tokens}): dos de ellos a la vez quedan en fila en la fila
     * del usuario, sin interbloqueo.
     * </p>
     *
     * <p>
     * <b>Carreras con un refresh o un login simultáneos.</b> Son seguras porque
     * la versión se compara en cada petición, no al emitir:
     * </p>
     * <ul>
     *   <li>Un refresh que leyó al usuario <em>antes</em> de que esta
     *       transacción confirmara firma su JWT con la versión vieja: el filtro
     *       lo rechaza en cuanto esto confirma, y su refresh sucesor lo revoca
     *       la segunda pasada (ver abajo).</li>
     *   <li>Un refresh que llega <em>después</em> encuentra su token revocado
     *       (sin sucesor: no hay gracia) y recibe 401.</li>
     *   <li>La gracia de las pestañas simultáneas
     *       ({@code isConcurrentRefresh}) exige que el sucesor siga vivo, y aquí
     *       se revoca. Pero esta transacción no bloquea al refresh que entra en
     *       la gracia (su token ya estaba revocado: el {@code UPDATE} no lo
     *       toca), así que puede confirmar <em>mientras</em> ese refresh se
     *       ejecuta. Por eso {@link #refresh(String)} lee la versión <b>antes</b>
     *       que el sucesor y firma con ella: si esto confirma antes de que lea
     *       el sucesor, no hay gracia; si confirma después, la gracia emite un
     *       JWT con la versión vieja, que el filtro rechaza. Nunca uno con la
     *       nueva (BAJA-A; el razonamiento completo, en {@link #refresh(String)}).</li>
     *   <li>Un login que termine a la vez puede llevar la versión vieja (su
     *       JWT se rechaza; el frontend renueva) o la nueva; su familia nueva
     *       puede quedar fuera (ver abajo). Es un login con la contraseña
     *       válida en ese instante, no una sesión anterior que sobreviva.</li>
     * </ul>
     *
     * <p>
     * La <b>reutilización</b> de un refresh ya rotado ({@code revokeFamilyOnReuse})
     * no pasa por aquí ni sube la versión, a propósito: revoca solo la familia
     * robada. Subir la versión cerraría también los demás dispositivos del
     * titular por el robo de una sola sesión; el JWT de acceso del ladrón vive
     * como mucho {@code jwt.access-token-ttl}, y el titular puede cerrar todas
     * sus sesiones si sospecha algo.
     * </p>
     *
     * <h3>Refresh tokens: dos pasadas</h3>
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
     * anterior que sobreviva.
     * </p>
     *
     * <p>
     * Se une a la transacción de quien llama (el cambio de contraseña revoca y
     * abre la familia nueva en la misma). Las sentencias masivas vacían la
     * sesión de Hibernate: las entidades cargadas antes quedan desacopladas y
     * su {@code getTokenVersion()} es la vieja. Quien emita un JWT después
     * (el cambio de contraseña, para la sesión actual) debe leer la versión con
     * {@code UserRepository.findTokenVersionById}.
     * </p>
     *
     * @param userId id del usuario (sale del token, nunca del cliente)
     * @return número de refresh tokens revocados entre las dos pasadas
     */
    @Transactional
    public int revokeAllSessions(Long userId) {

        // Si la cuenta no existe (0 filas), tampoco tiene tokens que revocar.
        userRepository.incrementTokenVersion(userId);
        return revokeAllRefreshTokensOf(userId);
    }

    /**
     * Revoca todos los refresh tokens activos del usuario, en dos pasadas (ver
     * «Refresh tokens: dos pasadas» en {@link #revokeAllSessions(Long)}), sin
     * tocar {@code users.token_version}.
     *
     * <p>
     * Es la parte de {@link #revokeAllSessions(Long)} que no bloquea la fila
     * de {@code users}; la usa también, sola, {@code rejectIfEmailNotVerified}.
     * </p>
     *
     * @param userId id del usuario
     * @return número de refresh tokens revocados entre las dos pasadas
     */
    private int revokeAllRefreshTokensOf(Long userId) {

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
     * Rechaza la renovación si la cuenta está pendiente de confirmar el correo,
     * y de paso revoca <b>todas</b> sus sesiones.
     *
     * <p>
     * <b>Por qué.</b> Desde la {@code V9}, las cuentas {@code USER} que ya
     * existían quedaron pendientes, pero pueden tener sesiones abiertas de
     * antes (refresh tokens de hasta 30 días). Una cuenta pendiente no puede
     * iniciar sesión (el login la trata como un intento fallido), así que tampoco debe
     * poder <i>seguir</i> en una sesión: si no, la verificación se esquivaría
     * renovando la cookie. Además, una sesión abierta antes de demostrar el
     * correo pudo abrirla otra persona que se registró con un email ajeno: si el
     * titular real confirma después el correo, esas sesiones no deben
     * «despertar». Por eso se revocan todas las familias del usuario y no solo
     * la presentada (cuesta un {@code UPDATE}; las demás se rechazarían igual
     * al presentarse, pero así quedan cerradas aunque no se presenten).
     * </p>
     *
     * <p>
     * Se comprueba después de las demás condiciones (revocado, caducado), para
     * que la reutilización de un token se siga detectando y registrando como
     * siempre. El mismo 401 {@code SESSION_EXPIRED} que cualquier otro fallo:
     * el frontend vuelve al login, y allí la interfaz le recuerda confirmar el
     * correo. La
     * transacción confirma la revocación pese a la excepción
     * ({@code noRollbackFor} en {@link #refresh(String)}).
     * </p>
     *
     * <p>
     * <b>Por qué solo los refresh y no {@link #revokeAllSessions(Long)}
     * (BAJA-B).</b> Aquí ya se tiene bloqueada la fila del token presentado
     * ({@code refresh_tokens}); subir la versión bloquearía después la de
     * {@code users}. La confirmación del correo y el restablecimiento de la
     * contraseña bloquean en el orden contrario ({@code users} y luego
     * {@code refresh_tokens}): si coincidían, PostgreSQL detectaba el
     * interbloqueo y abortaba una de las dos (un 500). Subir la versión aquí
     * tampoco aporta nada:
     * </p>
     * <ul>
     *   <li>{@code JwtAuthenticationFilter} ya rechaza <b>cualquier</b> JWT de
     *       una cuenta pendiente, lleve la versión que lleve;</li>
     *   <li>las dos formas de que una cuenta de usuario pase a verificada
     *       mientras la aplicación funciona (confirmar el correo y restablecer
     *       la contraseña) llaman a {@link #revokeAllSessions(Long)} en la misma
     *       transacción que la marcan, así que suben la versión justo cuando
     *       los JWT antiguos volverían a valer. La tercera,
     *       {@code AdminAccountInitializer} al arrancar, solo verifica una
     *       cuenta ADMIN que alguien dejó pendiente a mano, sin subir la
     *       versión: un caso que no debería darse (la V9 verificó a todos los
     *       ADMIN).</li>
     * </ul>
     * <p>
     * Se conservan las dos pasadas de {@code revokeAllByUserId} (el mismo
     * método privado que usa {@link #revokeAllSessions(Long)}): son las que
     * alcanzan a un sucesor creado por una rotación simultánea. Para una
     * cuenta pendiente esa rotación ni siquiera llega a crear sucesor (también
     * la rechaza este método), pero así el comportamiento no depende de ello.
     * </p>
     *
     * <p>
     * <b>Lo que queda (aceptado).</b> Si la cuenta tiene <i>más</i> de un
     * refresh activo, la confirmación y este método aún pueden cruzarse entre
     * filas de {@code refresh_tokens} (cada uno tiene una que el otro quiere
     * revocar). Ya ocurría antes de la versión, exige una cuenta heredada con
     * varias sesiones que renueve en el mismo instante en que confirma su
     * correo, y no tiene efecto de seguridad: PostgreSQL aborta una de las
     * dos, la otra confirma, y la confirmación se puede repetir con el mismo
     * enlace si fue ella la abortada (el token no se gasta al deshacerse).
     * </p>
     *
     * @param user dueño del token presentado
     * @throws SessionExpiredException si la cuenta está pendiente
     */
    private void rejectIfEmailNotVerified(User user) {

        if (user.isEmailVerified()) {
            return;
        }
        Long userId = user.getId();
        int revoked = revokeAllRefreshTokensOf(userId);
        LOGGER.debug("Refresh de una cuenta pendiente de confirmar el correo (usuario {}): se revocan sus "
                + "sesiones ({} tokens activos)", userId, revoked);
        throw new SessionExpiredException();
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
