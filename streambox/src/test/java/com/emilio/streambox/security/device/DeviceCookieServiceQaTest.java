package com.emilio.streambox.security.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.security.JwtProperties;

/**
 * Revisión independiente (QA) de {@link DeviceCookieService}: casos límite que
 * no cubre {@code DeviceCookieServiceTest}.
 *
 * <ul>
 *   <li>Fechas de emisión que <b>sí</b> caben en un {@link Instant} pero cuya
 *       suma con la vida de la cookie se sale de rango (la comprobación de
 *       caducidad lanzaría {@code DateTimeException}).</li>
 *   <li>Payloads <b>bien firmados</b> que no son Base64URL válido o que no son
 *       ASCII: el decodificador lanzaría {@code IllegalArgumentException}.</li>
 *   <li>Que la cookie más larga posible (ids y versiones máximos) sigue por
 *       debajo de {@link DeviceCookieService#MAX_LENGTH}: si no, una cookie
 *       legítima se descartaría en silencio y su titular perdería la
 *       protección sin que nada avisara.</li>
 * </ul>
 *
 * <p>
 * En todos los casos la regla es la misma: cualquier cosa que no sea una
 * cookie válida de la cuenta es «ausente» ({@link Optional#empty()}), nunca una
 * excepción (que acabaría en un 500 del login).
 * </p>
 */
class DeviceCookieServiceQaTest {

    private static final String SECRET = "secreto-de-prueba-qa-con-mas-de-treinta-y-dos-caracteres";
    private static final Duration TTL = Duration.ofDays(30);
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");
    private static final String DEVICE_ID = "AAAAAAAAAAAAAAAAAAAAAA";

    private final DeviceCookieService service = new DeviceCookieService(
            new JwtProperties(SECRET, Duration.ofMinutes(15)), new DeviceCookieProperties(TTL),
            Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * Emisión en el último segundo representable por {@link Instant} (o muy
     * cerca): {@code Instant.ofEpochSecond} la acepta, pero sumarle la vida
     * desborda. Debe ser «ausente», no una excepción.
     */
    @ParameterizedTest
    @ValueSource(longs = { 31556889864403199L, 31556889864403199L - 86_400L, 31556889864403199L - 2_592_000L,
            31556889864403199L - 2_592_001L })
    void unaEmisionAlFinalDelRangoDeInstantNoValeNiLanza(long issuedAt) {
        assertEquals(Instant.MAX.getEpochSecond(), 31556889864403199L, "control: último segundo de Instant");

        String value = signedCookie("42:3:" + DEVICE_ID + ":" + issuedAt);

        assertEquals(Optional.empty(), service.deviceIdFor(value, user(42L, 3)));
    }

    /**
     * Payload firmado con la clave correcta pero que no es Base64URL (o con
     * una longitud imposible): solo lo produciría un servidor con un error,
     * pero el decodificador lanza y no debe escaparse.
     */
    @ParameterizedTest
    @ValueSource(strings = { "@@@@", "A", "AAAAA", "AB==CD", "QQ=x", "+/+/", "AAAA AAAA" })
    void unPayloadFirmadoQueNoEsBase64UrlNoValeNiLanza(String encodedPayload) {
        String signedPart = "v1." + encodedPayload;
        String value = signedPart + "." + sign(signedPart);

        assertEquals(Optional.empty(), service.deviceIdFor(value, user(42L, 3)));
    }

    /**
     * Payload firmado con bytes que no son ASCII (UTF-8 con «ñ»): al
     * decodificarse como US-ASCII se convierten en caracteres de sustitución y
     * el patrón no casa. «Ausente», sin excepción.
     */
    @Test
    void unPayloadFirmadoConBytesNoAsciiNoVale() {
        String payload = "42:3:ñAAAAAAAAAAAAAAAAAAAA:" + NOW.getEpochSecond();
        String signedPart = "v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(Optional.empty(), service.deviceIdFor(signedPart + "." + sign(signedPart), user(42L, 3)));
    }

    /**
     * Control de los anteriores: con el mismo método de firma y un payload
     * correcto, la cookie sí vale (si no, los tests de arriba pasarían por un
     * error del propio test y no por la validación).
     */
    @Test
    void controlElMismoFirmadoConUnPayloadCorrectoVale() {
        String value = signedCookie("42:3:" + DEVICE_ID + ":" + NOW.getEpochSecond());

        assertEquals(Optional.of(DEVICE_ID), service.deviceIdFor(value, user(42L, 3)));
    }

    /**
     * La cookie más larga que puede emitir el servidor (id de usuario
     * {@code Long.MAX_VALUE}, {@code token_version} {@code Integer.MAX_VALUE})
     * cabe en {@link DeviceCookieService#MAX_LENGTH} y se valida. Protege de
     * que un cambio de formato (más campos, otro tamaño de id) deje fuera, sin
     * error visible, las cookies legítimas de cuentas con ids o versiones
     * altos.
     */
    @Test
    void laCookieMasLargaPosibleCabeEnElLimiteYVale() {
        IssuedDeviceToken issued = service.issueNew(Long.MAX_VALUE, Integer.MAX_VALUE);

        assertTrue(issued.value().length() <= DeviceCookieService.MAX_LENGTH,
                "longitud " + issued.value().length() + " > " + DeviceCookieService.MAX_LENGTH);
        assertTrue(service.deviceIdFor(issued.value(), user(Long.MAX_VALUE, Integer.MAX_VALUE)).isPresent());
    }

    /**
     * Una cookie emitida justo en el límite de la vida: vale un segundo antes
     * de cumplirla y deja de valer exactamente al cumplirla (sin margen de un
     * segundo que alargue la vida configurada).
     */
    @Test
    void elLimiteDeLaVidaEsExacto() {
        long issuedAt = NOW.minus(TTL).getEpochSecond();

        assertEquals(Optional.empty(),
                service.deviceIdFor(signedCookie("42:3:" + DEVICE_ID + ":" + issuedAt), user(42L, 3)));
        assertEquals(Optional.of(DEVICE_ID),
                service.deviceIdFor(signedCookie("42:3:" + DEVICE_ID + ":" + (issuedAt + 1)), user(42L, 3)));
    }

    /**
     * Una cookie válida con la cabecera y la firma intactas pero con algo
     * pegado (espacios, comillas, un punto final): el valor ya no es el
     * firmado y es «ausente». Así un navegador o un proxy que alteren el
     * valor nunca convierten una cookie en otra.
     */
    @ParameterizedTest
    @ValueSource(strings = { " %s", "%s ", "\"%s\"", "%s.", ".%s", "%s;", "%s\u0000" })
    void unaCookieValidaConAlgoPegadoNoVale(String template) {
        String valid = service.issueNew(42L, 3).value();

        assertEquals(Optional.empty(), service.deviceIdFor(template.formatted(valid), user(42L, 3)));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String signedCookie(String payload) {
        String signedPart = "v1." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
        return signedPart + "." + sign(signedPart);
    }

    /** Firma como documenta el servicio: HMAC-SHA256 con la clave derivada del secreto. */
    private static String sign(String data) {
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            SecretKeySpec key = new SecretKeySpec(
                    derive.doFinal(DeviceCookieService.KEY_LABEL.getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cuenta con el correo confirmado (las únicas que pueden usar la cookie). */
    private static User user(long id, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEmail("qa@device.test");
        user.setUsername("qa");
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(NOW.minus(Duration.ofDays(1)));
        ReflectionTestUtils.setField(user, "tokenVersion", tokenVersion);
        return user;
    }
}
