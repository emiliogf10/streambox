package com.emilio.streambox.security;

import java.io.IOException;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Manejador de accesos denegados para usuarios autenticados sin permisos suficientes.
 *
 * <p>Spring Security invoca este componente cuando un usuario está autenticado
 * pero intenta acceder a un recurso para el que no tiene el rol necesario.
 * Devuelve el mismo formato JSON {@link ErrorResponse} que el resto de la API
 * en lugar de la respuesta HTML por defecto.</p>
 *
 * <p>Responde siempre con {@code 403 Forbidden}.</p>
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    /**
     * Crea la instancia registrando el módulo JSR-310 para serializar
     * {@link LocalDateTime} en formato ISO-8601.
     */
    public JwtAccessDeniedHandler() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Escribe una respuesta {@code 403} con cuerpo JSON uniforme.
     *
     * @param request               petición que fue rechazada por falta de permisos
     * @param response              respuesta HTTP que se enviará al cliente
     * @param accessDeniedException excepción que describe el motivo del rechazo
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {

        ErrorResponse error = new ErrorResponse(
                LocalDateTime.now(),
                HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(),
                ErrorCode.INVALID_CREDENTIALS,
                "No tienes permisos suficientes para acceder a este recurso.",
                request.getRequestURI());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), error);
    }
}
