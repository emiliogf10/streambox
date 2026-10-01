package com.emilio.streambox.service;

import java.util.List;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
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
     * El email se normaliza (sin espacios y en minúsculas) y la contraseña se
     * guarda cifrada con BCrypt. El rol nunca viene del cliente: los
     * administradores solo se crean con {@code AdminAccountInitializer}.
     * </p>
     *
     * @param request datos de registro (ya validados)
     * @return la cuenta creada
     * @throws UserAlreadyExistsException si el nombre de usuario o el email ya están en uso
     */
    @Transactional
    public UserResponse registerUser(CreateUserRequest request) {

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        String username = request.getUsername().trim();

        if (userRepository.existsByUsername(username)) {
            throw new UserAlreadyExistsException("El nombre de usuario ya está en uso");
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
}
