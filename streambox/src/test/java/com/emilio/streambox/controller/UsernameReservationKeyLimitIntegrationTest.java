package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.emilio.streambox.entity.User;

/**
 * <b>NV-2, variante del tope de claves</b>: la reserva de un nombre no debe
 * depender de {@code streambox.security.rate-limit.max-keys}. Cuando vivía en
 * los contadores en memoria, al llenarse el tope se olvidaba la reserva más
 * antigua: bastaban {@code max-keys} registros de relleno (con el email de una
 * cuenta propia y nombres nuevos) para que la reserva de la sonda
 * desapareciera y el registro y «Editar perfil» volvieran a delatar si su
 * email tenía cuenta. Ver {@link UsernameReservationLossTestSupport}.
 *
 * <p>
 * Con un tope de 3 claves, se reservan 5 nombres más después del sondeado: el
 * nombre sondeado debe seguir reservado (409 igual que el de la pendiente).
 * Contexto propio por el {@code @TestPropertySource}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "streambox.security.rate-limit.max-keys=3")
class UsernameReservationKeyLimitIntegrationTest extends UsernameReservationLossTestSupport {

    /** Registros de relleno: más que el tope de claves. */
    private static final int FILLER = 5;

    @Test
    void reservarMasNombresQueElTopeDeClavesNoOlvidaLaReservaDeLaSonda() throws Exception {
        createVerifiedUser(NAME_PREFIX + "_titular_t", "titular_t" + DOMAIN);
        User attacker = createVerifiedUser(NAME_PREFIX + "_atacante_t", "atacante_t" + DOMAIN);
        assertEquals(202, register(NAME_PREFIX + "_nuevo_t", "libre_t" + DOMAIN).getStatus());
        assertEquals(202, register(NAME_PREFIX + "_sonda_t", "titular_t" + DOMAIN).getStatus());

        // Relleno: el atacante usa el email de una cuenta (aquí la del
        // titular; en la realidad, una suya) y un nombre nuevo cada vez.
        for (int i = 1; i <= FILLER; i++) {
            assertEquals(202, register(NAME_PREFIX + "_relleno" + i, "titular_t" + DOMAIN).getStatus());
        }

        assertSameConflict(
                renameTo(attacker, NAME_PREFIX + "_nuevo_t"),
                renameTo(attacker, NAME_PREFIX + "_sonda_t"));
        assertSameConflict(
                register(NAME_PREFIX + "_nuevo_t", "otro1_t" + DOMAIN),
                register(NAME_PREFIX + "_sonda_t", "otro2_t" + DOMAIN));
    }
}
