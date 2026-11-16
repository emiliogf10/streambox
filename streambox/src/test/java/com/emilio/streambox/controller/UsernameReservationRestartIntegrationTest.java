package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.service.RegistrationService;
import com.emilio.streambox.service.UserService;
import com.emilio.streambox.service.UsernameReservations;

/**
 * <b>NV-2, variante del reinicio</b>: la reserva de un nombre debe sobrevivir
 * a un reinicio de la aplicación (despliegue, caída con
 * {@code restart: unless-stopped}), igual que la sobrevive la cuenta
 * pendiente con la que se compara. Ver {@link UsernameReservationLossTestSupport}.
 *
 * <h2>Cómo se simula el reinicio</h2>
 * <p>
 * Un arranque nuevo crea una instancia nueva de {@link UsernameReservations}
 * con la misma base de datos. Eso es lo que hace {@link #simulateRestart}:
 * pide a Spring una instancia recién construida (con sus dependencias
 * normales, como en el arranque) y se la pone a los dos servicios que la
 * usan. Con las reservas en memoria, la instancia nueva no sabía nada de las
 * anteriores y este test fallaba (202 y 200 en vez de 409); con las reservas
 * en la base de datos, la instancia nueva las lee de ahí. Al terminar se
 * devuelve la instancia original, porque el contexto de Spring se reutiliza
 * en otras clases.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsernameReservationRestartIntegrationTest extends UsernameReservationLossTestSupport {

    @Autowired private AutowireCapableBeanFactory beanFactory;
    @Autowired private RegistrationService registrationService;
    @Autowired private UserService userService;
    @Autowired private UsernameReservations originalReservations;

    @AfterEach
    void restoreReservations() {
        useReservations(originalReservations);
    }

    /**
     * Registro tras el reinicio: un segundo registro con el nombre reservado
     * (y otro email) debe dar el mismo 409 que con el nombre de la pendiente.
     */
    @Test
    void trasUnReinicioElRegistroConElNombreReservadoSigueDando409() throws Exception {
        createVerifiedUser(NAME_PREFIX + "_titular_r", "titular_r" + DOMAIN);
        assertEquals(202, register(NAME_PREFIX + "_nuevo_r", "libre_r" + DOMAIN).getStatus());
        assertEquals(202, register(NAME_PREFIX + "_reservado_r", "titular_r" + DOMAIN).getStatus());

        simulateRestart();

        assertSameConflict(
                register(NAME_PREFIX + "_nuevo_r", "otro1_r" + DOMAIN),
                register(NAME_PREFIX + "_reservado_r", "otro2_r" + DOMAIN));
    }

    /**
     * «Editar perfil» tras el reinicio, desde una cuenta propia verificada
     * (la vía más barata para el atacante: sin BCrypt ni límite por IP).
     */
    @Test
    void trasUnReinicioEditarPerfilConElNombreReservadoSigueDando409() throws Exception {
        createVerifiedUser(NAME_PREFIX + "_titular_p", "titular_p" + DOMAIN);
        User attacker = createVerifiedUser(NAME_PREFIX + "_atacante_p", "atacante_p" + DOMAIN);
        assertEquals(202, register(NAME_PREFIX + "_nuevo_p", "libre_p" + DOMAIN).getStatus());
        assertEquals(202, register(NAME_PREFIX + "_reservado_p", "titular_p" + DOMAIN).getStatus());

        simulateRestart();

        assertSameConflict(
                renameTo(attacker, NAME_PREFIX + "_nuevo_p"),
                renameTo(attacker, NAME_PREFIX + "_reservado_p"));
        assertEquals(NAME_PREFIX + "_atacante_p",
                userRepository.findById(attacker.getId()).orElseThrow().getUsername());
    }

    /**
     * Sustituye la instancia de {@link UsernameReservations} de los servicios
     * por una recién creada por Spring, como tras un arranque nuevo.
     */
    private void simulateRestart() {
        useReservations(beanFactory.createBean(UsernameReservations.class));
    }

    private void useReservations(UsernameReservations reservations) {
        // Los servicios pueden estar envueltos en un proxy (@Transactional):
        // el campo hay que cambiarlo en el objeto real. El tipo Object es
        // necesario: sin él, Java infiere Class para el genérico y elige la
        // sobrecarga de setField para campos estáticos.
        Object registration = AopTestUtils.getUltimateTargetObject(registrationService);
        Object users = AopTestUtils.getUltimateTargetObject(userService);
        ReflectionTestUtils.setField(registration, "usernameReservations", reservations);
        ReflectionTestUtils.setField(users, "usernameReservations", reservations);
    }
}
