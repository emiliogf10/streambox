package com.emilio.streambox.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.mapper.UserMapper;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio con la lógica de negocio de las cuentas de usuario.
 *
 * <p>
 * Los métodos devuelven {@link UserResponse} (nunca la entidad, que contiene
 * la contraseña cifrada). La lista de favoritos tiene su propio servicio:
 * {@link FavoriteService}.
 * </p>
 */
@Service
public class UserService {

    /** Mensaje del 409 cuando el nombre de usuario pertenece a otra cuenta. */
    static final String USERNAME_TAKEN_MESSAGE = "El nombre de usuario ya está en uso";

    /** Mensaje del 409 cuando el email pertenece a otra cuenta. */
    static final String EMAIL_TAKEN_MESSAGE = "El correo electrónico ya está en uso";

    /**
     * Mensaje del 409 de una carrera en el registro cuando la base de datos no
     * dice qué restricción falló (no debería pasar con PostgreSQL ni con H2,
     * pero el mensaje sigue siendo cierto).
     */
    static final String USERNAME_OR_EMAIL_TAKEN_MESSAGE =
            "El nombre de usuario o el correo electrónico ya están en uso";

    /**
     * Caracteres invisibles en los extremos del nombre: separadores Unicode
     * (incluido el espacio de no separación), controles y caracteres de
     * formato. Es la misma expresión que usa {@link GenreService}: con un
     * simple {@code trim()}, que solo quita lo que es {@code <= U+0020}, un
     * espacio duro final creaba un «gemelo» visual de otro usuario.
     */
    private static final Pattern EDGES =
            Pattern.compile("^[\\p{Z}\\p{Cc}\\p{Cf}]+|[\\p{Z}\\p{Cc}\\p{Cf}]+$");

    /** Secuencias de separadores o controles dentro del nombre (se colapsan en un espacio). */
    private static final Pattern INNER_SPACES = Pattern.compile("[\\p{Z}\\p{Cc}]+");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Crea el servicio de usuarios.
     *
     * @param userRepository  repositorio de usuarios
     * @param passwordEncoder codificador con el que se cifran las contraseñas
     */
    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Lista todos los usuarios registrados (uso exclusivo de administradores).
     *
     * @return usuarios registrados
     */
    @Transactional(readOnly = true)
    public List<UserResponse> getAllUsers() {

        return UserMapper.toResponseList(userRepository.findAll());
    }

