package com.emilio.streambox.security.refresh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.JwtService;

import jakarta.servlet.http.Cookie;

/**
 * Refresh y logout con la base de datos caída (revisión independiente de QA de
 * la tarea 29): la respuesta es un 500 genérico <b>sin ningún
 * {@code Set-Cookie}</b>.
 *
 * <p>
 * Protege el contrato con el frontend, que decide solo por el estado:
 * </p>
 * <ul>
 * <li><b>Logout:</b> ante un 5xx, {@code lib/logout.ts} mantiene la sesión
 * abierta en pantalla y avisa de que sigue abierta. Si el servidor borrara las
 * cookies aunque no hubiera podido revocar el refresh token, la pantalla
 * mentiría al revés: diría «tu sesión sigue abierta» con las cookies ya
 * borradas. Y si respondiera 204 sin revocar, la interfaz diría «sesión
 * cerrada» con un refresh token que sigue sirviendo días.</li>
 * <li><b>Refresh:</b> ante un 5xx, {@code apiFetch} no cierra la sesión
 * (resultado «no se sabe») y la pantalla ofrece reintentar. Si la respuesta
 * borrara las cookies, el reintento ya no podría renovar: la sesión se
 * perdería por un fallo pasajero. Solo el 401 {@code SESSION_EXPIRED} las
 * borra.</li>
 * </ul>
 *
 * <p>
 * El fallo se simula con {@link MockitoBean} sobre {@link RefreshTokenService}
 * (la excepción que da Spring sin conexión), así que el contexto es propio y
 * no hay datos que limpiar. El valor de la cookie tiene el formato de un token
 * (43 caracteres base64url) para que el controlador llegue a llamar al
 * servicio.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RefreshAndLogoutDatabaseFailureIntegrationTest {

    private static final String WELL_FORMED_TOKEN = "A".repeat(43);

    /** Detalle interno de la excepción simulada: no debe llegar nunca al cliente. */
    private static final String DB_ERROR_MESSAGE = "Conexión rechazada por el servidor de base de datos";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @MockitoBean private RefreshTokenService refreshTokenService;

    /** Logout sin poder revocar: 500, sin borrar cookies y sin filtrar el detalle de la BD. */
    @Test
    void conLaBaseDeDatosCaidaElLogoutDa500YNoBorraLasCookies() throws Exception {
        doThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE))
                .when(refreshTokenService).revokeFamilyOf(any());

        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/logout")
                        .header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie(AuthCookieService.REFRESH_COOKIE_NAME, WELL_FORMED_TOKEN)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse();

        assertNoCookiesAndNoInternalDetail(response);
    }

    /** Refresh sin poder consultar: 500 (no 401), sin borrar cookies y sin filtrar el detalle de la BD. */
    @Test
    void conLaBaseDeDatosCaidaElRefreshDa500YNoBorraLasCookies() throws Exception {
        when(refreshTokenService.refresh(any()))
                .thenThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE));

        MockHttpServletResponse response = mockMvc.perform(post("/api/auth/refresh")
                        .header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie(AuthCookieService.REFRESH_COOKIE_NAME, WELL_FORMED_TOKEN)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse();

        assertNoCookiesAndNoInternalDetail(response);
    }

    /**
     * Cerrar sesión en todos los dispositivos sin poder revocar: 500, sin
     * borrar cookies y sin filtrar el detalle de la BD (el cliente debe
     * reintentar, no dar las sesiones por cerradas).
     */
    @Test
    void conLaBaseDeDatosCaidaElLogoutAllDa500YNoBorraLasCookies() throws Exception {
        User user = new User();
        user.setUsername("logoutall-bd-caida");
        user.setEmail("logoutall@bd-caida.test");
        user.setPassword("no-se-usa");
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        user = userRepository.save(user);
        try {
            doThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE))
                    .when(refreshTokenService).revokeAllSessions(user.getId());

            MockHttpServletResponse response = mockMvc.perform(post("/api/auth/logout-all")
                            .header("X-Requested-With", "StreamBox")
                            .cookie(new Cookie(AuthCookieService.COOKIE_NAME, jwtService.generateToken(user)),
                                    new Cookie(AuthCookieService.REFRESH_COOKIE_NAME, WELL_FORMED_TOKEN)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andReturn().getResponse();

            assertNoCookiesAndNoInternalDetail(response);
        } finally {
            userRepository.delete(user);
        }
    }

    private static void assertNoCookiesAndNoInternalDetail(MockHttpServletResponse response) throws Exception {
        assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
                "un 500 no debe tocar las cookies: " + response.getHeaders(HttpHeaders.SET_COOKIE));
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains(DB_ERROR_MESSAGE),
                "el detalle interno de la excepción no debe llegar al cliente");
    }
}
