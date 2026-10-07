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
     * Si el token no es válido o ha expirado, la excepción se registra
     * como aviso ({@code WARN}) y la petición continúa sin autenticación.
     * Spring Security rechazará la petición si el endpoint lo requiere.
     * </p>
     *
     * @param request     petición HTTP recibida
     * @param response    respuesta HTTP
     * @param filterChain cadena de filtros que debe continuar procesando
     *                    la petición
     * @throws ServletException si se produce un error relacionado con
     *                          el procesamiento del servlet
     * @throws IOException      si se produce un error de entrada o salida
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

        try {

            String email = jwtService.extractEmail(token);

            User user = userRepository.findByEmail(email)
                    .orElse(null);

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

        } catch (Exception e) {

            // Token inválido o expirado: se registra como aviso y la petición
            // continúa sin autenticación. Spring Security rechazará el acceso
            // a los endpoints protegidos a través del AuthenticationEntryPoint.
            LOGGER.warn("Token JWT inválido o expirado en {}: {}",
                    request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
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