    /**
     * Obtiene los datos públicos de un usuario.
     *
     * @param id identificador del usuario
     * @return datos del usuario
     * @throws UserNotFoundException si no existe
     */
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {

        return userRepository.findById(id)
                .map(UserMapper::toResponse)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));
    }

    /**
     * Registra una cuenta nueva con rol {@link Role#USER}.
     *
     * <p>
     * El email se normaliza (sin espacios y en minúsculas), el nombre de
     * usuario con {@link #normalizeUsername} y la contraseña se
     * guarda cifrada con BCrypt. El rol nunca viene del cliente: los
     * administradores solo se crean con {@code AdminAccountInitializer}.
     * </p>
     *
     * @param request datos de registro (ya validados)
     * @return la cuenta creada
     * @throws UserAlreadyExistsException si el nombre de usuario o el email ya están en uso
     * @throws InvalidParameterException  si el nombre normalizado no tiene una longitud válida
     *                                    o el email normalizado no cabe en su columna
     */
    @Transactional
    public UserResponse registerUser(CreateUserRequest request) {

        String email = normalizeEmail(request.getEmail());
        String username = normalizeUsername(request.getUsername());

        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
        }
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException(EMAIL_TAKEN_MESSAGE);
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);

        try {
            return UserMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            throw translateRegistrationViolation(e);
        }
    }

    /**
     * Traduce la violación de integridad del {@code INSERT} del registro.
     *
     * <p>
     * <strong>Comprobación previa + restricción como respaldo</strong>, como en
     * {@link #updateProfile}: dos registros simultáneos con el mismo nombre o
     * email pasan los dos {@code existsBy...} y solo el {@code UNIQUE} de la
     * base de datos frena al segundo. Antes esa violación llegaba sin traducir
     * a {@code GlobalExceptionHandler}, que respondía un 409 genérico; hoy allí
     * sería un 500, así que se traduce aquí a la misma
     * {@link UserAlreadyExistsException} que la comprobación previa, gane quien
     * gane la carrera.
     * </p>
     *
     * <p>
     * <strong>Cuál de las dos.</strong> Aquí se insertan las dos columnas
     * únicas a la vez y, tras el error, la transacción ya no admite más
     * consultas (en PostgreSQL queda abortada), así que no se puede volver a
     * preguntar: se mira el nombre de la restricción ({@code uk_users_email} o
     * {@code uk_users_username}, de la migración {@code V1}). Cualquier otra
     * violación se relanza tal cual (500 y registro en el log): disfrazarla de
     * "ya está en uso" ocultaría un fallo.
     * </p>
     *
     * @param error excepción lanzada por el repositorio
     * @return la excepción de dominio a lanzar; si no es de unicidad, el propio {@code error}
     */
    private static RuntimeException translateRegistrationViolation(DataIntegrityViolationException error) {

        if (!(error instanceof DuplicateKeyException)
                && !SqlStates.UNIQUE_VIOLATION.equals(SqlStates.find(error))) {
            return error;
        }
        String constraint = SqlStates.constraintName(error);
        if (constraint != null && constraint.contains("uk_users_email")) {
            return new UserAlreadyExistsException(EMAIL_TAKEN_MESSAGE);
        }
        if (constraint != null && constraint.contains("uk_users_username")) {
            return new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
        }
        return new UserAlreadyExistsException(USERNAME_OR_EMAIL_TAKEN_MESSAGE);
    }

    /**
     * Cambia el nombre de usuario y/o el icono de perfil (avatar) de la cuenta
     * autenticada.
     *
     * <p>
     * Solo se leen {@link UpdateProfileRequest#username()} y el avatar
     * ({@link UpdateProfileRequest#avatarSent()} /
     * {@link UpdateProfileRequest#avatarKey()}): el email, el rol y la
     * contraseña nunca cambian aquí (el DTO ya rechaza que se envíen). Cada
     * campo ausente se deja como está; si no viene ninguno de los dos, 400 (ver
     * {@link UpdateProfileRequest}).
     * </p>
     *
     * <ul>
     * <li><b>Nombre</b>: se normaliza igual que en el registro. Un cambio solo
     * de mayúsculas ({@code bob} a {@code Bob}) sí es un cambio.</li>
     * <li><b>Avatar</b>: ya viene validado contra la lista blanca
     * ({@code Avatar}); {@code null} lo quita (la interfaz vuelve a la
     * inicial).</li>
     * </ul>
     * <p>
     * Si nada cambia de verdad (mismo nombre y mismo avatar), se responde sin
     * escribir.
     * </p>
     *
     * <p>
     * <strong>Comprobación previa + restricción como respaldo</strong> (como en
     * {@link GenreService}): {@code existsByUsername} da el 409 claro en el
     * caso normal, pero otra petición puede quedarse el nombre entre esa
     * consulta y el {@code UPDATE}. Entonces salta el {@code UNIQUE} de
     * {@code users.username} y su violación se traduce a la misma
     * {@link UserAlreadyExistsException}, gane quien gane la carrera. Por eso se
     * usa {@code saveAndFlush}: sin el {@code flush}, la violación aparecería al
     * confirmar la transacción, ya fuera de este método, como una violación
     * no traducida (500 {@code INTERNAL_ERROR}). El avatar no tiene
     * restricción de unicidad, así que una violación de unicidad solo puede
     * ser la del nombre.
     * </p>
     *
     * <p>
     * <strong>La contraseña no se revalida.</strong> La política prohíbe que la
     * contraseña contenga el nombre de usuario, pero aquí solo se tiene su hash
     * BCrypt, no el texto en claro, así que no se puede comprobar. La regla se
     * aplica al elegir la contraseña (registro).
     * </p>
     *
     * <p>
     * <strong>Sin bloqueo, pero sin pisar la contraseña.</strong> La fila se
     * lee sin bloquearla, así que un cambio de contraseña puede confirmarse
     * entre esa lectura y el {@code UPDATE}. No pasa nada porque {@link User}
     * lleva {@code @DynamicUpdate}: el {@code UPDATE} solo escribe
     * {@code username} y {@code avatar}, nunca el hash leído al principio. Aquí
     * no hace falta bloquear porque nada se decide a partir de la contraseña.
     * </p>
     *
     * <p>
     * La sesión no se ve afectada: el JWT identifica al usuario por su email y
     * el filtro de autenticación vuelve a leer la cuenta de la base de datos en
     * cada petición.
     * </p>
     *
     * @param userId  id del usuario autenticado (sale del token, nunca de la URL)
     * @param request cambios pedidos (ya validados)
     * @return los datos de la cuenta tras el cambio (completos, como {@code GET /api/users/me})
     * @throws InvalidParameterException  si no se pide ningún cambio o el nombre
     *                                    normalizado no tiene una longitud válida
     * @throws UserAlreadyExistsException si el nombre lo usa otra cuenta
     * @throws UserNotFoundException      si la cuenta ya no existe
     */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {

        boolean usernameSent = request.username() != null;
        if (!usernameSent && !request.avatarSent()) {
            throw new InvalidParameterException("username", UpdateProfileRequest.NO_CHANGES_MESSAGE);
        }
        // Se valida antes de ir a la base de datos: un nombre no válido no
        // necesita consultar nada.
        String username = usernameSent ? normalizeUsername(request.username()) : null;

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));

        boolean changed = false;
        if (usernameSent && !username.equals(user.getUsername())) {
            if (userRepository.existsByUsername(username)) {
                throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
            }
            user.setUsername(username);
            changed = true;
        }
        if (request.avatarSent() && !Objects.equals(request.avatarKey(), user.getAvatar())) {
            user.setAvatar(request.avatarKey());
            changed = true;
        }
        if (!changed) {
            return UserMapper.toResponse(user);
        }

        try {
            return UserMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // La única restricción de unicidad que puede tocar este UPDATE es
            // la de users.username. Cualquier otra violación (por ejemplo, el
            // CHECK de formato del avatar si el validador fallara) se relanza
            // tal cual: disfrazarla de "nombre en uso" ocultaría el problema.
            if (e instanceof DuplicateKeyException
                    || SqlStates.UNIQUE_VIOLATION.equals(SqlStates.find(e))) {
                throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
            }
            throw e;
        }
    }

    /**
     * Normaliza el email del registro (sin espacios exteriores y en minúsculas)
     * y comprueba que el resultado cabe en {@code users.email}.
     *
     * <p>
     * {@code @Size} en {@link CreateUserRequest} mide el texto <em>recibido</em>,
     * pero pasar a minúsculas puede alargarlo: {@code @Email} admite caracteres
     * no ASCII en la parte local y, por ejemplo, la {@code İ} (I con punto,
     * U+0130) se convierte en dos caracteres ({@code i} + punto combinante).
     * Un email de 100 caracteres válido para la anotación podía acabar con más
     * de 100 y fallar en la base de datos (antes un 409 genérico, hoy un 500); aquí recibe el
     * mismo 400 y el mismo mensaje que si fuera largo desde el principio.
     * </p>
     *
     * @param email email recibido (ya validado: no vacío, con formato y como mucho 100 caracteres)
     * @return el email normalizado
     * @throws InvalidParameterException si el resultado supera
     *                                   {@value CreateUserRequest#EMAIL_MAX_LENGTH} caracteres
     */
    static String normalizeEmail(String email) {

        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > CreateUserRequest.EMAIL_MAX_LENGTH) {
            throw new InvalidParameterException("email", CreateUserRequest.EMAIL_SIZE_MESSAGE);
        }
        return normalized;
    }

    /**
     * Normaliza un nombre de usuario y valida su longitud sobre el resultado.
     *
     * <p>
     * Quita lo invisible de los extremos ({@link #EDGES}) y colapsa los
     * espacios interiores en uno ({@link #INNER_SPACES}), para que
     * {@code "ana  lópez"} y {@code "ana lópez"} no sean dos cuentas que se ven
     * igual. No cambia mayúsculas: el nombre es el que el usuario eligió.
     * </p>
     *
     * <p>
     * Las anotaciones de los DTO miden el texto <em>recibido</em>; normalizar
     * puede acortarlo (un nombre de solo espacios duros pasaba {@code @Size} y
     * quedaba vacío), así que la longitud se vuelve a comprobar aquí, con el
     * mismo mensaje.
     * </p>
     *
     * @param username nombre recibido (ya validado como no nulo)
     * @return el nombre normalizado
     * @throws InvalidParameterException si el resultado no tiene entre 3 y 50 caracteres
     */
    static String normalizeUsername(String username) {

        String normalized = username == null ? ""
                : INNER_SPACES.matcher(EDGES.matcher(username).replaceAll("")).replaceAll(" ");
        int length = normalized.length();
        if (length < UpdateProfileRequest.USERNAME_MIN_LENGTH
                || length > UpdateProfileRequest.USERNAME_MAX_LENGTH) {
            throw new InvalidParameterException("username", UpdateProfileRequest.USERNAME_SIZE_MESSAGE);
        }
        return normalized;
    }
}
