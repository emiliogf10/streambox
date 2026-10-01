package com.emilio.streambox.security;

import java.io.IOException;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Escribe respuestas de error JSON con el formato {@link ErrorResponse} desde
 * los componentes de seguridad (filtros y handlers), que actúan antes de que
 * Spring MVC y {@code GlobalExceptionHandler} entren en juego.
 */
@Component
public class SecurityErrorResponseWriter {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Escribe el error en la respuesta HTTP.
     *
     * @param request  petición rechazada
     * @param response respuesta en la que se escribe el error
     * @param status   estado HTTP
     * @param code     código funcional del error
     * @param message  mensaje para el cliente
     * @throws IOException si falla la escritura
     */
    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            ErrorCode code,
            String message) throws IOException {

        ErrorResponse error = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                code,
                message,
                request.getRequestURI());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), error);
    }
}
