package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando existe más de una película con el mismo título
 * y la operación no puede resolverse sin un identificador único.
 *
 * <p>
 * Se produce al intentar buscar una película por título exacto en
 * operaciones de favoritos cuando hay duplicados en la base de datos.
 * El cliente debe repetir la operación utilizando el ID de la película.
 * </p>
 */
public class AmbiguousTitleException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje que indica qué título resulta ambiguo
     */
    public AmbiguousTitleException(String message) {
        super(message);
    }
}
