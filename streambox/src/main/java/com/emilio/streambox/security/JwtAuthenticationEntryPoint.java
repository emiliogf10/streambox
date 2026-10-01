package com.emilio.streambox.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.emilio.streambox.dto.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Punto de entrada personalizado para peticiones no autenticadas.
 *
 * <p>Spring Security invoca este componente cuando una petición llega a un
 * endpoint protegido sin un token JWT válido. En lugar de la respuesta HTML
 * por defecto, devuelve el mismo formato JSON {@code ErrorResponse} que el
 * resto de la API.</p>
 *
 * <p>Responde siempre con {@code 401 Unauthorized}.</p>
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorResponseWriter errorWriter;

    /**
     * @param errorWriter escritor de las respuestas de error JSON de seguridad
     */
    public JwtAuthenticationEntryPoint(SecurityErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /**
     * Escribe una respuesta {@code 401} con cuerpo JSON uniforme.
     *
     * @param request       petición que no superó la autenticación
     * @param response      respuesta HTTP que se enviará al cliente
     * @param authException excepción que describe el motivo del rechazo
     */
    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        errorWriter.write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_CREDENTIALS,
                "Autenticación requerida. Proporciona un token JWT válido.");
    }
}
