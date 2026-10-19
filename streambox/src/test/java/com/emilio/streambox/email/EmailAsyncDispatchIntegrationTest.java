package com.emilio.streambox.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Envío <b>asíncrono</b> real del correo ({@code streambox.email.async=true},
 * el valor de producción; el resto de suites lo desactivan para leer la bandeja
 * sin esperas).
 *
 * <p>
 * Comprueba que el correo del registro sale en un hilo del ejecutor de correo
 * ({@code email-*}), no en el de la petición: es lo que impide que el tiempo de
 * respuesta delate si el email ya tenía cuenta (ver {@link EmailDispatcher}).
 * Falla si el despachador envía en el hilo de la petición.
 * </p>
 *
 * <p>
 * <b>Sin {@code Thread.sleep}:</b> {@link MemoryEmailSender#awaitSentTo} espera
 * a que llegue el correo (el envío despierta al hilo) con un tope de 10 s, que
 * solo se agota si el correo no llega nunca.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "streambox.email.async=true")
class EmailAsyncDispatchIntegrationTest {

    private static final String EMAIL = "asincrono@envio-asincrono.test";

    @MockitoSpyBean
    private MemoryEmailSender inbox;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private final AtomicReference<String> senderThread = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        cleanUp();
        inbox.clear();
        doAnswer(invocation -> {
            senderThread.set(Thread.currentThread().getName());
            return invocation.callRealMethod();
        }).when(inbox).send(any());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%@envio-asincrono.test");
    }

    @Test
    void elCorreoDelRegistroSaleEnUnHiloDelEjecutorDeCorreo() throws Exception {
        String requestThread = Thread.currentThread().getName();

        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "username", "asincrono", "email", EMAIL, "password", "Paseo-Por-El-Retiro-2026"))))
                .andExpect(status().isAccepted());

        List<SentEmail> emails = inbox.awaitSentTo(EMAIL, 1, Duration.ofSeconds(10));
        assertEquals(1, emails.size(), "El correo no ha llegado a la bandeja");
        assertEquals(AccountEmails.VERIFICATION_SUBJECT, emails.get(0).subject());
        assertTrue(senderThread.get().startsWith("email-"), senderThread.get());
        assertNotEquals(requestThread, senderThread.get());
    }

    /**
     * El reenvío hace todo su trabajo (buscar la cuenta, emitir el token y
     * enviar) en el ejecutor de correo, con su propia transacción: la petición
     * solo lo encola (B1, ver {@code EmailVerificationService#resend}). Aquí
     * se comprueba que ese camino funciona de punta a punta con el ejecutor
     * real: el enlace nuevo llega y el token se guardó (confirmado en otro
     * hilo).
     */
    @Test
    void elReenvioSeHaceEnElEjecutorDeCorreoYElEnlaceLlega() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "username", "asincrono_bis", "email", EMAIL, "password", "Paseo-Por-El-Retiro-2026"))))
                .andExpect(status().isAccepted());
        assertEquals(1, inbox.awaitSentTo(EMAIL, 1, Duration.ofSeconds(10)).size());

        mockMvc.perform(post("/api/auth/email-verification/resend").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of("email", EMAIL))))
                .andExpect(status().isAccepted());

        List<SentEmail> emails = inbox.awaitSentTo(EMAIL, 2, Duration.ofSeconds(10));
        assertEquals(2, emails.size(), "El correo del reenvío no ha llegado a la bandeja");
        assertTrue(senderThread.get().startsWith("email-"), senderThread.get());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens t JOIN users u ON u.id = t.user_id "
                + "WHERE u.email = ?", Integer.class, EMAIL));
    }
}
