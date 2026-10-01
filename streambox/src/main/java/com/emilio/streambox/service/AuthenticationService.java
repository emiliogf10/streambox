package com.emilio.streambox.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.emilio.streambox.dto.LoginResponse;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidCredentialsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;

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

    private final LoginAttemptService loginAttemptService;

    private final JwtService jwtService;

    /**
     * Hash válido que se comprueba cuando el email no existe, para que el
     * tiempo de respuesta no delate si la cuenta está registrada.
     */
    private final String dummyPasswordHash;

    /**
     * Crea una instancia del servicio de autenticación.
     *
     * @param userRepository  repositorio utilizado para buscar los usuarios
     * @param passwordEncoder componente utilizado para comprobar
     *                        las contraseñas cifradas
     * @param loginAttemptService servicio que bloquea temporalmente las
     *                            cuentas con demasiados intentos fallidos
     * @param jwtService          servicio que genera el token del usuario
     *                            autenticado
     */
    public AuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("contraseña-que-nadie-usa");
    }

    /**
     * Inicia sesión: comprueba las credenciales y genera el token JWT.
     *
     * @param email    correo electrónico del usuario
     * @param password contraseña en texto plano
     * @return respuesta con el token JWT
     * @throws InvalidCredentialsException si el email no existe o la contraseña es incorrecta
     * @throws com.emilio.streambox.exception.TooManyRequestsException si la cuenta está bloqueada
     *         temporalmente por demasiados intentos fallidos
     */
    public LoginResponse login(String email, String password) {

        return new LoginResponse(
                jwtService.generateToken(authenticate(email, password)));
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
    private User authenticate(String email, String password) {

        // El registro guarda el email normalizado (trim + minúsculas); el login
        // debe normalizarlo igual o el usuario no podría entrar escribiéndolo
        // con otras mayúsculas.
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);

        // Se comprueba antes que nada: una cuenta bloqueada rechaza el login
        // aunque la contraseña sea correcta.
        loginAttemptService.checkNotLocked(normalizedEmail);

        // Se cuentan los fallos tanto si el usuario existe como si no, para que
        // el bloqueo no revele qué emails están registrados.
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);

        // Con usuario inexistente se compara contra un hash falso para que el
        // coste (BCrypt) y, por tanto, el tiempo de respuesta sean parecidos.
        String hashToCheck = user != null ? user.getPassword() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(password, hashToCheck);

        if (user == null || !passwordMatches) {

            loginAttemptService.recordFailure(normalizedEmail);

            throw new InvalidCredentialsException(
                    "Email o contraseña incorrectos");
        }

        loginAttemptService.recordSuccess(normalizedEmail);

        return user;
    }
}