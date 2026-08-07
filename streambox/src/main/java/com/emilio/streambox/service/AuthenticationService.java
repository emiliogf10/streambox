package com.emilio.streambox.service;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

//Clase que sirve para la lógica de autenticación, como el registro de usuarios y la verificación de 
// credenciales.

@Service
public class AuthenticationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationService(UserRepository userRepository,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User authenticate(String email, String password) {

    User user = userRepository.findByEmail(email)
            .orElseThrow(() ->
                    new RuntimeException("Email o contraseña incorrectos"));

    if (!passwordEncoder.matches(password, user.getPassword())) {
        throw new RuntimeException("Email o contraseña incorrectos");
    }

    return user;
}

}
