package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.repository.UsernameReservationRepository;

/**
 * La reserva de un nombre va en la <b>misma transacción</b> que el registro
 * que la crea ({@code RegistrationService}): se confirma con el 202 y se
 * deshace si el registro falla después de reservar. Con las reservas en
 * memoria no había transacción: una reserva hecha por un registro que acababa
 * en 500 se quedaba. Comprueba también que de los emails solo se guarda el
 * hash.
 *
 * <p>
 * El fallo se provoca con un {@link MockitoSpyBean} sobre
 * {@link EmailDispatcher}: el aviso «Ya tienes una cuenta» se programa
 * <b>después</b> de reservar el nombre, dentro de la transacción. Contexto
 * propio por el espía.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsernameReservationTransactionIntegrationTest extends UsernameReservationLossTestSupport {

    @MockitoSpyBean private EmailDispatcher emailDispatcher;

    @Test
    void laReservaSeConfirmaConElRegistroYSoloGuardaElHashDelEmail() throws Exception {
        String ownerEmail = "titular_c" + DOMAIN;
        createVerifiedUser(NAME_PREFIX + "_titular_c", ownerEmail);

        assertEquals(202, register(NAME_PREFIX + "_confirmada", ownerEmail).getStatus());

        assertEquals(1, reservations(NAME_PREFIX + "_confirmada"));
        List<String> owners = jdbc.queryForList(
                "SELECT email_hash FROM username_reservation_owners WHERE username = ?", String.class,
                NAME_PREFIX + "_confirmada");
        assertEquals(List.of(UsernameReservationRepository.emailHash(ownerEmail)), owners);
    }

    /**
     * Si el registro falla después de reservar (aquí, al programar el aviso),
     * la petición responde 500 y la reserva se deshace con el resto: no queda
     * ninguna de una petición que no respondió 202.
     */
    @Test
    void siElRegistroFallaTrasReservarLaReservaSeDeshace() throws Exception {
        String ownerEmail = "titular_f" + DOMAIN;
        createVerifiedUser(NAME_PREFIX + "_titular_f", ownerEmail);
        doThrow(new IllegalStateException("fallo simulado al programar el correo"))
                .when(emailDispatcher).sendAfterCommit(any());

        assertEquals(500, register(NAME_PREFIX + "_deshecha", ownerEmail).getStatus());

        assertEquals(0, reservations(NAME_PREFIX + "_deshecha"));
    }

    private int reservations(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM username_reservations WHERE username = ?",
                Integer.class, username);
    }
}
