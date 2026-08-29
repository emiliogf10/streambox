package com.emilio.streambox.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio encargado de gestionar la lógica de autenticación de usuarios.
 *
 * <p>
 * Se encarga de localizar al usuario mediante su dirección de correo
 * electrónico y comprobar que la contraseña proporcionada coincide con
 * la contraseña cifrada almacenada en la base de datos.
 * </p>
 */
@Service
public class AuthenticationService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    /**
     * Crea una instancia del servicio de autenticación.
     *
     * @param userRepository  repositorio utilizado para buscar los usuarios
     * @param passwordEncoder componente utilizado para comprobar
     *                        las contraseñas cifradas
     */
    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Autentica a un usuario utilizando su correo electrónico y contraseña.
     *
     * <p>
     * Primero se busca el usuario mediante su dirección de correo
     * electrónico. Si existe, se comprueba la contraseña proporcionada
     * utilizando
     * {@link PasswordEncoder#matches(CharSequence, String)}.
     * </p>
     *
     * <p>
     * Por motivos de seguridad, se devuelve el mismo mensaje de error
     * tanto cuando el correo electrónico no existe como cuando la contraseña
     * es incorrecta. De esta forma no se revela si una dirección de correo
     * está registrada en la aplicación.
     * </p>
     *
     * @param email    dirección de correo electrónico del usuario
     * @param password contraseña proporcionada durante el inicio de sesión
     * @return usuario autenticado correctamente
     * @throws RuntimeException si el correo electrónico no existe o
     *                          la contraseña proporcionada es incorrecta
     */
    public User authenticate(String email, String password) {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException(
                        "Email o contraseña incorrectos"));

        if (!passwordEncoder.matches(password, user.getPassword())) {

            throw new RuntimeException(
                    "Email o contraseña incorrectos");
        }

        return user;
    }
}