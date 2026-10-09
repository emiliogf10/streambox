package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Con {@code streambox.auth.cookie.secure=false} (Docker Compose por HTTP) la
 * cookie va sin {@code Secure}; el resto de atributos se mantienen.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "streambox.auth.cookie.secure=false")
class CookieSecureFlagDisabledIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void lasCookiesDeLogoutRespetanSecureDesactivado() throws Exception {
        // Con la cabecera CSRF que exige el logout (sin ella, 403 sin Set-Cookie).
        java.util.List<String> setCookies = mockMvc.perform(post("/api/auth/logout")
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE);

        // Las dos: streambox_token y streambox_refresh.
        org.junit.jupiter.api.Assertions.assertEquals(2, setCookies.size(), setCookies.toString());
        for (String setCookie : setCookies) {
            assertFalse(setCookie.contains("Secure"), setCookie);
            assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("SameSite=Strict"), setCookie);
        }
    }
}
