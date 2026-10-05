package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", "corta").run(null));

        // Ahora la longitud la comprueba la política, con su mensaje, en el
        // mismo punto que el resto de reglas.
        assertEquals("ADMIN_PASSWORD no es válida: La contraseña debe tener entre 12 y 64 caracteres",
                error.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void rechazaContrasenasComunesConUnMensajeClaro() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", "Password1234").run(null));

        assertEquals("ADMIN_PASSWORD no es válida: La contraseña es demasiado común. "
                + "Elige otra más difícil de adivinar.", error.getMessage());
        verify(userRepository, never()).save(any());
    }

    @Test
    void rechazaContrasenasQueContienenElUsuarioOElEmail() {
        IllegalStateException byUsername = assertThrows(IllegalStateException.class,
                () -> initializer("jefa@test.com", "admin", "MiAdminSeguro-2026").run(null));
        IllegalStateException byEmail = assertThrows(IllegalStateException.class,
                () -> initializer("jefa@test.com", "root", "Soy-la-JEFA-2026").run(null));

        assertTrue(byUsername.getMessage().endsWith("no puede contener tu nombre de usuario ni tu email."));
        assertTrue(byEmail.getMessage().endsWith("no puede contener tu nombre de usuario ni tu email."));
        verify(userRepository, never()).save(any());
    }

    /**
     * Sin la regla de bytes, {@code BCryptPasswordEncoder.encode} lanzaría un
     * {@code IllegalArgumentException} en inglés ("password cannot be more
     * than 72 bytes") y la aplicación no arrancaría sin explicar qué variable
     * hay que corregir.
     */
    @Test
    void rechazaContrasenasDeMasDe72BytesAntesDeLlegarABcrypt() {
        String password = "Contraseña-" + "ñ".repeat(31); // 42 caracteres, 74 bytes

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", password).run(null));

        assertTrue(error.getMessage().startsWith("ADMIN_PASSWORD no es válida: La contraseña es demasiado larga"));
        assertFalse(error.getMessage().contains(password), "El mensaje no debe incluir la contraseña");
        verify(userRepository, never()).save(any());
    }

    /**
     * La política nueva solo se aplica al crear el administrador: una
     * instalación que ya lo tiene creado sigue arrancando aunque su
     * ADMIN_PASSWORD no cumpla las reglas añadidas después.
     */
    @Test
    void siElAdministradorYaExisteNoAplicaLaPoliticaNueva() {
        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);

        initializer("admin@test.com", "admin", "Password1234").run(null);

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
