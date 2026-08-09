package com.emilio.streambox.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Manejador global de excepciones de la API REST de Streambox.
 *
 * <p>Centraliza el tratamiento de las excepciones producidas durante
 * el procesamiento de las peticiones HTTP y las transforma en
 * respuestas HTTP apropiadas para el cliente.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Gestiona las excepciones producidas cuando se intenta realizar
     * una operación que no es válida según las reglas de negocio.
     *
     * <p>Actualmente se utiliza, entre otros casos, cuando se intenta
     * registrar un usuario cuyo nombre de usuario o correo electrónico
     * ya está registrado.</p>
     *
     * @param exception excepción producida durante el procesamiento
     *                  de la petición
     * @return respuesta HTTP con estado {@code 409 Conflict} y un
     *         mensaje descriptivo del problema
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(
            IllegalArgumentException exception) {

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of("message", exception.getMessage()));
    }
}
