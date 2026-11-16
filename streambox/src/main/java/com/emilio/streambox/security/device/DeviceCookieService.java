package com.emilio.streambox.security.device;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.security.JwtProperties;

/**
 * Emite y valida la <b>cookie de dispositivo</b> {@code streambox_device}
 * (patrón «device cookies» de OWASP), que evita que el bloqueo de una cuenta
 * por intentos fallidos se use como arma contra su titular.
 *
 * <h2>Qué problema resuelve</h2>
 * <p>
 * Con 5 fallos en 15 minutos, cualquiera que conozca un email bloquea la
 * cuenta; repitiéndolo, la mantiene bloqueada. Las «IPs conocidas»
 * ({@code LoginAttemptService}) solo lo mitigan a medias: viven en memoria (un
 * reinicio las borra), no sirven desde una IP nueva y se comparten bajo una IP
 * común. Esta cookie identifica al <b>navegador</b> en el que el titular ya
 * inició sesión con éxito: con ella, el login usa un contador propio (email +
 * id de dispositivo) con los mismos límites, al que no afectan los fallos de
 * los demás.
 * </p>
 *
 * <h2>Formato</h2>
 * <p>
 * {@code v1.<payload>.<firma>}, con {@code payload} y {@code firma} en
 * Base64URL sin relleno:
 * </p>
 * <ul>
 *   <li><b>payload</b>: {@code idUsuario:tokenVersion:idDispositivo:emitidaEn}
 *       (segundos desde 1970). Sin email ni otros datos personales: el id
 *       numérico ya identifica la cuenta y no dice nada a quien lea la
 *       cookie. El id de dispositivo son {@value #DEVICE_ID_BYTES} bytes de
 *       {@link SecureRandom} (128 bits): imposible de adivinar o de repetir.</li>
 *   <li><b>firma</b>: HMAC-SHA256 de {@code v1.<payload>} (la versión va
 *       firmada: no se puede reetiquetar una cookie como de otro formato).</li>
 * </ul>
 * <p>
 * La versión del formato permite cambiarlo en el futuro (otra etiqueta, otra
 * clave) sin confundir cookies antiguas con nuevas: una versión desconocida es
 * una cookie ausente.
 * </p>
 *
 * <h2>Clave: derivada del secreto JWT con separación de dominio</h2>
 * <p>
 * Se calcula una vez al arrancar como HMAC-SHA256 con el secreto JWT como
 * clave sobre la etiqueta fija {@value #KEY_LABEL}. Así no hace falta una
 * variable de entorno nueva (no rompe el arranque local, Docker ni el CI) y,
 * aun así, la clave es <b>distinta</b> de la de los JWT: una firma de un
 * contexto nunca vale en el otro (un JWT no se puede presentar como cookie de
 * dispositivo ni al revés). Rotar {@code JWT_SECRET} invalida también estas
 * cookies; es aceptable, porque ya cierra todas las sesiones.
 * </p>
 *
 * <h2>Validez: cualquier duda es «cookie ausente»</h2>
 * <p>
 * Una cookie solo sirve si su firma es correcta, no ha caducado (vida
 * {@code streambox.security.device-cookie.ttl} desde su emisión), es del
 * usuario del email con el que se intenta entrar y lleva su
 * {@code token_version} actual. Cualquier otra cosa (vacía, enorme, mal
 * formada, otra versión, firma incorrecta, caducada, de otra cuenta o de una
 * versión anterior) se trata exactamente como si no hubiera cookie: nunca un
 * error ni un 500, y por tanto la misma respuesta que sin ella.
 * </p>
 *
 * <h2>Revocación persistente con {@code token_version}</h2>
 * <p>
 * «Cerrar sesión en todos los dispositivos», cambiar o restablecer la
 * contraseña y confirmar el correo suben {@code users.token_version}
 * ({@code RefreshTokenService.revokeAllSessions}): desde ese momento todas las
 * cookies de dispositivo anteriores dejan de valer, sin guardar nada en el
 * servidor y aunque se reinicie. Quien entró con una contraseña robada pierde
 * su presupuesto propio en cuanto el titular reacciona.
 * </p>
 *
 * <p>
 * La firma se compara en tiempo constante ({@link MessageDigest#isEqual}),
 * contra la firma <b>esperada ya codificada</b>: no se decodifica la que manda
 * el cliente, así que tampoco hay codificaciones alternativas de una misma
 * firma. Es seguro para uso concurrente ({@link Mac} se crea en cada llamada,
 * porque no es thread-safe; cuesta microsegundos).
 * </p>
 */
