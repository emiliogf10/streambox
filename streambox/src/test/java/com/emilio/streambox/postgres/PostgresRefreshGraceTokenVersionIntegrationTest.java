package com.emilio.streambox.postgres;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.refresh.LogoutAllDuringGraceScenario;
import com.emilio.streambox.security.refresh.RefreshTokenService;

/**
 * BAJA-A sobre un <b>PostgreSQL real</b> (READ COMMITTED de verdad, donde la
 * carrera existe): un «cerrar sesión en todos los dispositivos» que confirma
 * entre la lectura de la versión de tokens y la del sucesor deja al refresh
 * sin gracia y sin JWT. El escenario, en {@link LogoutAllDuringGraceScenario};
 * la versión de H2, en {@code RefreshGraceTokenVersionIntegrationTest}.
 *
 * <p>
 * Comprueba además una premisa que H2 no reproduce fielmente: el
 * {@code UPDATE} de {@code revokeAllByUserId} no espera al bloqueo que el
 * refresh tiene sobre el token presentado (ya revocado, no cumple el
 * {@code WHERE}); si esperara, el escenario agotaría su tiempo límite.
 * </p>
 */
class PostgresRefreshGraceTokenVersionIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private UserRepository spiedUsers;

    @BeforeEach
    @AfterEach
    void clean() {
        cleanDatabase();
    }

    @Test
    void unCerrarTodasEntreLaVersionYElSucesorDejaSinGracia() throws Exception {
        User user = saveUser("graciaversion", Role.USER);

        LogoutAllDuringGraceScenario.assertNoGraceWhenLogoutAllCommitsAfterTheVersionRead(refreshTokenService,
                spiedUsers, jdbc, user.getId());
    }
}
