package com.emilio.streambox.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.emilio.streambox.dto.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Manejador de accesos denegados para usuarios autenticados sin permisos suficientes.
 *
 * <p>Spring Security invoca este componente cuando un usuario está autenticado
 * pero intenta acceder a un recurso para el que no tiene el rol necesario.
 * Devuelve el mismo formato JSON {@code ErrorResponse} que el resto de la API
 * en lugar de la respuesta HTML por defecto.</p>
 *
 * <p>Responde siempre con {@code 403 Forbidden}.</p>
 */
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter errorWriter;

    /**
     * @param errorWriter escritor de las respuestas de error JSON de seguridad
     */
    public JwtAccessDeniedHandler(SecurityErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
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

        errorWriter.write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                ErrorCode.ACCESS_DENIED,
                "No tienes permisos suficientes para acceder a este recurso.");
    }
}
