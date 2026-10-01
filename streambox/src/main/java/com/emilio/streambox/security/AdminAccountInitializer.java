package com.emilio.streambox.security;

import java.time.LocalDateTime;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Crea el primer administrador al arrancar la aplicación.
 *
 * <p>
 * El registro público ({@code POST /api/users}) siempre crea usuarios con rol
 * {@code USER}, y no debe poder crear administradores. Para no tener que
 * insertarlos a mano en la base de datos, el primer administrador se define
 * mediante las variables de entorno {@code ADMIN_EMAIL}, {@code ADMIN_USERNAME}
 * (opcional) y {@code ADMIN_PASSWORD}.
 * </p>
 *
 * <ul>
 *   <li>Sin esas variables no hace nada.</li>
 *   <li>Si ya existe un usuario con ese email, no lo modifica: nunca
 *       sobrescribe una contraseña existente.</li>
 *   <li>La contraseña no se escribe en los logs ni en el código.</li>
 * </ul>
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAccountInitializer.class);

    static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * @param properties      datos del administrador (pueden estar vacíos)
     * @param userRepository  repositorio de usuarios
     * @param passwordEncoder codificador con el que se cifra la contraseña
     */
    public AdminAccountInitializer(
            AdminProperties properties,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {

        if (!properties.isConfigured()) {
            return;
        }

        String email = properties.email().trim().toLowerCase(Locale.ROOT);
        String username = properties.username() == null ? "" : properties.username().trim();

        if (!email.contains("@") || email.length() > 100) {
            throw new IllegalStateException("ADMIN_EMAIL no es un correo electrónico válido");
        }
        if (username.length() < 3 || username.length() > 50) {
            throw new IllegalStateException("ADMIN_USERNAME debe tener entre 3 y 50 caracteres");
        }
        if (properties.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_PASSWORD debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres");
        }

        if (userRepository.existsByEmail(email)) {
            LOGGER.info("El administrador inicial ya existe; no se modifica");
            return;
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException(
                    "No se puede crear el administrador: el nombre de usuario '"
                            + username + "' ya está en uso por otra cuenta");
        }

        User admin = new User();
        admin.setEmail(email);
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(properties.password()));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(LocalDateTime.now());
        userRepository.save(admin);

        LOGGER.info("Administrador inicial creado: {}", email);
    }
}
