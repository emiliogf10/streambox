package com.emilio.streambox.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Manejador global de excepciones de la API REST de Streambox.
 *
 * <p>Centraliza el tratamiento de los errores producidos durante
 * el procesamiento de las peticiones HTTP y permite devolver
 * respuestas con códigos de estado HTTP adecuados.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Gestiona las excepciones producidas cuando no se encuentra
     * una película solicitada.
     *
     * @param exception excepción que contiene información sobre
     *                  la película no encontrada
     * @return respuesta HTTP con estado {@code 404 NOT_FOUND}
     */
    @ExceptionHandler(MovieNotFoundException.class)
    public ResponseEntity<String> handleMovieNotFound(
            MovieNotFoundException exception) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(exception.getMessage());
    }
}
