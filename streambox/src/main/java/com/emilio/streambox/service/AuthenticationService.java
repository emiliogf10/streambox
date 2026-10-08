package com.emilio.streambox.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.exception.InvalidCredentialsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;

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

    /**
     * Mensaje único para email inexistente y contraseña incorrecta (evita la
     * enumeración de usuarios).
     */
    static final String INVALID_CREDENTIALS_MESSAGE = "Email o contraseña incorrectos";

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
     * @param clientIp dirección remota del cliente ({@code request.getRemoteAddr()},
     *                 nunca {@code X-Forwarded-For} a mano): decide si el
     *                 bloqueo de la cuenta le aplica como IP conocida o
     *                 desconocida (ver {@link LoginAttemptService})
     * @return token JWT; el controlador lo entrega en una cookie HttpOnly, nunca
     *         en el cuerpo
     * @throws InvalidCredentialsException si el email no existe o la contraseña es
     *         incorrecta; lleva los intentos que quedan antes del bloqueo
     * @throws AccountLockedException si la cuenta está bloqueada temporalmente
     *         por demasiados intentos fallidos, o si este fallo agota los intentos
     */
    public String login(String email, String password, String clientIp) {

        return jwtService.generateToken(authenticate(email, password, clientIp));
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
     * <p>
     * Los intentos restantes y el bloqueo dependen del email y de si la IP es
     * «conocida» de la cuenta, pero no de si la cuenta existe: <b>desde la
     * misma IP de origen</b>, la respuesta a un email registrado con contraseña
     * incorrecta y a uno inexistente es idéntica (estado, código, mensaje y
     * {@code remainingAttempts}). Bajo una IP compartida con el titular, quien
     * cruce dos orígenes puede notar la diferencia entre IP conocida y
     * desconocida; es una limitación conocida y acotada (ver
     * {@link LoginAttemptService}).
     * </p>
     *
     * @param email    dirección de correo electrónico del usuario
     * @param password contraseña proporcionada durante el inicio de sesión
     * @param clientIp dirección remota del cliente
     * @return usuario autenticado correctamente
     * @throws InvalidCredentialsException si el correo electrónico no existe o
     *                          la contraseña proporcionada es incorrecta
     * @throws AccountLockedException si la cuenta está bloqueada o este fallo
     *                          agota los intentos
     */
    private User authenticate(String email, String password, String clientIp) {

        // El registro guarda el email normalizado (trim + minúsculas); el login
        // debe normalizarlo igual o el usuario no podría entrar escribiéndolo
        // con otras mayúsculas.
        String normalizedEmail = email.trim().toLowerCase(java.util.Locale.ROOT);

        // Se busca antes de reservar el intento para que un fallo de la base
        // de datos (un 500) no se cuente como intento fallido y acabe
        // bloqueando cuentas durante una caída.
        User user = userRepository.findByEmail(normalizedEmail).orElse(null);

        // Se reserva el intento antes de comprobar la contraseña: una cuenta
        // bloqueada rechaza el login aunque la contraseña sea correcta, y la
        // reserva atómica impide que muchas peticiones simultáneas prueben más
        // contraseñas de las permitidas. Se cuenta tanto si el usuario existe
        // como si no, para que el bloqueo no revele qué emails están registrados.
        LoginAttempt attempt = loginAttemptService.reserveAttempt(normalizedEmail, clientIp);

        // Con usuario inexistente se compara contra un hash falso para que el
        // coste (BCrypt) y, por tanto, el tiempo de respuesta sean parecidos.
        // matches() no lanza excepciones con contraseñas de más de 72 bytes
        // (BCrypt solo compara los primeros 72), así que el intento reservado
        // siempre se cierra.
        String hashToCheck = user != null ? user.getPassword() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(password, hashToCheck);

        if (user == null || !passwordMatches) {

            // Lanza AccountLockedException (429) si este fallo agota los intentos.
            int remainingAttempts = loginAttemptService.recordFailure(attempt);

            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE, remainingAttempts);
        }

        // Solo aquí, con la contraseña verificada de una cuenta que existe, la
        // IP pasa a ser "conocida" de la cuenta (ver LoginAttemptService).
        loginAttemptService.recordSuccess(attempt);

        return user;
    }
}