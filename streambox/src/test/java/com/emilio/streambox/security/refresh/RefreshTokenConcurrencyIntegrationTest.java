package com.emilio.streambox.security.refresh;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Varios refresh simultáneos con el mismo token (pestañas que despiertan a la
 * vez) sobre H2: exactamente uno rota y el resto entra en la gracia. La misma
 * prueba contra PostgreSQL real está en
 * {@code PostgresRefreshTokenServiceConcurrencyIntegrationTest}, que es la que
 * reproduce fielmente los bloqueos de fila.
 *
 * <p>
 * Protege el bloqueo de {@code findByTokenHashForUpdate}: sin él, varias
 * transacciones leen el token aún activo y rotan todas (varios sucesores
 * válidos en la familia) o chocan al escribir la misma fila (500).
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: cada hilo necesita su propia transacción, y los
 * datos del test deben estar confirmados para que los vean.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenConcurrencyIntegrationTest {

    private static final String DOMAIN = "@concurrency.refresh.test";

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    @Test
    void variosRefreshSimultaneosDelMismoTokenSoloRotanUnaVez() throws Exception {
        User user = new User();
        user.setUsername("concurrency-refresh");
        user.setEmail("ana" + DOMAIN);
        user.setPassword(passwordEncoder.encode("Contraseña-Concurrente-2026"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        RefreshConcurrencyScenario.assertSingleRotation(refreshTokenService, refreshTokenRepository,
                refreshTokenService.startFamily(user.getId()), 8);
    }
}
