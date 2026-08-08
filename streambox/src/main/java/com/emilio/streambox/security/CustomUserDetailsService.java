package com.emilio.streambox.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio utilizado por Spring Security para cargar los datos de un usuario
 * desde la base de datos durante el proceso de autenticación.
 *
 * Implementa el estándar de Spring Security para recuperar la información
 * necesaria para autenticar a un usuario.
 *
 * En este caso, la identificación se realiza mediante el email del usuario,
 * por lo que el parámetro recibido por loadUserByUsername representa
 * la dirección de correo electrónico.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Crea una instancia del servicio de detalles de usuario.
     *
     * @param userRepository repositorio utilizado para buscar los usuarios
     *                       en la base de datos
     */
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Carga los datos de un usuario mediante su correo electrónico.
     *
     * Convierte la entidad User en una instancia de UserDetails,
     * que es la representación de usuario utilizada internamente
     * por Spring Security.
     *
     * @param email correo electrónico utilizado para identificar al usuario
     * @return información del usuario en el formato requerido por Spring Security
     * @throws UsernameNotFoundException si no existe ningún usuario asociado
     *                                   al correo electrónico
     */
    @Override
    public UserDetails loadUserByUsername(String email)
            throws UsernameNotFoundException {

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new UsernameNotFoundException("Usuario no encontrado"));

        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail())
                .password(user.getPassword())
                .roles(user.getRole().name())
                .build();
    }
}

