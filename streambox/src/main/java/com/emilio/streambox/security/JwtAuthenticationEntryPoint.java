package com.emilio.streambox.security;

import java.io.IOException;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Punto de entrada personalizado para peticiones no autenticadas.
 *
 * <p>Spring Security invoca este componente cuando una petición llega a un
 * endpoint protegido sin un token JWT válido. En lugar de la respuesta HTML
 * por defecto, devuelve el mismo formato JSON {@link ErrorResponse} que el
 * resto de la API.</p>
 *
 * <p>Responde siempre con {@code 401 Unauthorized}.</p>
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    /**
     * Crea la instancia registrando el módulo JSR-310 para serializar
     * {@link LocalDateTime} en formato ISO-8601.
     */
    public JwtAuthenticationEntryPoint() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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

        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                ErrorCode.INVALID_CREDENTIALS,
                "Autenticación requerida. Proporciona un token JWT válido.",
                request.getRequestURI());

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), error);
    }
}
