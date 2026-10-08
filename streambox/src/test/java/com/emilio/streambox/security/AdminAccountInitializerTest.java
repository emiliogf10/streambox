package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static User account(Role role) {
        User user = new User();
        user.setRole(role);
        return user;
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
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(account(Role.ADMIN)));

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
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(account(Role.ADMIN)));

        initializer("admin@test.com", "admin", "Password1234").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void rechazaEmailInvalido() {
        assertThrows(IllegalStateException.class,
                () -> initializer("no-es-un-email", "admin", PASSWORD).run(null));
    }

    @Test
    void rechazaUsernameDeOtraCuentaConUnMensajeAccionable() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", PASSWORD).run(null));

        assertTrue(error.getMessage().contains("'admin'"), error.getMessage());
        assertTrue(error.getMessage().contains("ADMIN_USERNAME"), error.getMessage());
        assertFalse(error.getMessage().contains(PASSWORD));
        verify(userRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // NV-3: una cuenta previa con ADMIN_EMAIL que NO es administrador
    // ------------------------------------------------------------------

    /**
     * Regresión (NV-3): el registro público es abierto, así que alguien pudo
     * registrarse con ADMIN_EMAIL antes del primer arranque. Antes se daba por
     * hecho que «el administrador ya existe» y la instalación quedaba sin
     * administrador sin avisar. Ahora el arranque se aborta con un error que
     * dice qué hacer, y la cuenta no se toca (ni se promueve).
     */
    @Test
    void siLaCuentaDelCorreoEsUnUserAbortaElArranqueSinPromoverla() {
        User impostor = account(Role.USER);
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(impostor));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("Admin@Test.com", "admin", PASSWORD).run(null));

        assertTrue(error.getMessage().contains("ADMIN_EMAIL"), error.getMessage());
        assertTrue(error.getMessage().contains("admin@test.com"), error.getMessage());
        assertTrue(error.getMessage().contains("no es ADMIN"), error.getMessage());
        assertTrue(error.getMessage().contains("Elige otro ADMIN_EMAIL"), error.getMessage());
        assertTrue(error.getMessage().contains("ADMIN_EMAIL/ADMIN_PASSWORD vacíos para arrancar sin administrador"),
                error.getMessage());
        assertFalse(error.getMessage().contains(PASSWORD), "El mensaje no debe incluir la contraseña");
        assertEquals(Role.USER, impostor.getRole(), "Nunca se promueve una cuenta existente");
        verify(userRepository, never()).save(any());
    }

    /** Aunque la contraseña configurada no cumpla la política, el error es el de la cuenta ajena. */
    @Test
    void siLaCuentaDelCorreoEsUnUserFallaAunqueLaContrasenaNoCumplaLaPolitica() {
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(account(Role.USER)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", "corta").run(null));

        assertTrue(error.getMessage().contains("no es ADMIN"), error.getMessage());
    }

    /** Una cuenta sin rol tampoco se acepta como administrador. */
    @Test
    void unaCuentaSinRolNoCuentaComoAdministrador() {
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(account(null)));

        assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", PASSWORD).run(null));
    }

    /** El caso normal: si ya hay un ADMIN con ese correo, se arranca sin modificar nada. */
    @Test
    void siLaCuentaDelCorreoYaEsAdminArrancaYNoLaModifica() {
        User existing = account(Role.ADMIN);
        existing.setPassword("hash-original");
        when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(existing));

        initializer("admin@test.com", "admin", PASSWORD).run(null);

        assertEquals("hash-original", existing.getPassword());
        assertEquals(Role.ADMIN, existing.getRole());
        verify(userRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // NV-3: carrera entre la comprobación y el guardado
    // ------------------------------------------------------------------

    /** Un registro público ocupó el correo justo antes de guardar: error claro de cuenta ajena. */
    @Test
    void siUnRegistroOcupaElCorreoEntreLaComprobacionYElGuardadoElErrorEsClaro() {
        when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Optional.empty())                       // comprobación previa
                .thenReturn(Optional.of(account(Role.USER)));       // tras la violación
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", PASSWORD).run(null));

        assertTrue(error.getMessage().contains("no es ADMIN"), error.getMessage());
        assertFalse(error.getMessage().contains("uk_users_email"), "No se filtra el detalle de la base de datos");
    }

    /** Otra cuenta ocupó el usuario justo antes de guardar: mismo mensaje que la comprobación previa. */
    @Test
    void siOtraCuentaOcupaElUsuarioEntreLaComprobacionYElGuardadoElErrorEsClaro() {
        when(userRepository.existsByUsername("admin")).thenReturn(false).thenReturn(true);
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_users_username"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", PASSWORD).run(null));

        assertTrue(error.getMessage().contains("ADMIN_USERNAME"), error.getMessage());
        verify(userRepository, times(1)).save(any());
    }

    /** Otra instancia creó al administrador a la vez: no es un fallo, se arranca. */
    @Test
    void siOtraInstanciaCreaAlAdministradorALaVezNoEsUnError() {
        when(userRepository.findByEmail("admin@test.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(account(Role.ADMIN)));
        when(userRepository.save(any())).thenThrow(new DataIntegrityViolationException("uk_users_email"));

        initializer("admin@test.com", "admin", PASSWORD).run(null);

        verify(userRepository, times(1)).save(any());
    }

    /** Un conflicto de unicidad que no se sabe explicar sigue abortando el arranque, con su causa. */
    @Test
    void siElConflictoDeUnicidadNoSeExplicaAbortaConUnMensajeGenerico() {
        DataIntegrityViolationException cause = new DataIntegrityViolationException("raro");
        when(userRepository.save(any())).thenThrow(cause);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", PASSWORD).run(null));

        assertTrue(error.getMessage().contains("conflicto de unicidad"), error.getMessage());
        assertEquals(cause, error.getCause());
    }
}
