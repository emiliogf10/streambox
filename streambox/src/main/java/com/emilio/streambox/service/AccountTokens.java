package com.emilio.streambox.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.entity.AccountToken;
import com.emilio.streambox.entity.AccountTokenPurpose;
import com.emilio.streambox.repository.AccountTokenRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tokens de un solo uso de las cuentas (tabla {@code account_tokens}): cómo se
 * generan, cómo se guardan y cuántos se pueden emitir. Lo comparten la
 * verificación del correo ({@link EmailVerificationService}) y «He olvidado mi
 * contraseña» ({@link PasswordResetService}).
 *
 * <h2>Por qué un componente común</h2>
 * <p>
 * Los dos enlaces tienen las mismas garantías y no deben separarse con el
 * tiempo: si uno pasara a generar tokens más cortos o a guardarlos en claro,
 * sería un fallo de seguridad que nadie vería en una revisión del otro. Aquí
 * está, una sola vez, lo que es igual; lo que cambia (el propósito, la vida
 * del enlace, el correo) lo decide cada servicio.
 * </p>
 *
 * <h2>Los tokens</h2>
 * <ul>
 *   <li><b>32 bytes de {@link SecureRandom}</b> (256 bits) en Base64URL sin
 *       relleno: 43 caracteres que van tal cual en una URL. Adivinar uno es
 *       imposible en la práctica, con o sin límite de intentos.</li>
 *   <li>En la base de datos <b>solo su SHA-256</b> (hexadecimal en minúsculas),
 *       como los refresh tokens: con una copia de la tabla no se puede usar
 *       ningún enlace. Un hash rápido basta porque el token es aleatorio y
 *       largo, no una contraseña.</li>
 *   <li>{@code createdAt} y {@code expiresAt} salen del mismo instante (la
 *       tabla exige {@code expires_at > created_at}).</li>
 *   <li>Emitir uno <b>invalida los anteriores del mismo propósito</b>: solo
 *       vale el enlace del último correo. Quien llama debe tener bloqueada la
 *       fila del usuario para que dos emisiones simultáneas no dejen dos
 *       vigentes.</li>
 * </ul>
 *
 * <h2>Límite silencioso por cuenta</h2>
 * <p>
 * {@link #withinSilentLimit} cuenta los tokens <b>de ese propósito</b> que hay
 * en la base de datos ({@code countIssuedSince}), así que sobrevive a los
 * reinicios y los dos enlaces no comparten cupo: pedir restablecer la
 * contraseña no gasta los reenvíos de la verificación ni al revés. Los dos
 * usan las mismas propiedades ({@code streambox.email.resend.*}).
 * </p>
 */
@Component
public class AccountTokens {

    /** Bytes aleatorios de cada token. */
    static final int TOKEN_BYTES = 32;

    /** Longitud del token en Base64URL sin relleno (32 bytes → 43 caracteres). */
    static final int TOKEN_LENGTH = 43;

    /** Forma exacta de un token propio: lo demás se rechaza sin ir a la base de datos. */
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{" + TOKEN_LENGTH + "}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccountTokenRepository accountTokenRepository;
    private final UserRepository userRepository;

    /**
     * Crea el componente.
     *
     * @param accountTokenRepository tokens de un solo uso
     * @param userRepository         cuentas (solo para la referencia del token al usuario)
     */
    public AccountTokens(AccountTokenRepository accountTokenRepository, UserRepository userRepository) {
        this.accountTokenRepository = accountTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * Indica si el límite silencioso de la cuenta permite emitir otro token de
     * ese propósito ahora: al menos {@code minInterval} desde el último y menos
     * de {@code maxPerWindow} en la ventana.
     *
     * @param userId  id de la cuenta (con su fila bloqueada en esta transacción)
     * @param purpose propósito cuyos tokens se cuentan (solo ese)
     * @param now     instante actual (del {@code Clock} del servicio)
     * @param limits  límites ({@code streambox.email.resend.*})
     * @return {@code true} si se puede emitir
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean withinSilentLimit(Long userId, AccountTokenPurpose purpose, Instant now,
            EmailProperties.Resend limits) {

        boolean tooSoon = !limits.minInterval().isZero()
                && accountTokenRepository.countIssuedSince(userId, purpose, now.minus(limits.minInterval())) > 0;
        boolean tooMany = accountTokenRepository.countIssuedSince(userId, purpose, now.minus(limits.window()))
                >= limits.maxPerWindow();
        return !tooSoon && !tooMany;
    }

    /**
     * Invalida los tokens vigentes de ese propósito de la cuenta y guarda uno
     * nuevo (solo su hash).
     *
     * @param userId  id de la cuenta (con su fila bloqueada en esta transacción)
     * @param purpose propósito del token
     * @param now     instante de emisión (del {@code Clock} del servicio)
     * @param ttl     vida del enlace
     * @return el token en claro, para el enlace del correo (no se guarda en ningún sitio)
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(Long userId, AccountTokenPurpose purpose, Instant now, Duration ttl) {

        accountTokenRepository.invalidateActiveTokens(userId, purpose, now);

        String token = newToken();
        AccountToken entity = new AccountToken();
        // Referencia sin cargar el usuario (ni depender de que la entidad que
        // tenga quien llama siga en la sesión de Hibernate).
        entity.setUser(userRepository.getReferenceById(userId));
        entity.setPurpose(purpose);
        entity.setTokenHash(sha256Hex(token));
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plus(ttl));
        accountTokenRepository.save(entity);
        return token;
    }

    /**
     * Indica si un texto tiene la forma exacta de un token propio
     * ({@value #TOKEN_LENGTH} caracteres de Base64URL). Lo que no la tiene no
     * puede existir, así que se rechaza sin gastar una consulta.
     *
     * @param token texto recibido (puede ser {@code null})
     * @return {@code true} si podría ser un token emitido por la aplicación
     */
    public static boolean isWellFormed(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
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
    public static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM está obligada a tener SHA-256.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
