package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.emilio.streambox.repository.UserRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Casos límite de {@link AdminAccountInitializer} con la política de
 * contraseñas (revisión independiente de QA).
 *
 * <p>
 * Protege que endurecer la política no impida arrancar una instalación que ya
 * tiene su administrador (con cualquier contraseña configurada: corta, larga,
 * con muchos bytes o con el usuario; solo se avisa en el log), y que el
 * administrador nuevo pase por las mismas reglas que el registro.
 * </p>
 */
class AdminAccountInitializerEdgeCasesTest {

    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
    }

    private AdminAccountInitializer initializer(String email, String username, String password) {
        return new AdminAccountInitializer(
                new AdminProperties(email, username, password), userRepository, new BCryptPasswordEncoder());
    }

    /**
     * Si el administrador ya existe, una ADMIN_PASSWORD que hoy no cumpliría
     * la política (65 caracteres, más de 72 bytes o con el nombre de usuario)
     * no impide arrancar ni llega a cifrarse (BCrypt fallaría con más de 72
     * bytes).
     */
    @Test
    void siYaExisteArrancaConContrasenasQueLaPoliticaNuevaRechazaria() {
        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);
        String tooLong = "Lince-Iberico-Donana-2026!Halcon-Peregrino-Gredos#Oso-Pardo-Somie";
        String tooManyBytes = "Contraseña-" + "ñ".repeat(31);
        assertEquals(65, tooLong.length());
        assertTrue(tooManyBytes.getBytes(StandardCharsets.UTF_8).length > 72);

        assertDoesNotThrow(() -> initializer("admin@test.com", "admin", tooLong).run(null));
        assertDoesNotThrow(() -> initializer("admin@test.com", "admin", tooManyBytes).run(null));
        assertDoesNotThrow(() -> initializer("admin@test.com", "admin", "MiAdminSeguro-2026").run(null));

        verify(userRepository, never()).save(any());
    }

    /**
     * Regresión de coherencia: antes, con el administrador ya creado, una
     * ADMIN_PASSWORD de menos de 12 caracteres seguía impidiendo arrancar
     * (se comprobaba antes de mirar si existía), mientras que el resto de
     * reglas no. Ahora ninguna regla se aplica si la cuenta ya existe: se
     * arranca, no se guarda nada y solo se avisa en el log, sin la
     * contraseña ni la regla concreta.
     */
    @Test
    void siYaExisteArrancaAunqueLaContrasenaConfiguradaSeaCortaYSoloAvisa() {
        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);
        String shortPassword = "Corta-1";

        List<ILoggingEvent> logs = captureLogs(() -> assertDoesNotThrow(
                () -> initializer("admin@test.com", "admin", shortPassword).run(null)));

        verify(userRepository, never()).save(any());
        List<ILoggingEvent> warnings = logs.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), () -> "Avisos: " + warnings);
        String warning = warnings.get(0).getFormattedMessage();
        assertTrue(warning.contains("ADMIN_PASSWORD no cumple la política"), warning);
        for (ILoggingEvent event : logs) {
            assertFalse(event.getFormattedMessage().contains(shortPassword), "El log no debe incluir la contraseña");
            assertFalse(event.getFormattedMessage().contains("entre 12 y 64"), "El log no debe decir qué regla falla");
        }
    }

    /** Si la contraseña configurada cumple la política, no hay aviso. */
    @Test
    void siYaExisteYLaContrasenaCumpleNoHayAviso() {
        when(userRepository.existsByEmail("admin@test.com")).thenReturn(true);

        List<ILoggingEvent> logs = captureLogs(
                () -> initializer("admin@test.com", "admin", "una-contraseña-larga-123").run(null));

        assertTrue(logs.stream().noneMatch(e -> e.getLevel() == Level.WARN), () -> "Avisos: " + logs);
    }

    /** Al crear el administrador, 65 caracteres se rechazan con el mensaje de longitud. */
    @Test
    void alCrearloRechazaMasDe64Caracteres() {
        String tooLong = "Lince-Iberico-Donana-2026!Halcon-Peregrino-Gredos#Oso-Pardo-Somie";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("admin@test.com", "admin", tooLong).run(null));

        assertEquals("ADMIN_PASSWORD no es válida: La contraseña debe tener entre 12 y 64 caracteres",
                error.getMessage());
        assertFalse(error.getMessage().contains(tooLong));
        verify(userRepository, never()).save(any());
    }

    /**
     * El usuario se compara recortado (como se guarda) y sin distinguir
     * mayúsculas: ADMIN_USERNAME="  Root  " impide una contraseña con "ROOT".
     */
    @Test
    void elUsuarioSeComparaRecortadoYSinMayusculas() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer("jefa@test.com", "  Root  ", "Mi-ROOT-del-servidor-26").run(null));

        assertTrue(error.getMessage().endsWith("no puede contener tu nombre de usuario ni tu email."));
    }

    /**
     * Las credenciales del backend efímero de la suite E2E
     * ({@code frontend/e2e/support/config.ts}) deben cumplir la política, o
     * Playwright no podría arrancar su backend. Se copian aquí a propósito:
     * si alguien las cambia allí por unas que no cumplen, el E2E fallará al
     * arrancar con el mensaje de esta regla.
     */
    @Test
    void lasCredencialesDelPerfilE2eCumplenLaPolitica() {
        assertDoesNotThrow(() -> initializer(
                "admin-e2e@streambox.local", "admin-e2e", "e2e-contrasena-admin-1").run(null));
    }

    /**
     * El {@code toString()} de {@link AdminProperties} no muestra
     * {@code ADMIN_PASSWORD}: el que genera Java para un {@code record} sí la
     * incluiría si alguien registrase el objeto en un log.
     */
    @Test
    void toStringDeLasPropiedadesNoMuestraLaContrasena() {
        String password = "contrasena-que-no-debe-salir-en-logs";

        String text = new AdminProperties("admin@test.com", "admin", password).toString();

        assertFalse(text.contains(password));
        assertTrue(text.contains("admin@test.com"));
    }

    /**
     * Ejecuta la acción capturando lo que escribe el logger de
     * {@link AdminAccountInitializer} (con un appender de Logback en memoria,
     * independiente de la configuración de consola).
     */
    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminAccountInitializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return List.copyOf(appender.list);
    }
}
