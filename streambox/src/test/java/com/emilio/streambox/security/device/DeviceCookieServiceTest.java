package com.emilio.streambox.security.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.security.JwtProperties;

/**
 * Tests unitarios de {@link DeviceCookieService}: firma, formato y validez de
 * la cookie de dispositivo. Cualquier valor que no sea una cookie válida de
 * esa cuenta debe dar «ausente» ({@link Optional#empty()}), nunca una
 * excepción.
 */
class DeviceCookieServiceTest {

    private static final String SECRET = "secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres";
    private static final JwtProperties JWT = new JwtProperties(SECRET, Duration.ofMinutes(15));
    private static final Duration TTL = Duration.ofDays(30);
    private static final Instant NOW = Instant.parse("2026-11-16T10:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final DeviceCookieService service = new DeviceCookieService(JWT, new DeviceCookieProperties(TTL), clock);

    @Test
    void unaCookieRecienEmitidaValeParaSuCuentaYConservaElId() {
        User user = user(42L, 3);
        IssuedDeviceToken issued = service.issueNew(42L, 3);

        Optional<String> deviceId = service.deviceIdFor(issued.value(), user);

        assertTrue(deviceId.isPresent());
        assertEquals(22, deviceId.get().length(), "16 bytes en Base64URL sin relleno");
        assertEquals(TTL, issued.maxAge());
        assertTrue(issued.value().startsWith("v1."), issued.value());
        // Renovar conserva el id de dispositivo.
        IssuedDeviceToken renewed = service.issue(42L, 3, deviceId.get());
        assertEquals(deviceId, service.deviceIdFor(renewed.value(), user));
    }

    /** Sin email ni datos personales: solo id, versión, dispositivo y fecha. */
    @Test
    void elPayloadNoLlevaElEmail() {
        String value = service.issueNew(42L, 3).value();
        String payload = new String(Base64.getUrlDecoder().decode(value.split("\\.")[1]), StandardCharsets.US_ASCII);

        assertTrue(payload.matches("42:3:[A-Za-z0-9_-]{22}:" + NOW.getEpochSecond()), payload);
        assertFalse(value.contains("@"));
    }

    @Test
    void cadaDispositivoNuevoTieneUnIdDistinto() {
        User user = user(42L, 0);
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            ids.add(service.deviceIdFor(service.issueNew(42L, 0).value(), user).orElseThrow());
        }
        assertEquals(200, ids.size());
    }

    @Test
    void unaFirmaAlteradaNoVale() {
        String value = service.issueNew(42L, 3).value();
        int last = value.length() - 1;
        String tampered = value.substring(0, last) + (value.charAt(last) == 'A' ? 'B' : 'A');

        assertEquals(Optional.empty(), service.deviceIdFor(tampered, user(42L, 3)));
    }

    /** Cambiar el payload (p. ej. el id de usuario) sin poder refirmar no sirve. */
    @Test
    void unPayloadAlteradoNoVale() {
        String[] parts = service.issueNew(42L, 3).value().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.US_ASCII);
        String forged = encode(payload.replaceFirst("^42:", "43:"));

