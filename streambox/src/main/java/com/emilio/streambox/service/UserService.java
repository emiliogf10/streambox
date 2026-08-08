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
     * <p>Antes de persistir el usuario se realizan varias operaciones
     * gestionadas por el servidor:</p>
     *
     * <ul>
     *     <li>Se asigna el rol {@link Role#USER} por defecto.</li>
     *     <li>Se establece la fecha y hora de creación.</li>
     *     <li>Se cifra la contraseña mediante {@link PasswordEncoder}.</li>
     * </ul>
     *
     * <p>El rol y la fecha de creación no proceden directamente del
     * cliente, ya que son valores que debe controlar la aplicación.</p>
     *
     * @param user usuario que se desea guardar
     * @return usuario guardado, incluyendo los valores generados por
     *         la base de datos
     */
    public User saveUser(User user) {

        user.setRole(Role.USER);
        user.setCreatedAt(LocalDateTime.now());

        user.setPassword(passwordEncoder.encode(user.getPassword()));

        return userRepository.save(user);
    }
}