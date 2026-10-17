package com.emilio.streambox.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hibernate.exception.ConstraintViolationException;

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
 * Es pública porque también la usa {@code GlobalExceptionHandler} para dejar
 * en el log qué restricción falló cuando ningún servicio tradujo la violación
 * (sin el mensaje del motor, que puede llevar valores de la fila). Las
 * constantes siguen siendo de paquete: decidir qué significa cada código es
 * cosa de los servicios.
 * </p>
 */
public final class SqlStates {

    /** Violación de unicidad (PostgreSQL y H2). */
    static final String UNIQUE_VIOLATION = "23505";

    /**
     * Violación de clave foránea (PostgreSQL; H2 también lo usa cuando se
     * intenta borrar una fila a la que otras siguen apuntando).
     */
    static final String FOREIGN_KEY_VIOLATION = "23503";

    /** H2 usa este código cuando el registro padre de la clave foránea no existe. */
    static final String H2_FOREIGN_KEY_PARENT_MISSING = "23506";

    /**
     * Comienzo de un nombre de restricción: letras, dígitos, guion bajo, punto
     * (H2 lo antepone con el esquema, {@code PUBLIC.UK_...}) y dólar.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("^\"?([\\p{L}\\p{N}_.$]+)");

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
    public static String find(Throwable error) {

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

    /**
     * Indica si el error es una violación de clave foránea ({@code 23503}, o
     * {@code 23506} en H2 cuando falta el registro padre) de la restricción
     * indicada.
     *
     * @param error      excepción de la que partir
     * @param constraint nombre de la restricción en minúsculas (p. ej.
     *                   {@code "fk_movie_genres_genre"})
     * @return {@code true} si es esa clave foránea; {@code false} si es otra
     *         violación o no se sabe cuál
     */
    static boolean isForeignKeyViolation(Throwable error, String constraint) {

        String sqlState = find(error);
        if (!FOREIGN_KEY_VIOLATION.equals(sqlState) && !H2_FOREIGN_KEY_PARENT_MISSING.equals(sqlState)) {
            return false;
        }
        String name = constraintName(error);
        return name != null && name.contains(constraint);
    }

    /**
     * Devuelve el nombre de la restricción que ha fallado, en minúsculas, tal
     * como lo extrae Hibernate del error del driver
     * ({@link ConstraintViolationException#getConstraintName()}), o
     * {@code null} si no se conoce (por ejemplo, un error de datos como el NUL
     * en un texto, que no viola ninguna restricción con nombre).
     *
     * <p>
     * <b>Solo se conserva el identificador inicial.</b> Hibernate saca el
     * nombre del texto del mensaje, y en H2 ese texto sigue con la definición
     * del índice y los <em>valores</em> de la fila
     * ({@code "PUBLIC.UK_USERS_EMAIL_INDEX_4 ON PUBLIC.USERS(EMAIL ...) VALUES ('ana@...')"}).
     * Recortarlo al primer identificador evita que un email acabe en el log o
     * en una comparación. En PostgreSQL el nombre ya viene limpio
     * ({@code uk_users_email}).
     * </p>
     *
     * <p>
     * Se compara con {@code contains} sobre el resultado (p. ej.
     * {@code "uk_users_email"}), porque H2 le añade el esquema delante y, en
     * los índices únicos, un sufijo ({@code _index_4}).
     * </p>
     *
     * @param error excepción de la que partir
     * @return el nombre en minúsculas, o {@code null} si ninguna causa lo trae
     */
    public static String constraintName(Throwable error) {

        Throwable current = error;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                Matcher matcher = IDENTIFIER.matcher(violation.getConstraintName().trim());
                return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
