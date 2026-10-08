package com.emilio.streambox.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.RateLimitingFilter;

import io.jsonwebtoken.JwtException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filtro de Spring Security encargado de autenticar las peticiones
 * que contienen un token JWT válido.
 *
 * <p>
 * El filtro comprueba la cabecera {@code Authorization} de cada petición.
 * Cuando contiene un token con el formato {@code Bearer <token>}, el token
 * se valida mediante {@link JwtService} y se obtiene el correo electrónico
 * del usuario.
 * </p>
 *
 * <p>
 * Posteriormente se busca el usuario en la base de datos y, si existe,
 * se construye un {@link AuthenticatedUser} ligero que se almacena como
 * principal en el {@link SecurityContextHolder}. Al no contener la
 * contraseña ni colecciones JPA con carga diferida, se evita tanto la
 * exposición de datos sensibles como posibles
 * {@code LazyInitializationException} fuera de sesión Hibernate.
 * </p>
 *
 * <p>
 * Este filtro hereda de {@link OncePerRequestFilter} para garantizar que
 * su lógica se ejecute como máximo una vez por cada petición HTTP.
 * </p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** Cabecera exigida en peticiones no seguras autenticadas por cookie. */
    public static final String CSRF_HEADER = "X-Requested-With";

    /** Valor exigido en {@link #CSRF_HEADER}. */
    public static final String CSRF_HEADER_VALUE = "StreamBox";

    /** Ruta del cierre de sesión (pública e idempotente). */
    public static final String LOGOUT_PATH = "/api/auth/logout";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SecurityErrorResponseWriter errorWriter;
    private final RequestMatcher loginRequest;
    private final RequestMatcher registerRequest;
    private final RequestMatcher logoutRequest;

    /**
     * Crea una instancia del filtro JWT.
     *
     * @param jwtService     servicio utilizado para validar los tokens JWT
     * @param userRepository repositorio utilizado para buscar al usuario
     *                       asociado al token
     * @param errorWriter    escritor del 403 CSRF_REJECTED
     * @param matchers       constructor de matchers de rutas (el mismo que usan
     *                       las reglas de autorización)
     */
    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository,
            SecurityErrorResponseWriter errorWriter,
            PathPatternRequestMatcher.Builder matchers) {

        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.errorWriter = errorWriter;
        this.loginRequest = matchers.matcher(HttpMethod.POST, RateLimitingFilter.LOGIN_PATH);
        this.registerRequest = matchers.matcher(HttpMethod.POST, RateLimitingFilter.REGISTER_PATH);
        this.logoutRequest = matchers.matcher(HttpMethod.POST, LOGOUT_PATH);
    }

    /**
     * Procesa una petición HTTP para determinar si contiene un token JWT
     * válido y establecer la autenticación correspondiente.
     *
     * <p>
     * Si la petición no contiene una cabecera {@code Authorization}
     * con el esquema {@code Bearer}, se continúa directamente con la
     * siguiente etapa de la cadena de filtros.
     * </p>
     *
     * <p>
     * Cuando existe un token, se extrae el correo electrónico mediante
     * {@link JwtService}, se busca el usuario correspondiente y se crea
     * un {@link AuthenticatedUser} que actúa como principal. Esto evita
     * almacenar la entidad JPA completa en el contexto de seguridad.
     * </p>
     *
     * <p>
     * Si el token no es válido o ha expirado, o el usuario ya no existe, la
     * petición continúa sin autenticación y Spring Security la rechazará
     * (401) si el endpoint lo requiere (ver {@link #readEmailOrNull}). En
     * cambio, un fallo al consultar el usuario (base de datos caída) <b>no</b>
     * se captura: sube y el cliente recibe un 500 {@code INTERNAL_ERROR}, no un
     * 401 que le cerraría la sesión.
     * </p>
     *
     * @param request     petición HTTP recibida
     * @param response    respuesta HTTP
     * @param filterChain cadena de filtros que debe continuar procesando
     *                    la petición
     * @throws ServletException si se produce un error relacionado con
     *                          el procesamiento del servlet
     * @throws IOException      si se produce un error de entrada o salida
     * @throws org.springframework.dao.DataAccessException si falla la consulta
     *                          del usuario (se deja propagar a propósito)
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        boolean bearer = authHeader != null && authHeader.startsWith("Bearer ");
        String token;
        boolean fromCookie = false;

        if (bearer) {
            // Precedencia: si hay Authorization: Bearer, manda y no se mira la
            // cookie (aunque el Bearer sea inválido: no se "rescata" con la cookie).
            token = authHeader.substring(7);
        } else {
            if (isCookieExempt(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            token = readCookie(request);
            fromCookie = true;
            if (token == null) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        String email = readEmailOrNull(token, request);
        if (email == null) {
            // Token no válido: la petición sigue como anónima y Spring Security
            // responderá 401 si la ruta lo exige (o la atenderá si es pública).
            filterChain.doFilter(request, response);
            return;
        }

        // Fuera de cualquier try a propósito: si la base de datos falla
        // (DataAccessException) o hay cualquier otro error inesperado, la
        // excepción sube hasta Tomcat, que la registra a ERROR y reenvía la
        // petición a /error (ApiErrorController): el cliente recibe un 500
        // INTERNAL_ERROR. Tratarlo como "sin autenticar" daría un 401 falso y
        // el frontend cerraría la sesión de un usuario con un token válido.
        User user = userRepository.findByEmail(email).orElse(null);

        // Token válido de un usuario que ya no existe (cuenta borrada): no es
        // un error del servidor, la petición sigue como anónima (401 si la ruta
        // es protegida), igual que con un token no válido.
        if (user != null) {

            // Defensa CSRF: el navegador adjunta la cookie solo, así que una
            // petición no segura autenticada por cookie debe traer una cabecera
            // personalizada que un formulario de otro sitio no puede añadir.
            if (fromCookie && !isSafeMethod(request.getMethod())
                    && !CSRF_HEADER_VALUE.equals(request.getHeader(CSRF_HEADER))) {
                errorWriter.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.CSRF_REJECTED,
                        "Petición rechazada: falta la cabecera de protección "
                                + CSRF_HEADER + ": " + CSRF_HEADER_VALUE);
                return;
            }

            AuthenticatedUser principal = AuthenticatedUser.from(user);

            var authorities = List.of(
                    new SimpleGrantedAuthority(
                            "ROLE_" + principal.role().name()));

            var authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    authorities);

            SecurityContextHolder
                    .getContext()
                    .setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Valida el token y devuelve el email de su {@code subject}, o
     * {@code null} si el token no es válido.
     *
     * <p>
     * Solo se capturan las excepciones que significan «token no válido»:
     * </p>
     * <ul>
     * <li>{@link JwtException} y sus subclases, que es lo que lanza jjwt para
     * un token mal formado ({@code MalformedJwtException}), con firma incorrecta
     * o algoritmo/clave no admitidos ({@code SecurityException},
     * {@code SignatureException}, {@code WeakKeyException}...), caducado
     * ({@code ExpiredJwtException}), aún no válido
     * ({@code PrematureJwtException}), de otro emisor o sin él
     * ({@code IncorrectClaimException}, {@code MissingClaimException}), sin
     * firma o cifrado ({@code UnsupportedJwtException}) o con Base64/JSON que
     * no se puede leer (las excepciones de {@code io.jsonwebtoken.io}, que
     * también heredan de {@code JwtException}).</li>
     * <li>{@link IllegalArgumentException}: jjwt la lanza si el token está
     * vacío (p. ej. {@code Authorization: Bearer } sin nada detrás).</li>
     * </ul>
     *
     * <p>
     * Nada más: antes se capturaba {@code Exception} alrededor de la
     * validación <em>y</em> de la consulta del usuario, así que una caída de la
     * base de datos se convertía en «sin autenticar» → 401 → el frontend
     * cerraba la sesión. Cualquier otra excepción es un fallo real y debe
     * acabar en un 500.
     * </p>
     *
     * <p>
     * <b>Log.</b> Se registra la <em>clase</em> de la excepción, nunca su
     * mensaje: los mensajes de jjwt pueden reproducir partes del token (la
     * cabecera, el algoritmo, el emisor...), que controla el cliente. Y va a
     * {@code DEBUG}, no a {@code WARN}: un token caducado es algo normal (el
     * usuario vuelve al día siguiente) y cualquiera puede enviar tokens
     * basura sin límite (estas rutas no tienen rate limiting), así que a
     * {@code WARN} llenaría el log de producción sin aportar nada que el 401
     * no diga ya. Para investigar, se activa
     * {@code logging.level.com.emilio.streambox.security.JwtAuthenticationFilter=DEBUG}.
     * </p>
     *
     * @param token   token recibido (de la cabecera o de la cookie)
     * @param request petición, solo para registrar la ruta
     * @return email del {@code subject}, o {@code null} si el token no es
     *         válido o no trae {@code subject}
     */
    private String readEmailOrNull(String token, HttpServletRequest request) {
        try {
            String email = jwtService.extractEmail(token);
            // Los tokens propios siempre llevan subject; uno firmado sin él no
            // identifica a nadie y se trata como no válido.
            return email == null || email.isBlank() ? null : email;
        } catch (JwtException | IllegalArgumentException e) {
            LOGGER.debug("Token JWT no válido en {} ({}): la petición sigue sin autenticar",
                    request.getRequestURI(), e.getClass().getSimpleName());
            return null;
        }
    }

    /** Valor de la cookie de sesión, o {@code null} si no viene o está vacía. */
    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieService.COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return value == null || value.isBlank() ? null : value;
            }
        }
        return null;
    }

    /**
     * Login, registro y logout son públicos y no necesitan sesión: ignorar la
     * cookie ahí evita que una caducada o manipulada, o la exigencia CSRF, los
     * bloquee. Riesgo residual documentado: un login CSRF (un sitio ajeno
     * inicia sesión del usuario con credenciales del atacante); lo mitiga
     * SameSite=Strict.
     */
    private boolean isCookieExempt(HttpServletRequest request) {
        return loginRequest.matches(request) || registerRequest.matches(request)
                || logoutRequest.matches(request);
    }

    private static boolean isSafeMethod(String method) {
        return "GET".equals(method) || "HEAD".equals(method)
                || "OPTIONS".equals(method) || "TRACE".equals(method);
    }
}
