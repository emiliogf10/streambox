package com.emilio.streambox.exception;

import java.time.LocalDateTime;
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
 * Centraliza el tratamiento de las excepciones producidas durante
 * el procesamiento de las peticiones HTTP y permite devolver
 * respuestas con códigos de estado y formatos consistentes.
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
        public ResponseEntity<ErrorResponse> handleMovieNotFound(
                        MovieNotFoundException exception) {

                return buildErrorResponse(
                                HttpStatus.NOT_FOUND,
                                exception.getMessage());
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
        public ResponseEntity<ErrorResponse> handleGenreNotFound(
                        GenreNotFoundException exception) {

                return buildErrorResponse(
                                HttpStatus.NOT_FOUND,
                                exception.getMessage());
        }

        /**
         * Gestiona los intentos de crear un usuario utilizando un nombre
         * de usuario o correo electrónico que ya existe.
         *
         * @param exception excepción que contiene información sobre
         *                  el conflicto detectado
         * @return respuesta HTTP con estado {@code 409 CONFLICT}
         */
        @ExceptionHandler(UserAlreadyExistsException.class)
        public ResponseEntity<ErrorResponse> handleUserAlreadyExists(
                        UserAlreadyExistsException exception) {

                return buildErrorResponse(
                                HttpStatus.CONFLICT,
                                exception.getMessage());
        }

        /**
         * Gestiona los errores producidos cuando las credenciales
         * proporcionadas durante el inicio de sesión no son válidas.
         *
         * @param exception excepción que contiene información sobre
         *                  las credenciales incorrectas
         * @return respuesta HTTP con estado {@code 401 UNAUTHORIZED}
         */
        @ExceptionHandler(InvalidCredentialsException.class)
        public ResponseEntity<ErrorResponse> handleInvalidCredentials(
                        InvalidCredentialsException exception) {

                return buildErrorResponse(
                                HttpStatus.UNAUTHORIZED,
                                exception.getMessage());
        }

        /**
         * Gestiona los errores producidos cuando los datos recibidos
         * en una petición no cumplen las validaciones definidas mediante
         * Jakarta Validation.
         *
         * @param exception excepción que contiene los errores de validación
         * @return respuesta HTTP con estado {@code 400 BAD_REQUEST} y
         *         los errores asociados a cada campo
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String, Object>> handleValidationErrors(
                        MethodArgumentNotValidException exception) {

                Map<String, String> errors = new HashMap<>();

                exception.getBindingResult()
                                .getFieldErrors()
                                .forEach(error -> errors.put(
                                                error.getField(),
                                                error.getDefaultMessage()));

                Map<String, Object> response = new HashMap<>();

                response.put("status", HttpStatus.BAD_REQUEST.value());
                response.put("message", "Error de validación");
                response.put("errors", errors);
                response.put("timestamp", LocalDateTime.now());

                return ResponseEntity
                                .status(HttpStatus.BAD_REQUEST)
                                .body(response);
        }

        /**
         * Construye una respuesta de error utilizando el formato estándar
         * de la API.
         *
         * @param status  código de estado HTTP
         * @param message mensaje descriptivo del error
         * @return respuesta HTTP con información estandarizada del error
         */
        private ResponseEntity<ErrorResponse> buildErrorResponse(
                        HttpStatus status,
                        String message) {

                ErrorResponse errorResponse = new ErrorResponse(
                                status.value(),
                                message,
                                LocalDateTime.now());

                return ResponseEntity
                                .status(status)
                                .body(errorResponse);
        }
}