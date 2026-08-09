package com.emilio.streambox.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con los usuarios de Streambox.
 *
 * <p>Se encarga de coordinar las operaciones entre los controladores,
 * el repositorio de usuarios y los componentes de seguridad necesarios
 * para proteger las contraseñas.</p>
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Crea una instancia del servicio de usuarios.
     *
     * @param userRepository repositorio utilizado para acceder a los usuarios
     * @param passwordEncoder componente utilizado para cifrar las contraseñas
     */
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Obtiene todos los usuarios registrados en Streambox.
     *
     * @return lista con todos los usuarios almacenados en la base de datos
     */
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Guarda un nuevo usuario en la base de datos.
     * 
     * <p>El usuario creado recibe automáticamente el rol {@link Role#USER} * y la fecha de creación actual. La contraseña se almacena cifrada * mediante {@link PasswordEncoder}.</p>
     * 
     * @param user usuario a guardar en la base de datos
     * @return usuario guardado con su ID generado y la contraseña cifrada
     * @throws IllegalArgumentException si el nombre de usuario o el correo electrónico ya están en uso
     */
    public User saveUser(User user) {

        if(userRepository.existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("El nombre de usuario ya está en uso");

        }

        if (userRepository.existsByEmail(user.getEmail())) {
            throw new IllegalArgumentException("El correo electrónico ya está en uso");
        }

        user.setRole(Role.USER);
        user.setCreatedAt(LocalDateTime.now());

        user.setPassword(passwordEncoder.encode(user.getPassword()));

        return userRepository.save(user);
    }
}