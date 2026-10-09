package com.emilio.streambox.security.refresh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * La limpieza de {@code refresh_tokens} está programada de verdad cuando
 * {@code streambox.auth.refresh.cleanup.enabled=true} (lo normal fuera de los
 * tests): un token de una familia caducada desaparece sin que nadie llame al
 * servicio. Comprueba también que el arranque acepta el intervalo con el
 * formato de duración de Spring Boot. La lógica del borrado (retención) se
 * prueba en {@link RefreshTokenIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "streambox.auth.refresh.cleanup.enabled=true",
        "streambox.auth.refresh.cleanup.interval=200ms",
        "streambox.auth.refresh.cleanup.initial-delay=0s"
})
class RefreshTokenCleanupSchedulingIntegrationTest {

    private static final String EMAIL = "limpieza@cleanup.refresh.test";

    @Autowired private ApplicationContext context;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @AfterEach
    void deleteUser() {
        userRepository.findByEmail(EMAIL).ifPresent(userRepository::delete);
    }

    @Test
    void laTareaProgramadaBorraLosTokensDeFamiliasCaducadas() throws Exception {
        assertTrue(context.getBeanNamesForType(RefreshTokenCleanupConfig.class).length == 1);
        User user = new User();
        user.setUsername("limpieza-programada");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode("Contraseña-Limpieza-2026"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);

        Instant now = Instant.now();
        RefreshToken expired = new RefreshToken();
        expired.setUser(user);
        expired.setTokenHash(RefreshTokenService.sha256Hex(UUID.randomUUID().toString()));
        expired.setFamilyId(UUID.randomUUID());
        expired.setCreatedAt(now.minus(Duration.ofDays(40)));
        expired.setExpiresAt(now.minus(Duration.ofDays(33)));
        expired.setFamilyExpiresAt(now.minus(Duration.ofDays(10)));
        long id = refreshTokenRepository.save(expired).getId();

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (refreshTokenRepository.existsById(id) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertFalse(refreshTokenRepository.existsById(id), "la limpieza programada no borró el token caducado");
    }
}
