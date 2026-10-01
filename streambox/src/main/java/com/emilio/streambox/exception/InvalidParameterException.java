package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando un parámetro de la petición tiene un valor
 * que la API no acepta (por ejemplo, un campo de ordenación no permitido).
 *
 * <p>
 * Se traduce en una respuesta {@code 400 Bad Request} que indica
 * el parámetro afectado.
 * </p>
 */
public class InvalidParameterException extends RuntimeException {

    private final String parameter;

    /**
     * Crea la excepción indicando el parámetro erróneo.
     *
     * @param parameter nombre del parámetro de la petición
     * @param message   motivo por el que el valor no es válido
     */
    public InvalidParameterException(String parameter, String message) {
        super(message);
        this.parameter = parameter;
    }

    /**
     * @return nombre del parámetro cuyo valor no es válido
     */
    public String getParameter() {
        return parameter;
    }
}
