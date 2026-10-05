package com.emilio.streambox.security.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.security.SecurityErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limita por IP cuántas veces se puede llamar a los endpoints públicos más
 * sensibles: el inicio de sesión ({@code POST /api/auth/login}) y el registro
 * ({@code POST /api/users}).
 *
 * <p>
 * Al superar el límite responde {@code 429 Too Many Requests} con la cabecera
 * {@code Retry-After}. Las peticiones rechazadas no se contabilizan, de modo
 * que un cliente legítimo que espera lo indicado recupera el acceso.
 * </p>
 *
 * <p>
 * La IP se toma de {@link HttpServletRequest#getRemoteAddr()}. Detrás de un
 * proxy inverso hay que activar {@code server.forward-headers-strategy=native}
 * para que sea la IP real del cliente; la cabecera {@code X-Forwarded-For} no
 * se lee a mano porque un cliente podría falsificarla para evadir el límite.
 * </p>
 *
 * <h2>Cómo se reconoce la ruta</h2>
 * <p>
 * Con los mismos {@link PathPatternRequestMatcher} que usan las reglas de
 * autorización de {@code SecurityConfig} (creados con el mismo
 * {@link PathPatternRequestMatcher.Builder}), no comparando textos. Antes se
 * comparaba {@link HttpServletRequest#getRequestURI()} con
 * {@code "/api/auth/login"} mediante {@code equals}, pero ese método devuelve
 * la ruta <b>tal como llega, sin decodificar</b>, mientras que Spring MVC y
 * Spring Security decodifican cada segmento: {@code POST /api/auth/%6cogin}
 * (la {@code l} codificada) llegaba al login sin pasar por el límite, y
 * {@code POST /api/%75sers} creaba cuentas sin límite. El cortafuegos de
 * Spring Security ({@code StrictHttpFirewall}) no lo impide: rechaza
 * {@code %2F}, {@code %2E} o el punto y coma, pero no una letra codificada.
 * </p>
 *
 * <p>
 * El matcher compara cada segmento ya decodificado y sin parámetros de matriz
 * ({@code ;jsessionid=...}), así que cualquier forma de escribir la ruta que
 * Spring MVC atienda como login o registro cuenta aquí igual. Lo que no
 * reconoce (otras mayúsculas como {@code /api/Users}, o la barra final de
 * {@code /api/users/}) tampoco lo enruta Spring MVC ni lo hace público la
 * regla de autorización: nunca llega al login ni al registro. La regla general
 * es "se limita exactamente lo que es público".
 * </p>
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    /**
     * Ruta del inicio de sesión. {@code SecurityConfig} la usa también para
     * hacerla pública, para que lo público y lo limitado no se separen.
     */
    public static final String LOGIN_PATH = "/api/auth/login";

    /** Ruta del registro (pública y limitada, como {@link #LOGIN_PATH}). */
    public static final String REGISTER_PATH = "/api/users";

    private final RequestMatcher loginRequest;
    private final RequestMatcher registerRequest;
    private final RateLimitProperties.Rule loginRule;
    private final RateLimitProperties.Rule registerRule;
    private final SlidingWindowCounter loginCounter;
    private final SlidingWindowCounter registerCounter;
    private final SecurityErrorResponseWriter errorWriter;

    /**
     * @param properties  límites configurados
     * @param clock       reloj de la aplicación
     * @param errorWriter escritor de respuestas de error de seguridad
     * @param matchers    constructor de matchers de rutas; debe ser el mismo
     *                    que usan las reglas de autorización (Spring Boot
     *                    registra uno que tiene en cuenta
     *                    {@code spring.mvc.servlet.path}), para que el filtro
     *                    reconozca las rutas exactamente igual que ellas
     */
    public RateLimitingFilter(
            RateLimitProperties properties,
            Clock clock,
            SecurityErrorResponseWriter errorWriter,
            PathPatternRequestMatcher.Builder matchers) {

        this.loginRequest = matchers.matcher(HttpMethod.POST, LOGIN_PATH);
        this.registerRequest = matchers.matcher(HttpMethod.POST, REGISTER_PATH);
        this.loginRule = properties.login();
        this.registerRule = properties.register();
        this.loginCounter = new SlidingWindowCounter(loginRule.window(), clock);
        this.registerCounter = new SlidingWindowCounter(registerRule.window(), clock);
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (loginRequest.matches(request)) {
            String ip = request.getRemoteAddr();
            if (!loginCounter.tryAcquire(ip, loginRule.maxRequests())) {
                reject(request, response, loginCounter.retryAfter(ip));
                return;
            }
        } else if (registerRequest.matches(request)) {
            String ip = request.getRemoteAddr();
            if (!registerCounter.tryAcquire(ip, registerRule.maxRequests())) {
                reject(request, response, registerCounter.retryAfter(ip));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            Duration retryAfter) throws IOException {

        long seconds = Math.max((retryAfter.toMillis() + 999) / 1000, 1);

        response.setHeader("Retry-After", String.valueOf(seconds));
        errorWriter.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMIT_EXCEEDED,
                "Demasiadas peticiones. Inténtalo de nuevo en " + seconds + " segundos.");
    }
}