@Component
public class DeviceCookieService {

    /** Versión del formato (primer campo del valor). */
    public static final String FORMAT_VERSION = "v1";

    /** Etiqueta con la que se deriva la clave del secreto JWT (separación de dominio). */
    static final String KEY_LABEL = "streambox:device-cookie:v1";

    /** Bytes aleatorios del id de dispositivo (128 bits). */
    static final int DEVICE_ID_BYTES = 16;

    /**
     * Longitud máxima del valor que se llega a analizar. Una cookie legítima
     * no pasa de unos 150 caracteres; con cualquier cosa más larga ni se
     * calcula el HMAC (trabajo acotado por petición).
     */
    static final int MAX_LENGTH = 256;

    /**
     * Margen para una fecha de emisión en el futuro (un ajuste del reloj del
     * servidor). Más allá, la cookie se descarta: solo podría haberla firmado
     * un servidor con la hora mal.
     */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private static final String ALGORITHM = "HmacSHA256";

    private static final char SEPARATOR = '.';

    /**
     * Payload en claro: id de usuario positivo, versión no negativa, id de
     * dispositivo de 22 caracteres Base64URL (16 bytes) y segundos de
     * emisión. Sin ceros a la izquierda: un único texto por cada valor.
     */
    private static final Pattern PAYLOAD = Pattern.compile(
            "([1-9][0-9]{0,18}):(0|[1-9][0-9]{0,9}):([A-Za-z0-9_-]{22}):(0|[1-9][0-9]{0,18})");

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    private final Duration ttl;

    private final Clock clock;

    private final SecureRandom random = new SecureRandom();

