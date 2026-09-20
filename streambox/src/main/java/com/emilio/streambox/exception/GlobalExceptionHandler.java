package com.emilio.streambox.exception;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Manejador global de excepciones de la API REST de Streambox.
 *
 * <p>
 * Centraliza el tratamiento de los errores producidos durante
 * el procesamiento de las peticiones HTTP y permite devolver
 * respuestas con códigos de estado y formatos consistentes.
 * </p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

        private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
        private static final String VALIDATION_MESSAGE = "Los datos proporcionados no son válidos";
        private static final String INTEGRITY_MESSAGE =
                        "No se puede completar la operación porque entra en conflicto con datos existentes";
        private static final String INTERNAL_ERROR_MESSAGE =
                        "Se ha producido un error interno. Inténtalo de nuevo más tarde";

        /**
         * Gestiona las excepciones producidas cuando no se encuentra
         * una película solicitada.
         *
         * @param exception excepción que contiene información sobre
         *                  la película no encontrada
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 404 NOT_FOUND}
         */
        @ExceptionHandler(MovieNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleMovieNotFound(
                        MovieNotFoundException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                                exception.getMessage(), request);
        }

        /**
         * Gestiona las excepciones producidas cuando no se encuentra
         * un género solicitado.
         *
         * @param exception excepción que contiene información sobre
         *                  el género no encontrado
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 404 NOT_FOUND}
         */
        @ExceptionHandler(GenreNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleGenreNotFound(
                        GenreNotFoundException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                                exception.getMessage(), request);
        }

        /**
         * Gestiona las excepciones producidas cuando no se encuentra un
         * usuario solicitado.
         *
         * @param exception excepción que contiene información sobre el usuario
         *                  no encontrado
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 404 NOT_FOUND}
         */
        @ExceptionHandler(UserNotFoundException.class)
        public ResponseEntity<ErrorResponse> handleUserNotFound(
                        UserNotFoundException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                                exception.getMessage(), request);
        }

        /**
         * Gestiona el intento de registrar un nombre de usuario o correo ya existentes.
         *
         * @param exception excepción de duplicidad de usuario
         * @param request petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 409 CONFLICT}
         */
        @ExceptionHandler(UserAlreadyExistsException.class)
        public ResponseEntity<ErrorResponse> handleUserAlreadyExists(
                        UserAlreadyExistsException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.USER_ALREADY_EXISTS,
                                exception.getMessage(), request);
        }


        /**
         * Gestiona los intentos de añadir una película ya existente a la lista
         * de favoritos del usuario.
         *
         * @param exception excepción que contiene información sobre el duplicado
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 409 CONFLICT}
         */
        @ExceptionHandler(MovieAlreadyInFavoritesException.class)
        public ResponseEntity<ErrorResponse> handleMovieAlreadyInFavorites(
                        MovieAlreadyInFavoritesException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.CONFLICT,
                                ErrorCode.MOVIE_ALREADY_IN_FAVORITES, exception.getMessage(), request);
        }

        /**
         * Gestiona los intentos de eliminar una película que no pertenece a la
         * lista de favoritos del usuario.
         *
         * @param exception excepción que contiene información sobre la ausencia
         *                  de la película en la lista
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 404 NOT_FOUND}
         */
        @ExceptionHandler(MovieNotInFavoritesException.class)
        public ResponseEntity<ErrorResponse> handleMovieNotInFavorites(
                        MovieNotInFavoritesException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.NOT_FOUND,
                                ErrorCode.MOVIE_NOT_IN_FAVORITES, exception.getMessage(), request);
        }

        /**
         * Gestiona los errores producidos cuando los datos recibidos
         * en una petición no cumplen las validaciones definidas mediante
         * las anotaciones de Jakarta Validation.
         *
         * @param exception excepción que contiene los errores de validación
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 400 BAD_REQUEST} y
         *         los errores asociados a cada campo
         */
        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ErrorResponse> handleValidationErrors(
                        MethodArgumentNotValidException exception,
                        HttpServletRequest request) {

                Map<String, String> errors = new LinkedHashMap<>();

                exception.getBindingResult()
                                .getFieldErrors()
                                .forEach(error -> errors.putIfAbsent(
                                                error.getField(),
                                                error.getDefaultMessage()));

                return buildErrorResponse(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                                VALIDATION_MESSAGE, request, errors);
        }

        /**
         * Gestiona las excepciones producidas cuando las credenciales
         * proporcionadas durante la autenticación no son válidas.
         *
         * @param exception excepción producida durante la autenticación
         * @param request   petición HTTP que produjo la excepción
         * @return respuesta HTTP con estado {@code 401 UNAUTHORIZED}
         */
        @ExceptionHandler(InvalidCredentialsException.class)
        public ResponseEntity<ErrorResponse> handleInvalidCredentials(
                        InvalidCredentialsException exception,
                        HttpServletRequest request) {

                return buildErrorResponse(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS,
                                exception.getMessage(), request);
        }

        /**
         * Protege la API frente a carreras entre la comprobación previa y la inserción.
         * La causa concreta se registra en el servidor, pero nunca se expone al cliente.
         */
        @ExceptionHandler(DataIntegrityViolationException.class)
        public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
                        DataIntegrityViolationException exception,
                        HttpServletRequest request) {

                LOGGER.warn("Conflicto de integridad al procesar {}", request.getRequestURI(), exception);
                return buildErrorResponse(HttpStatus.CONFLICT, ErrorCode.DATA_INTEGRITY_VIOLATION,
                                INTEGRITY_MESSAGE, request);
        }

        /**
         * Registra los errores no previstos y devuelve una respuesta segura y uniforme.
         */
        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleUnexpectedException(
                        Exception exception,
                        HttpServletRequest request) {

                LOGGER.error("Error no controlado al procesar {}", request.getRequestURI(), exception);
                return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                                INTERNAL_ERROR_MESSAGE, request);
        }

        /**
         * Construye una respuesta de error estándar sin errores de validación.
         *
         * @param status  estado HTTP que se devolverá
         * @param message mensaje personalizado que describe el error
         * @param request petición HTTP que produjo el error
         * @return respuesta con el formato común {@link ErrorResponse}
         */
        private ResponseEntity<ErrorResponse> buildErrorResponse(
                        HttpStatus status,
                        ErrorCode code,
                        String message,
                        HttpServletRequest request) {

                return buildErrorResponse(status, code, message, request, null);
        }

        private ResponseEntity<ErrorResponse> buildErrorResponse(
                        HttpStatus status,
                        ErrorCode code,
                        String message,
                        HttpServletRequest request,
                        Map<String, String> validationErrors) {

                ErrorResponse error = new ErrorResponse(
                                LocalDateTime.now(),
                                status.value(),
                                status.getReasonPhrase(),
                                code,
                                message,
                                request.getRequestURI(),
                                validationErrors);

                return ResponseEntity.status(status).body(error);
        }
}