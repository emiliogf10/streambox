package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.emilio.streambox.security.refresh.SessionTokens;
import com.emilio.streambox.service.AuthenticationService;

/**
 * Tests unitarios del refresh de {@link AuthController}: un refresh sin cookie
 * (o con un valor que no puede ser un token) se rechaza <b>sin llamar</b> a
 * {@link RefreshTokenService#refresh}.
 *
 * <p>
 * Importa porque cada visita sin sesión hace ese refresh al arrancar: el
 * método del servicio es transaccional y solo abrir la transacción toma una
 * conexión del pool. Además, {@code RateLimitingFilter} no cuenta los refresh
 * sin cookie; si llegaran al servicio, serían consultas sin límite.
 * </p>
 */
class AuthControllerRefreshTest {

    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);

    private final AuthCookieService authCookieService = mock(AuthCookieService.class);

    private final AuthController controller = new AuthController(
            mock(AuthenticationService.class), refreshTokenService, authCookieService);

    /**
     * Sin cookie, vacía o con un valor imposible: 401 (la excepción) con las
     * dos cookies borradas, como cualquier otro fallo, y sin servicio.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "   ", "basura", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
    void unRefreshSinTokenPosibleNoLlamaAlServicioYBorraLasCookies(String value) {
        when(authCookieService.clearingCookie()).thenReturn("streambox_token=");
        when(authCookieService.clearingRefreshCookie()).thenReturn("streambox_refresh=");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(SessionExpiredException.class, () -> controller.refresh(value, response));

        verifyNoInteractions(refreshTokenService);
        assertEquals(List.of("streambox_token=", "streambox_refresh="), response.getHeaders(HttpHeaders.SET_COOKIE));
    }

    /** Con un valor de buen formato sí se llama al servicio (él decide si existe). */
    @Test
    void unRefreshConUnTokenDeBuenFormatoLlamaAlServicio() {
        String token = "A".repeat(43);
        when(refreshTokenService.refresh(token)).thenReturn(new SessionTokens("jwt", null));
        when(authCookieService.sessionCookie("jwt")).thenReturn("streambox_token=jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.refresh(token, response);

        verify(refreshTokenService).refresh(token);
        assertEquals(List.of("streambox_token=jwt"), response.getHeaders(HttpHeaders.SET_COOKIE));
    }
}
