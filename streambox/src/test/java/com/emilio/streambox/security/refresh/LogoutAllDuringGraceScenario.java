package com.emilio.streambox.security.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;

import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.repository.UserRepository;

/**
 * Escenario de la BAJA-A, compartido por H2 y PostgreSQL: un «cerrar sesión en
 * todos los dispositivos» confirma <b>mientras</b> un refresh está en la
 * gracia de las pestañas simultáneas, justo después de que el refresh haya
 * leído la versión de tokens y antes de que mire el sucesor.
 *
 * <p>
 * <b>Cómo se fuerza el momento.</b> {@code UserRepository} es un espía
 * ({@code @MockitoSpyBean}): cuando el refresh llama a
 * {@code findTokenVersionById}, se deja que lea de verdad y, antes de
 * devolverle el valor, otro hilo ejecuta {@code revokeAllSessions} completo
 * (con su commit). Así el refresh sigue con la versión vieja en la mano y un
 * sucesor que ya está revocado en la base de datos.
 * </p>
 *
 * <p>
 * <b>Qué se espera.</b> Sin gracia: el sucesor se lee después y se ve
 * revocado, así que el refresh da 401 y no emite ningún JWT. Antes del
 * arreglo el refresh no leía la versión (el hilo nunca se lanzaba), concedía
 * la gracia y firmaba el JWT con la entidad cargada después: con la carrera
 * real, con la versión <b>nueva</b>. Aquí falla en el {@code assertThrows}.
 * </p>
 *
 * <p>
 * El «cerrar todas las sesiones» no debe esperar al refresh (el token
 * presentado ya está revocado: el {@code UPDATE} no lo toca); si esperara, el
 * escenario sería otro y el {@code get} con tiempo límite lo diría en vez de
 * colgarse.
 * </p>
 */
public final class LogoutAllDuringGraceScenario {

    private LogoutAllDuringGraceScenario() {
    }

    /**
     * Ejecuta el escenario con un usuario verificado ya guardado.
     *
     * @param service           servicio real
     * @param spiedUserRepository el {@code UserRepository} del contexto, espiado
     * @param jdbc              acceso directo para comprobar el estado final
     * @param userId            id del usuario (verificado, sin sesiones)
     * @throws Exception si el hilo del «cerrar todas» falla o no termina
     */
    public static void assertNoGraceWhenLogoutAllCommitsAfterTheVersionRead(RefreshTokenService service,
            UserRepository spiedUserRepository, JdbcTemplate jdbc, Long userId) throws Exception {

        IssuedRefreshToken a = service.startFamily(userId);
        // A -> B: A queda revocado ahora mismo, dentro de la gracia de 10 s.
        assertNotNull(service.refresh(a.value()).refreshToken(), "A debía rotar a B");
        int versionBefore = tokenVersion(jdbc, userId);

        // El espía envuelve un proxy de Spring Data (una interfaz):
        // callRealMethod no sirve; su respuesta por defecto delega en el real.
        Answer<?> real = mockingDetails(spiedUserRepository).getMockCreationSettings().getDefaultAnswer();
        AtomicBoolean revokedInBetween = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                Object version = real.answer(invocation);
                if (revokedInBetween.compareAndSet(false, true)) {
                    pool.submit(() -> service.revokeAllSessions(userId)).get(10, TimeUnit.SECONDS);
                }
                return version;
            }).when(spiedUserRepository).findTokenVersionById(userId);

            assertThrows(SessionExpiredException.class, () -> service.refresh(a.value()),
                    "con el sucesor ya revocado no hay gracia (ni JWT)");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(revokedInBetween.get(), "el refresh debe leer la versión de tokens antes que el sucesor");
        assertEquals(versionBefore + 1, tokenVersion(jdbc, userId), "el «cerrar todas» confirmó");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class,
                userId), "no queda ninguna sesión viva");
    }

    private static int tokenVersion(JdbcTemplate jdbc, Long userId) {
        return jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, userId);
    }
}
