package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/** Tests unitarios de {@link AdminAccountInitializer}. */
class AdminAccountInitializerTest {

    private static final String PASSWORD = "una-contraseña-larga-123";

    private UserRepository userRepository;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
    }

    private AdminAccountInitializer initializer(String email, String username, String password) {
        return new AdminAccountInitializer(
                new AdminProperties(email, username, password), userRepository, passwordEncoder);
    }

    @Test
    void sinVariablesNoHaceNada() {
        initializer("", "admin", "").run(null);
        initializer(null, "admin", null).run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void creaElAdministradorConRolAdminYContrasenaCifrada() {
        initializer("  Admin@Test.com ", "admin", PASSWORD).run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertEquals(Role.ADMIN, saved.getRole());
        assertEquals("admin@test.com", saved.getEmail());
        assertEquals("admin", saved.getUsername());
        assertNotEquals(PASSWORD, saved.getPassword());
        assertTrue(passwordEncoder.matches(PASSWORD, saved.getPassword()));
    }

    @Test
    void siYaExisteNoLoModificaNiSobrescribeLaContrasena() {
        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);

        initializer("admin@test.com", "admin", PASSWORD).run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void rechazaContrasenasCortas() {
        assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", "corta").run(null));

        verify(userRepository, never()).save(any());
    }

    @Test
    void rechazaEmailInvalido() {
        assertThrows(IllegalStateException.class,
                () -> initializer("no-es-un-email", "admin", PASSWORD).run(null));
    }

    @Test
    void rechazaUsernameDeOtraCuenta() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", PASSWORD).run(null));

        verify(userRepository, never()).save(any());
    }
}
