package com.emilio.streambox.security.refresh;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * BAJA-A sobre H2: un «cerrar sesión en todos los dispositivos» que confirma
 * mientras un refresh está en la gracia, entre la lectura de la versión y la
 * del sucesor, no deja que la gracia emita un JWT (ver
 * {@link LogoutAllDuringGraceScenario}). La misma prueba contra PostgreSQL
 * real está en {@code PostgresRefreshGraceTokenVersionIntegrationTest}.
 *
 * <p>
 * Sin {@code @Transactional}: el refresh y el «cerrar todas» van en
 * transacciones distintas y con commit real.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshGraceTokenVersionIntegrationTest {

    private static final String DOMAIN = "@gracia-version.refresh.test";

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private UserRepository userRepository;

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    @Test
    void unCerrarTodasEntreLaVersionYElSucesorDejaSinGracia() throws Exception {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now());
        user.setUsername("gracia-version");
        user.setEmail("ana" + DOMAIN);
        user.setPassword(passwordEncoder.encode("Contraseña-Gracia-Version-2026"));
        user.setRole(Role.USER);
        userRepository.save(user);

        LogoutAllDuringGraceScenario.assertNoGraceWhenLogoutAllCommitsAfterTheVersionRead(refreshTokenService,
                userRepository, jdbc, user.getId());
    }
}
