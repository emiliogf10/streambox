package com.emilio.streambox.security;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.password.PasswordPolicy;

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
 *       sobrescribe una contraseña existente, y tampoco valida la contraseña
 *       configurada (ver más abajo).</li>
 *   <li>Al crearlo, la contraseña debe cumplir la misma {@link PasswordPolicy}
 *       que el registro público (12 a 64 caracteres, máximo 72 bytes, no
 *       común, sin el usuario ni el email). Si no la cumple, la aplicación no
 *       arranca y el error dice qué regla falla: es mejor que un administrador
 *       con una contraseña de diccionario, que es la cuenta más valiosa.</li>
 *   <li>La contraseña no se escribe en los logs ni en el código.</li>
 * </ul>
 *
 * <p>
 * <b>Por qué la política solo se aplica al crear.</b> Con el administrador ya
 * creado, {@code ADMIN_PASSWORD} no se usa para nada: no se cifra ni se guarda.
 * Validarla entonces solo serviría para que una instalación que funcionaba
 * dejara de arrancar al endurecerse la política, igual que el registro no
 * obliga a cambiar la contraseña a las cuentas antiguas. Antes había una
 * incoherencia: el mínimo de 12 caracteres se comprobaba siempre (incluso con
 * el administrador ya existente) y el resto de reglas solo al crear; ahora se
 * comprueban todas en el mismo punto. Si la contraseña configurada no cumple
 * la política, se avisa en el log, porque si sigue siendo la del administrador
 * conviene cambiarla. El aviso no incluye la contraseña ni la regla concreta
 * que incumple (sería una pista sobre la contraseña de una cuenta activa).
 * </p>
 */
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAccountInitializer.class);

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

        // Los mensajes de PasswordPolicy son constantes y nunca incluyen la
        // contraseña, así que pueden ir a la excepción sin filtrarla.
        Optional<String> violation = PasswordPolicy.findViolation(properties.password(), username, email);

        if (userRepository.existsByEmail(email)) {
            // Ya existe: la contraseña configurada no se usa, así que no se
            // valida (ver el Javadoc de la clase); como mucho, se avisa. El
            // aviso no dice qué regla falla: si sigue siendo la contraseña de
            // una cuenta activa, "es demasiado común" sería una pista para
            // quien lea los logs (que a veces acaban en servicios externos).
            if (violation.isPresent()) {
                LOGGER.warn("ADMIN_PASSWORD no cumple la política de contraseñas actual. No se aplica "
                        + "porque el administrador ya existe; si sigue siendo su contraseña, conviene "
                        + "cambiarla, y la variable puede retirarse porque ya no se usa.");
            }
            LOGGER.info("El administrador inicial ya existe; no se modifica");
            return;
        }
        if (userRepository.existsByUsername(username)) {
            throw new IllegalStateException(
                    "No se puede crear el administrador: el nombre de usuario '"
                            + username + "' ya está en uso por otra cuenta");
        }

        // Se va a crear de verdad: la contraseña debe cumplir toda la política.
        if (violation.isPresent()) {
            throw new IllegalStateException("ADMIN_PASSWORD no es válida: " + violation.get());
        }

        User admin = new User();
        admin.setEmail(email);
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(properties.password()));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);

        LOGGER.info("Administrador inicial creado: {}", email);
    }
}
