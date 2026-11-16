package com.emilio.streambox.security.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.security.JwtProperties;

/**
 * Tests unitarios de {@link DeviceCookieService#deviceIdForAny}: varias
 * cookies {@code streambox_device} en la misma petición (incidencia QA-DC-2,
 * «cookie tossing»).
 *
 * <ul>
 *   <li>Una cookie plantada delante no anula la legítima: vale la primera
 *       válida <b>para esa cuenta</b>.</li>
 *   <li>El trabajo está acotado: como mucho {@value DeviceCookieService#MAX_CANDIDATES}
 *       comprobaciones aunque lleguen cientos, y siempre las mismas (no se
 *       para en la primera válida), para que no dependa de la cuenta.</li>
 *   <li>Sin ninguna válida, el mismo vacío que sin cookie.</li>
 * </ul>
 */
class DeviceCookieCandidatesTest {

    private static final JwtProperties JWT =
            new JwtProperties("secreto-de-prueba-con-mas-de-treinta-y-dos-caracteres", Duration.ofMinutes(15));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);
    private static final int MAX = DeviceCookieService.MAX_CANDIDATES;

    private final DeviceCookieService service =
            new DeviceCookieService(JWT, new DeviceCookieProperties(Duration.ofDays(30)), CLOCK);

    /** Falla sin el arreglo de QA-DC-2: antes solo contaba la primera cookie. */
    @Test
    void unaCookiePlantadaDelanteNoAnulaLaValida() {
        User user = user(42L, 3);
        String valid = service.issueNew(42L, 3).value();
        String expected = service.deviceIdFor(valid, user).orElseThrow();

        assertEquals(Optional.of(expected), service.deviceIdForAny(List.of("plantada", valid), user));
    }

    /**
     * Cookies válidas de <b>otra</b> cuenta o de una versión anterior de esta
     * delante: se saltan y se usa la de la cuenta.
     */
    @Test
    void seUsaLaPrimeraValidaParaEsaCuentaNoLaPrimeraConFirmaCorrecta() {
        User user = user(42L, 3);
        String otherAccount = service.issueNew(7L, 3).value();
        String oldVersion = service.issueNew(42L, 2).value();
        String first = service.issueNew(42L, 3).value();
        String second = service.issueNew(42L, 3).value();

        assertEquals(service.deviceIdFor(first, user),
                service.deviceIdForAny(List.of(otherAccount, oldVersion, first, second), user));
    }

    /** La legítima en la última posición que se mira todavía cuenta. */
    @Test
    void laValidaEnLaUltimaPosicionPermitidaSeUsa() {
        User user = user(42L, 3);
        String valid = service.issueNew(42L, 3).value();

        assertTrue(service.deviceIdForAny(withValidAt(MAX, valid), user).isPresent());
    }

    /**
     * Tope: una válida <b>después</b> de las {@value DeviceCookieService#MAX_CANDIDATES}
     * primeras se ignora (igual que sin cookie). Falla si se quita el tope.
     */
    @Test
    void laValidaMasAllaDelTopeSeIgnora() {
        User user = user(42L, 3);
        String valid = service.issueNew(42L, 3).value();

        assertEquals(Optional.empty(), service.deviceIdForAny(withValidAt(MAX + 1, valid), user));
    }

    /**
     * Cientos de cookies: solo {@value DeviceCookieService#MAX_CANDIDATES}
     * comprobaciones (HMAC). Falla si se quita el tope.
     */
    @Test
    void cientosDeCookiesSoloCuestanElTopeDeComprobaciones() {
        DeviceCookieService spied = spy(service);
        List<String> many = new ArrayList<>(Collections.nCopies(500, "v1.AAAA.AAAA"));

        assertEquals(Optional.empty(), spied.deviceIdForAny(many, user(42L, 3)));

        verify(spied, times(MAX)).deviceIdFor(anyString(), any());
    }

    /**
     * El trabajo no depende de dónde esté la válida ni de si la cuenta existe:
     * con la válida la primera, con un email inexistente ({@code null}) o sin
     * ninguna válida, siempre las mismas comprobaciones.
     */
    @Test
    void siempreSeCompruebanTodasLasCandidatasPermitidas() {
        String valid = service.issueNew(42L, 3).value();
        List<String> validFirst = withValidAt(1, valid);

        DeviceCookieService existing = spy(service);
        assertTrue(existing.deviceIdForAny(validFirst, user(42L, 3)).isPresent());
        verify(existing, times(MAX)).deviceIdFor(anyString(), any());

        DeviceCookieService missingAccount = spy(service);
        assertEquals(Optional.empty(), missingAccount.deviceIdForAny(validFirst, null));
        verify(missingAccount, times(MAX)).deviceIdFor(anyString(), any());
    }

    /** Sin cookies, con lista {@code null} o con valores {@code null}: ausente, sin excepciones. */
    @Test
    void sinCandidatasOConValoresNulosEsAusente() {
        User user = user(42L, 3);

        assertEquals(Optional.empty(), service.deviceIdForAny(null, user));
        assertEquals(Optional.empty(), service.deviceIdForAny(List.of(), user));
        assertEquals(Optional.empty(), service.deviceIdForAny(Arrays.asList(null, "", "%zz"), user));
    }

    /** {@code MAX + 1} cookies basura con la válida en la posición {@code position} (desde 1). */
    private static List<String> withValidAt(int position, String valid) {
        List<String> values = new ArrayList<>(Collections.nCopies(MAX + 1, "basura"));
        values.set(position - 1, valid);
        return values;
    }

    private static User user(Long id, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEmail("ana@device.test");
        user.setUsername("ana");
        user.setRole(Role.USER);
        ReflectionTestUtils.setField(user, "tokenVersion", tokenVersion);
        return user;
    }
}