    /**
     * Deriva la clave del secreto JWT (una sola vez).
     *
     * @param jwtProperties secreto JWT (ya validado: al menos 32 caracteres)
     * @param properties    vida de la cookie
     * @param clock         reloj de la aplicación
     */
    public DeviceCookieService(JwtProperties jwtProperties, DeviceCookieProperties properties, Clock clock) {
        byte[] secret = jwtProperties.secret().getBytes(StandardCharsets.UTF_8);
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(secret, ALGORITHM), KEY_LABEL), ALGORITHM);
        this.ttl = properties.ttl();
        this.clock = clock;
    }

    /**
     * Emite una cookie para un dispositivo <b>nuevo</b> (id aleatorio). Se usa
     * tras cambiar o restablecer la contraseña, donde el navegador no envía la
     * cookie anterior (o ya no vale).
     *
     * @param userId       id de la cuenta
     * @param tokenVersion {@code users.token_version} <b>actual</b> de la cuenta
     *                     (tras cualquier incremento de la misma operación)
     * @return valor y vida de la cookie
     */
    public IssuedDeviceToken issueNew(Long userId, int tokenVersion) {
        return issue(userId, tokenVersion, null);
    }

    /**
     * Emite (o renueva) la cookie de un dispositivo.
     *
     * <p>
     * Solo debe llamarse tras probar la contraseña (login correcto de una
     * cuenta confirmada) o el buzón (restablecimiento): es la misma condición
     * que hace «conocida» una IP.
     * </p>
     *
     * @param userId       id de la cuenta
     * @param tokenVersion {@code users.token_version} de la cuenta al emitir
     * @param deviceId     id del dispositivo de una cookie válida que se renueva
     *                     (conserva su identidad), o {@code null} para uno nuevo
     * @return valor y vida de la cookie
     */
    public IssuedDeviceToken issue(Long userId, int tokenVersion, String deviceId) {
        String id = deviceId != null ? deviceId : newDeviceId();
        long issuedAt = clock.instant().getEpochSecond();
        String payload = userId + ":" + tokenVersion + ":" + id + ":" + issuedAt;
        String signedPart = FORMAT_VERSION + SEPARATOR
                + ENCODER.encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
        return new IssuedDeviceToken(signedPart + SEPARATOR + sign(signedPart), ttl);
    }

    /**
     * Id del dispositivo si la cookie es válida <b>para esta cuenta</b>.
     *
     * <p>
     * La firma se comprueba siempre, también si {@code user} es {@code null}
     * (email inexistente): el trabajo no depende de si la cuenta existe.
     * </p>
     *
     * @param rawValue valor de la cookie tal como llega, o {@code null}
     * @param user     cuenta del email del login, o {@code null} si no existe
     * @return el id de dispositivo, o vacío si la cookie falta o no vale para
     *         esa cuenta (cualquier motivo, sin distinguirlos)
     */
    public Optional<String> deviceIdFor(String rawValue, User user) {
        Optional<DeviceToken> token = verify(rawValue);
        if (user == null || user.getId() == null) {
            return Optional.empty();
        }
        return token
                .filter(t -> t.userId() == user.getId().longValue())
                .filter(t -> t.tokenVersion() == user.getTokenVersion())
                .map(DeviceToken::deviceId);
    }

    /**
     * Comprueba formato, versión, firma y caducidad (no la cuenta).
     *
     * @param rawValue valor de la cookie, o {@code null}
     * @return su contenido, o vacío si no vale por cualquier motivo
     */
    Optional<DeviceToken> verify(String rawValue) {
        if (rawValue == null || rawValue.isEmpty() || rawValue.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        int first = rawValue.indexOf(SEPARATOR);
        int last = rawValue.lastIndexOf(SEPARATOR);
        // Exactamente dos separadores.
        if (first < 0 || first == last || rawValue.indexOf(SEPARATOR, first + 1) != last) {
            return Optional.empty();
        }
        if (!FORMAT_VERSION.equals(rawValue.substring(0, first))) {
            return Optional.empty();
        }
        String signedPart = rawValue.substring(0, last);
        byte[] expected = sign(signedPart).getBytes(StandardCharsets.US_ASCII);
        byte[] received = rawValue.substring(last + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, received)) {
            return Optional.empty();
        }
        // Desde aquí el contenido lo firmó este servidor; aun así, ningún
        // fallo de análisis puede convertirse en un error de la petición.
        try {
            return parsePayload(rawValue.substring(first + 1, last)).filter(this::isCurrent);
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            return Optional.empty();
        }
    }

    private static Optional<DeviceToken> parsePayload(String encodedPayload) {
        String payload = new String(DECODER.decode(encodedPayload), StandardCharsets.US_ASCII);
        Matcher matcher = PAYLOAD.matcher(payload);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        // parseLong/parseInt lanzan NumberFormatException (una
        // IllegalArgumentException) si el número no cabe.
        return Optional.of(new DeviceToken(
                Long.parseLong(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3),
                Instant.ofEpochSecond(Long.parseLong(matcher.group(4)))));
    }

    /** No caducada y sin una fecha de emisión imposible (en el futuro). */
    private boolean isCurrent(DeviceToken token) {
        Instant now = clock.instant();
        return now.isBefore(token.issuedAt().plus(ttl))
                && !token.issuedAt().isAfter(now.plus(MAX_CLOCK_SKEW));
    }

    private String newDeviceId() {
        byte[] bytes = new byte[DEVICE_ID_BYTES];
        random.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    private String sign(String signedPart) {
        return ENCODER.encodeToString(hmac(key, signedPart));
    }

    private static byte[] hmac(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 es obligatorio en toda JVM: no puede ocurrir.
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    /**
     * Contenido de una cookie con firma válida y no caducada.
     *
     * @param userId       id de la cuenta para la que se emitió
     * @param tokenVersion {@code token_version} de la cuenta al emitirla
     * @param deviceId     id aleatorio del dispositivo
     * @param issuedAt     instante de emisión
     */
    record DeviceToken(long userId, int tokenVersion, String deviceId, Instant issuedAt) {
    }
}
