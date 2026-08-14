package com.emilio.streambox.exception;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Manejador global de excepciones de la API REST de Streambox.
 *
 * <p>
 * Centraliza el tratamiento de los errores producidos durante
 * el procesamiento de las peticiones HTTP y permite devolver
 * respuestas con códigos de estado HTTP adecuados.
 * </p>
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

    /**
     * Gestiona las excepciones producidas cuando no se encuentra
     * un género solicitado.
     *
     * @param exception excepción que contiene información sobre
     *                  el género no encontrado
     * @return respuesta HTTP con estado {@code 404 NOT_FOUND}
     */
    @ExceptionHandler(GenreNotFoundException.class)
    public ResponseEntity<String> handleGenreNotFound(
            GenreNotFoundException exception) {

        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(exception.getMessage());
    }

    /**
     * Gestiona los errores producidos cuando los datos recibidos
     * en una petición no cumplen las validaciones definidas mediante
     * las anotaciones de Jakarta Validation.
     *
     * @param exception excepción que contiene los errores de validación
     * @return respuesta HTTP con estado {@code 400 BAD_REQUEST} y
     *         los errores asociados a cada campo
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(
            MethodArgumentNotValidException exception) {

        Map<String, String> errors = new HashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.put(
                        error.getField(),
                        error.getDefaultMessage()));

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errors);
    }
}
