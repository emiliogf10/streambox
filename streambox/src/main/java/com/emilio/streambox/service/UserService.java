package com.emilio.streambox.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.dto.UserResponse;
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
 * {@link FavoriteService}; el registro, {@link RegistrationService} (que usa
 * las normalizaciones de aquí).
 * </p>
 */
@Service
public class UserService {

    /**
     * Mensaje del 409 cuando el nombre de usuario pertenece a otra cuenta (en
     * «Editar perfil» y en el registro, {@link RegistrationService}). Ya no hay
     * mensaje de «correo en uso»: el registro no revela qué emails tienen cuenta.
     */
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
    private final UsernameReservations usernameReservations;
    private final EmailVerificationService emailVerificationService;

    /**
     * Crea el servicio de usuarios.
     *
     * @param userRepository           repositorio de usuarios
     * @param usernameReservations     nombres reservados por el registro
     * @param emailVerificationService regla de las cuentas pendientes
     *                                 sustituibles (bloqueo y borrado)
     */
    public UserService(UserRepository userRepository, UsernameReservations usernameReservations,
            EmailVerificationService emailVerificationService) {

        this.userRepository = userRepository;
        this.usernameReservations = usernameReservations;
        this.emailVerificationService = emailVerificationService;
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
     * de mayúsculas ({@code bob} a {@code Bob}) sí es un cambio. Un nombre
     * <b>reservado</b> por un registro con el email de otra cuenta
     * ({@link UsernameReservations}) da el mismo 409 que uno en uso: si no,
     * registrarse con el email de alguien y después cambiarse el nombre al
     * del registro diría si ese email tenía cuenta. La cuenta del email de la
     * reserva sí puede usarlo.</li>
     * <li><b>Nombre de una cuenta pendiente que ya no lo conserva</b> (la creó
     * el registro hace 24 h o más, ver
     * {@link EmailVerificationService#releasesUsername}; no se miran los
     * enlaces pedidos después del alta): se borra esa cuenta y el nombre
     * queda libre, igual que en el registro. Si no, pasadas 24 h el nombre de
     * un registro con un email nuevo daría 409 (la pendiente sigue en la base
     * de datos, quizá con un enlace recién pedido) y el de uno con un email
     * que ya tenía cuenta, 200 (su reserva ya caducó): el mismo oráculo de
     * emails (incidencias QA-2 y MEDIA-1). Una cuenta heredada o creada hace
     * menos de 24 h nunca se borra: 409.</li>
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
     * {@link GenreService}): {@code findByUsername} da el 409 claro en el
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
     * <strong>Sin bloqueo, pero sin pisar la contraseña.</strong> En el caso
     * normal la fila se lee sin bloquearla, así que un cambio de contraseña
     * puede confirmarse entre esa lectura y el {@code UPDATE}. No pasa nada
     * porque {@link User} lleva {@code @DynamicUpdate}: el {@code UPDATE} solo
     * escribe {@code username} y {@code avatar}, nunca el hash leído al
     * principio. Aquí no hace falta bloquear porque nada se decide a partir de
     * la contraseña. Solo se bloquea cuando el nombre lo tiene una cuenta
     * pendiente (ver {@link #freeUsernameOrFail}).
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
     * @throws UserAlreadyExistsException si el nombre lo usa otra cuenta que no
     *                                    se puede sustituir, o está reservado
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
            if (usernameReservations.isReservedForOthers(username, user.getEmail())) {
                throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
            }
            // Puede devolver otra instancia (releída bajo bloqueo): se sigue con ella.
            user = freeUsernameOrFail(user, username);
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
     * Comprueba que el nombre está libre para la cuenta y, si lo tiene una
     * cuenta pendiente que <b>ya no lo conserva</b>, la borra para liberarlo
     * (misma regla que el registro: {@link EmailVerificationService#deleteIfUsernameReleased}).
     *
     * <ul>
     *   <li>Nadie lo tiene: se sigue sin bloquear nada (si otra petición se lo
     *       queda antes del {@code UPDATE}, salta el {@code UNIQUE}).</li>
     *   <li>Lo tiene una cuenta confirmada: 409 sin bloquear nada (nunca es
     *       sustituible).</li>
     *   <li>Lo tiene una cuenta pendiente: se bloquean las dos filas (la propia
     *       y la de la pendiente) <b>de menor a mayor id</b>, como hace el
     *       registro. Así un registro con el email de esta cuenta y ese nombre,
     *       que bloquea las mismas dos filas en el mismo orden, espera en lugar
     *       de cruzarse con esta petición en un interbloqueo. Bajo el bloqueo se
     *       decide con el estado releído: si la pendiente ya no existe o cambió
     *       de nombre, el nombre se da por libre (el {@code UNIQUE} sigue de
     *       respaldo); si lo conserva (creada hace menos de 24 h, heredada o
     *       recién verificada), 409.</li>
     * </ul>
     *
     * <p>
     * El borrado vacía la sesión de Hibernate, así que la cuenta devuelta puede
     * quedar desacoplada: {@code saveAndFlush} la vuelve a unir
     * ({@code merge}) y, con {@code @DynamicUpdate}, solo escribe lo cambiado.
     * La contraseña que copia {@code merge} es la releída bajo el bloqueo, que
     * nadie puede cambiar hasta el final de la transacción.
     * </p>
     *
     * @param user     cuenta que quiere el nombre (leída en esta transacción)
     * @param username nombre normalizado, distinto del actual
     * @return la cuenta con la que seguir: la recibida o, si hubo que
     *         bloquear, la releída bajo el bloqueo
     * @throws UserAlreadyExistsException si el nombre lo tiene otra cuenta que
     *                                    no se puede sustituir
     * @throws UserNotFoundException      si la propia cuenta desapareció al bloquearla
     */
    private User freeUsernameOrFail(User user, String username) {

        Optional<User> found = userRepository.findByUsername(username);
        if (found.isEmpty() || found.get().getId().equals(user.getId())) {
            return user;
        }
        User holder = found.get();
        if (holder.isEmailVerified()) {
            throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
        }

        User lockedUser;
        Optional<User> lockedHolder;
        if (user.getId() < holder.getId()) {
            lockedUser = lockOwn(user);
            lockedHolder = emailVerificationService.lockForUpdate(holder);
        } else {
            lockedHolder = emailVerificationService.lockForUpdate(holder);
            lockedUser = lockOwn(user);
        }
        Optional<User> stillHolder = lockedHolder.filter(locked -> username.equals(locked.getUsername()));
        if (stillHolder.isPresent() && !emailVerificationService.deleteIfUsernameReleased(stillHolder.get())) {
            throw new UserAlreadyExistsException(USERNAME_TAKEN_MESSAGE);
        }
        return lockedUser;
    }

    /** Bloquea la fila de la propia cuenta y la devuelve releída (404 si ya no existe). */
    private User lockOwn(User user) {
        return emailVerificationService.lockForUpdate(user)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));
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
