package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Tests unitarios de {@link SqlStates#constraintName} y
 * {@link SqlStates#isForeignKeyViolation}, con la forma real de las
 * excepciones (Spring envuelve a Hibernate, que envuelve al driver).
 *
 * <p>
 * Lo importante es que el nombre se recorta al identificador: en H2 el texto
 * del que lo saca Hibernate sigue con los valores de la fila, que no deben
 * llegar al log. Que los motores reales producen estos formatos lo comprueban
 * {@code IntegrityViolationTranslationIntegrationTest} (H2) y
 * {@code PostgresControlCharactersIntegrationTest} (PostgreSQL).
 * </p>
 */
class SqlStatesTest {

    @Test
    void elNombreSeRecortaAlIdentificadorYSePasaAMinusculas() {
        // PostgreSQL: el nombre limpio
        assertEquals("uk_users_email", SqlStates.constraintName(violation("23505", "uk_users_email")));
        // H2: esquema delante, sufijo del índice y, detrás, la definición y los valores
        assertEquals("public.uk_users_email_index_4", SqlStates.constraintName(violation("23505",
                "PUBLIC.UK_USERS_EMAIL_INDEX_4 ON PUBLIC.USERS(EMAIL NULLS FIRST) VALUES ( /* 1 */ 'ana@x.com' )")));
        assertEquals("fk_movie_genres_genre", SqlStates.constraintName(violation("23506",
                "\"FK_MOVIE_GENRES_GENRE: PUBLIC.MOVIE_GENRES FOREIGN KEY(GENRE_ID)\"")));
    }

    @Test
    void sinRestriccionConNombreDevuelveNull() {
        assertNull(SqlStates.constraintName(violation("23505", null)));
        assertNull(SqlStates.constraintName(new DataIntegrityViolationException("sin causa")));
        assertNull(SqlStates.constraintName(violation("23505", "  ( valores )")));
    }

    @Test
    void reconoceLaClaveForaneaIndicadaEnPostgresqlYEnH2() {
        assertTrue(SqlStates.isForeignKeyViolation(violation("23503", "fk_movie_genres_genre"),
                "fk_movie_genres_genre"));
        assertTrue(SqlStates.isForeignKeyViolation(violation("23506", "FK_MOVIE_GENRES_GENRE: PUBLIC.X"),
                "fk_movie_genres_genre"));
    }

    @Test
    void otraClaveForaneaOtroCodigoOSinNombreNoCuentan() {
        assertFalse(SqlStates.isForeignKeyViolation(violation("23503", "fk_movie_genres_movie"),
                "fk_movie_genres_genre"));
        assertFalse(SqlStates.isForeignKeyViolation(violation("23505", "fk_movie_genres_genre"),
                "fk_movie_genres_genre"));
        assertFalse(SqlStates.isForeignKeyViolation(violation("23503", null), "fk_movie_genres_genre"));
    }

    private static DataIntegrityViolationException violation(String sqlState, String constraintName) {
        SQLException driver = new SQLException("violación", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraintName));
    }
}
