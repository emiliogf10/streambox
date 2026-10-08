package com.emilio.streambox.security;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
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
 *   <li>Si ya existe un <b>administrador</b> con ese email, no lo modifica:
 *       nunca sobrescribe una contraseña existente, y tampoco valida la
 *       contraseña configurada (ver más abajo).</li>
 *   <li>Si ya existe una cuenta con ese email pero <b>no es ADMIN</b> (el
 *       registro público es abierto: alguien pudo registrarse con ese correo
 *       antes del primer arranque), la aplicación <b>no arranca</b> y el error
 *       dice qué hacer (elegir otro {@code ADMIN_EMAIL} o corregir esa cuenta).
 *       Nunca se promueve una cuenta existente: se la daría a quien llegó
 *       primero. Lo mismo si el {@code ADMIN_USERNAME} lo ocupa otra cuenta.
 *       Si entre la comprobación y el guardado otra cuenta ocupa el correo o el
 *       usuario (carrera), la restricción única de la base de datos lo impide
 *       y el error se traduce al mismo mensaje claro.</li>
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

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            // Solo cuenta como "el administrador ya existe" si de verdad lo es.
            // El registro público es abierto: alguien pudo registrarse con
            // ADMIN_EMAIL antes del primer arranque. Dar eso por bueno dejaría
            // la instalación sin administrador sin avisar (y promover la
            // cuenta sería peor: se la daría a quien llegó primero).
            requireAdmin(existing.get(), email);

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
            throw usernameTaken(username);
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
        try {
            userRepository.save(admin);
        } catch (DataIntegrityViolationException raceLost) {
            // Entre la comprobación y el guardado otra cuenta ocupó el correo o el
            // usuario (un registro simultáneo, o otra réplica arrancando a la vez).
            // La restricción única de la base de datos lo impide, pero el error
            // crudo no dice qué hacer: se traduce a uno accionable.
            handleLostRace(email, username, raceLost);
            return;
        }

        LOGGER.info("Administrador inicial creado: {}", email);
    }

    /**
     * Exige que la cuenta que ya usa {@code ADMIN_EMAIL} sea de un
     * administrador. Si no lo es, aborta el arranque: la cuenta se registró
     * por la vía pública (siempre {@code USER}) y no se toca. Nunca se
     * promueve una cuenta existente.
     */
    private static void requireAdmin(User account, String email) {
        if (account.getRole() != Role.ADMIN) {
            throw notAnAdmin(email);
        }
    }

    private static IllegalStateException notAnAdmin(String email) {
        return new IllegalStateException(
                "No se puede crear el administrador: ya existe una cuenta con el correo de ADMIN_EMAIL ("
                        + email + ") pero su rol no es ADMIN (probablemente se registró públicamente antes "
                        + "del primer arranque). Por seguridad no se promueve a administrador. Elige otro "
                        + "ADMIN_EMAIL, corrige o elimina esa cuenta en la base de datos, o deja "
                        + "ADMIN_EMAIL/ADMIN_PASSWORD vacíos para arrancar sin administrador, y reinicia");
    }

    private static IllegalStateException usernameTaken(String username) {
        return new IllegalStateException(
                "No se puede crear el administrador: el nombre de usuario '" + username
                        + "' (ADMIN_USERNAME) ya está en uso por otra cuenta distinta de la de ADMIN_EMAIL. "
                        + "Elige otro ADMIN_USERNAME, o renombra o elimina esa cuenta, y reinicia");
    }

    /**
     * Interpreta una violación de unicidad al guardar: vuelve a mirar quién
     * ocupa ahora el correo y el usuario para dar el mismo error claro que
     * habría dado la comprobación previa. Si el correo lo ocupa ya un
     * administrador (otra réplica lo creó a la vez) no es un fallo y vuelve
     * sin lanzar nada.
     */
    private void handleLostRace(String email, String username, RuntimeException cause) {
        Optional<User> owner = userRepository.findByEmail(email);
        if (owner.isPresent()) {
            requireAdmin(owner.get(), email);
            LOGGER.info("El administrador inicial ya existe; no se modifica");
            return;
        }
        if (userRepository.existsByUsername(username)) {
            throw usernameTaken(username);
        }
        throw new IllegalStateException(
                "No se pudo crear el administrador por un conflicto de unicidad en la base de datos "
                        + "(correo o usuario ocupados al guardar). Reinicia la aplicación y, si se repite, "
                        + "revisa las cuentas con ADMIN_EMAIL y ADMIN_USERNAME", cause);
    }
}
