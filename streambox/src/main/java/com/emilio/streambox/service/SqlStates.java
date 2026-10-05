package com.emilio.streambox.service;

import java.sql.SQLException;

/**
 * Utilidades para identificar qué restricción de la base de datos ha fallado
 * a partir del {@code SQLSTATE} de la excepción del driver.
 *
 * <p>
 * Los servicios traducen algunas violaciones de integridad a excepciones de
 * dominio (p. ej. un duplicado que se cuela por una carrera entre la
 * comprobación y el {@code INSERT}). Para decidir se usa el {@code SQLSTATE},
 * que es estándar, y no el texto del mensaje, que cambia con el motor y con
 * el idioma. Antes esta lógica vivía solo en {@link FavoriteService}; se
 * extrajo aquí al necesitarla también {@link GenreService}.
 * </p>
 *
 * <p>
 * Es de paquete porque solo la usan los servicios.
 * </p>
 */
final class SqlStates {

    /** Violación de unicidad (PostgreSQL y H2). */
    static final String UNIQUE_VIOLATION = "23505";

    /**
     * Violación de clave foránea (PostgreSQL; H2 también lo usa cuando se
     * intenta borrar una fila a la que otras siguen apuntando).
     */
    static final String FOREIGN_KEY_VIOLATION = "23503";

    /** H2 usa este código cuando el registro padre de la clave foránea no existe. */
    static final String H2_FOREIGN_KEY_PARENT_MISSING = "23506";

    private SqlStates() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Recorre la cadena de causas y devuelve el primer {@code SQLSTATE} que
     * encuentre. Hibernate envuelve el {@link SQLException} del driver en su
     * {@code ConstraintViolationException} y Spring lo envuelve a su vez, por lo
     * que el código no está en la excepción de arriba.
     *
     * @param error excepción de la que partir
     * @return el {@code SQLSTATE}, o {@code null} si ninguna causa lo tiene
     */
    static String find(Throwable error) {

        Throwable current = error;
        // El límite protege de ciclos raros en la cadena de causas.
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