        assertEquals(Optional.empty(), service.deviceIdFor(parts[0] + "." + forged + "." + parts[2], user(43L, 3)));
    }

    /** Otra versión del formato, aunque el resto sea idéntico y bien firmado para ella. */
    @Test
    void unaVersionDesconocidaNoVale() {
        String[] parts = service.issueNew(42L, 3).value().split("\\.");
        String signedPart = "v2." + parts[1];
        String v2 = signedPart + "." + sign(derivedKey(), signedPart);

        assertEquals(Optional.empty(), service.deviceIdFor(v2, user(42L, 3)));
    }

    /** Firmada con el secreto JWT directamente (sin la derivación): otra clave. */
    @Test
    void unaCookieFirmadaConElSecretoSinDerivarNoVale() {
        String[] parts = service.issueNew(42L, 3).value().split("\\.");
        String signedPart = parts[0] + "." + parts[1];
        String withJwtKey = signedPart + "."
                + sign(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"), signedPart);

        assertEquals(Optional.empty(), service.deviceIdFor(withJwtKey, user(42L, 3)));
    }

    /** Otro secreto JWT (rotación): las cookies anteriores dejan de valer. */
    @Test
    void conOtroSecretoNoVale() {
        DeviceCookieService other = new DeviceCookieService(
                new JwtProperties("otro-secreto-distinto-con-mas-de-treinta-y-dos", Duration.ofMinutes(15)),
                new DeviceCookieProperties(TTL), clock);

        assertEquals(Optional.empty(), other.deviceIdFor(service.issueNew(42L, 3).value(), user(42L, 3)));
    }

    @Test
    void caducaAlCumplirSuVida() {
        String value = service.issueNew(42L, 3).value();

        clock.set(NOW.plus(TTL).minusSeconds(1));
        assertTrue(service.deviceIdFor(value, user(42L, 3)).isPresent());
        clock.set(NOW.plus(TTL));
        assertEquals(Optional.empty(), service.deviceIdFor(value, user(42L, 3)));
    }

    /** Una fecha de emisión muy en el futuro solo la firmaría un servidor con la hora mal. */
    @Test
    void unaEmisionEnElFuturoNoVale() {
        clock.set(NOW.plus(Duration.ofHours(1)));
        String fromTheFuture = service.issueNew(42L, 3).value();
        clock.set(NOW);

        assertEquals(Optional.empty(), service.deviceIdFor(fromTheFuture, user(42L, 3)));
    }

    @Test
    void deOtraCuentaNoVale() {
        String value = service.issueNew(42L, 3).value();

        assertEquals(Optional.empty(), service.deviceIdFor(value, user(7L, 3)));
    }

    /** Tras subir token_version (logout-all, cambio o restablecimiento) ya no vale. */
    @Test
    void conOtraTokenVersionNoVale() {
        String value = service.issueNew(42L, 3).value();

        assertEquals(Optional.empty(), service.deviceIdFor(value, user(42L, 4)));
    }

    /**
     * Cuenta pendiente de confirmar el correo (BAJA-2 de la revisión de la PR
     * #33): una cookie auténtica, suya y de su versión actual no vale. La
     * misma cookie, con la cuenta confirmada, sí (control).
     */
    @Test
    void deUnaCuentaPendienteNoVale() {
        String value = service.issueNew(42L, 3).value();
        User pending = user(42L, 3);
        pending.setEmailVerifiedAt(null);

        assertFalse(pending.isEmailVerified());
        assertEquals(Optional.empty(), service.deviceIdFor(value, pending));
        assertTrue(service.deviceIdFor(value, user(42L, 3)).isPresent(), "control: confirmada sí vale");
    }

    /** Email inexistente: nunca vale, aunque la cookie sea auténtica. */
    @Test
    void sinUsuarioNoVale() {
        assertEquals(Optional.empty(), service.deviceIdFor(service.issueNew(42L, 3).value(), null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "basura", "v1", "v1.", "v1..", "v1.a.b", ".a.b", "v1.a.b.c", "v1.a.b.",
            "v1.%%%.abc", "v1.AAAA.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "V1.a.b",
            "v1.ñ.ü", "v1.\u0000.\u0000" })
    void losValoresBasuraNoValenNiLanzan(String value) {
        assertEquals(Optional.empty(), service.deviceIdFor(value, user(42L, 3)));
    }

    /** Un valor enorme se descarta sin calcular nada (longitud acotada). */
    @Test
    void unValorEnormeNoVale() {
        String valid = service.issueNew(42L, 3).value();
        String huge = valid + "A".repeat(DeviceCookieService.MAX_LENGTH);

        assertEquals(Optional.empty(), service.deviceIdFor(huge, user(42L, 3)));
        assertEquals(Optional.empty(), service.deviceIdFor("v1." + "A".repeat(100_000) + ".x", user(42L, 3)));
    }

    /**
     * Payloads bien firmados pero con contenido imposible (solo los firmaría
     * un servidor con un error): números que no caben, ceros a la izquierda,
     * campos de más o de menos, fecha gigantesca. Nunca una excepción.
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "42:3:AAAAAAAAAAAAAAAAAAAAAA", "42:3:AAAAAAAAAAAAAAAAAAAAAA:1:9",
            "042:3:AAAAAAAAAAAAAAAAAAAAAA:1", "0:3:AAAAAAAAAAAAAAAAAAAAAA:1", "42:-3:AAAAAAAAAAAAAAAAAAAAAA:1",
            "99999999999999999999:3:AAAAAAAAAAAAAAAAAAAAAA:1", "42:9999999999:AAAAAAAAAAAAAAAAAAAAAA:1",
            "42:3:AAAAAAAAAAAAAAAAAAAAA:1", "42:3:AAAAAAAAAAAAAAAAAAAAAA|:1",
            "42:3:AAAAAAAAAAAAAAAAAAAAAA:9223372036854775807", "42:3:AAAAAAAAAAAAAAAAAAAAAA:99999999999999999999" })
    void unPayloadFirmadoPeroImposibleNoValeNiLanza(String payload) {
        String signedPart = "v1." + encode(payload);
        String value = signedPart + "." + sign(derivedKey(), signedPart);

        assertEquals(Optional.empty(), service.deviceIdFor(value, user(42L, 3)));
    }

    /** Control del anterior: el mismo método de firma con un payload correcto sí vale. */
    @Test
    void elFirmadoDelTestCoincideConElDelServicio() {
        String signedPart = "v1." + encode("42:3:AAAAAAAAAAAAAAAAAAAAAA:" + NOW.getEpochSecond());

        assertEquals(Optional.of("AAAAAAAAAAAAAAAAAAAAAA"),
                service.deviceIdFor(signedPart + "." + sign(derivedKey(), signedPart), user(42L, 3)));
    }

    @Test
    void laClaveEsDistintaDelSecretoJwt() {
        assertNotEquals(Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(derivedKey().getEncoded()));
    }

    @Test
    void elToStringNoMuestraElValor() {
        IssuedDeviceToken issued = service.issueNew(42L, 3);

        assertFalse(issued.toString().contains(issued.value()));
    }

    @ParameterizedTest
    @ValueSource(strings = { "PT0S", "-PT1S", "P401D" })
    void unaVidaFueraDeRangoImpideArrancar(String ttl) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new DeviceCookieProperties(Duration.parse(ttl)));
        assertEquals(DeviceCookieProperties.INVALID_TTL_MESSAGE, error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new DeviceCookieProperties(null));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Clave derivada como la documenta el servicio (HMAC del secreto sobre la etiqueta). */
    private static SecretKeySpec derivedKey() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new SecretKeySpec(mac.doFinal(DeviceCookieService.KEY_LABEL.getBytes(StandardCharsets.UTF_8)),
                    "HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(SecretKeySpec key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }

    /** Cuenta con el correo confirmado (las únicas que pueden usar la cookie). */
    private static User user(Long id, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEmail("ana@device.test");
        user.setUsername("ana");
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(NOW.minus(Duration.ofDays(1)));
        org.springframework.test.util.ReflectionTestUtils.setField(user, "tokenVersion", tokenVersion);
        return user;
    }

    /** Reloj parado que el test mueve a mano. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
