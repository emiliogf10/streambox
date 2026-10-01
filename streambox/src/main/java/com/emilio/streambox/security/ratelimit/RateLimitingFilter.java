package com.emilio.streambox.security.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.springframework.http.HttpStatus;
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
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/users";

    private final RateLimitProperties.Rule loginRule;
    private final RateLimitProperties.Rule registerRule;
    private final SlidingWindowCounter loginCounter;
    private final SlidingWindowCounter registerCounter;
    private final SecurityErrorResponseWriter errorWriter;

    /**
     * @param properties  límites configurados
     * @param clock       reloj de la aplicación
     * @param errorWriter escritor de respuestas de error de seguridad
     */
    public RateLimitingFilter(
            RateLimitProperties properties,
            Clock clock,
            SecurityErrorResponseWriter errorWriter) {

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

        if ("POST".equals(request.getMethod())) {

            String path = request.getRequestURI();
            String ip = request.getRemoteAddr();

            if (LOGIN_PATH.equals(path)) {
                if (!loginCounter.tryAcquire(ip, loginRule.maxRequests())) {
                    reject(request, response, loginCounter.retryAfter(ip));
                    return;
                }
            } else if (REGISTER_PATH.equals(path)) {
                if (!registerCounter.tryAcquire(ip, registerRule.maxRequests())) {
                    reject(request, response, registerCounter.retryAfter(ip));
                    return;
                }
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
