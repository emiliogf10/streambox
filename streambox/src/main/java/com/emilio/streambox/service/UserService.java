package com.emilio.streambox.service;

import java.util.List;
import java.util.Locale;
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
     */
    @Transactional
    public UserResponse registerUser(CreateUserRequest request) {

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String username = normalizeUsername(request.getUsername());

        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
        }
        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException("El correo electrónico ya está en uso");
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);

        return UserMapper.toResponse(userRepository.save(user));
    }

    /**
     * Cambia el nombre de usuario de la cuenta autenticada.
     *
     * <p>
     * Solo se lee {@link UpdateProfileRequest#username()}: el email, el rol y
     * la contraseña nunca cambian aquí (el DTO ya rechaza que se envíen). El
     * nombre se normaliza igual que en el registro. Si coincide con el actual
     * se responde sin escribir nada; un cambio solo de mayúsculas
     * ({@code bob} a {@code Bob}) sí es un cambio.
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
     * confirmar la transacción, ya fuera de este método, como un 409
     * {@code DATA_INTEGRITY_VIOLATION} genérico.
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
     * La sesión no se ve afectada: el JWT identifica al usuario por su email y
     * el filtro de autenticación vuelve a leer la cuenta de la base de datos en
     * cada petición.
     * </p>
     *
     * @param userId  id del usuario autenticado (sale del token, nunca de la URL)
     * @param request cambios pedidos (ya validados)
     * @return los datos de la cuenta tras el cambio
     * @throws InvalidParameterException  si el nombre normalizado no tiene una longitud válida
     * @throws UserAlreadyExistsException si el nombre lo usa otra cuenta
     * @throws UserNotFoundException      si la cuenta ya no existe
     */
    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {

        String username = normalizeUsername(request.username());

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));

        if (username.equals(user.getUsername())) {
            return UserMapper.toResponse(user);
        }
        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
        }

        user.setUsername(username);
        try {
            return UserMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // Aquí solo cambia el nombre, así que una violación de unicidad
            // solo puede ser la de users.username. Cualquier otra se relanza
            // tal cual: disfrazarla de "nombre en uso" ocultaría el problema.
            if (e instanceof DuplicateKeyException
                    || SqlStates.UNIQUE_VIOLATION.equals(SqlStates.find(e))) {
                throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
            }
            throw e;
        }
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
