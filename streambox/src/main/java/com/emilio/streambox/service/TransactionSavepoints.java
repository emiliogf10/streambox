package com.emilio.streambox.service;

import java.sql.Connection;
import java.sql.Savepoint;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * <b>Puntos de guardado</b> ({@code SAVEPOINT}) dentro de la transacción en
 * curso: permiten deshacer solo lo hecho desde un punto, sin deshacer toda la
 * transacción.
 *
 * <h2>Para qué</h2>
 * <p>
 * El registro ({@link RegistrationService}) reclama el nombre de usuario (su
 * cerrojo) al principio de la transacción y, si las cuentas que mira cambian
 * mientras decide, tiene que repetir la decisión <b>sin soltar ese
 * nombre</b>: deshacer toda la transacción lo soltaba a mitad de camino y
 * dejaba entrar a otra petición que esperaba por él (incidencia MEDIA-A). Con
 * un punto de guardado tomado justo después de reclamar el nombre, volver a
 * él deshace la decisión a medias (borrados, bloqueos de filas de
 * {@code users}) y conserva el nombre.
 * </p>
 *
 * <h2>Los bloqueos también se deshacen</h2>
 * <p>
 * En PostgreSQL, un bloqueo de fila tomado después de un punto de guardado se
 * suelta al volver a él (documentación de PostgreSQL, «Explicit Locking»). Es
 * lo que permite repetir la decisión bloqueando las filas otra vez en orden de
 * id sin arriesgar un interbloqueo: tras volver, la transacción solo retiene lo
 * que tenía antes del punto (el nombre), como al empezar.
 * </p>
 *
 * <h2>Por qué no {@code Propagation.NESTED}</h2>
 * <p>
 * Spring hace lo mismo con {@code NESTED}, pero el {@code JpaTransactionManager}
 * que configura Spring Boot no lo permite ({@code nestedTransactionAllowed}
 * es {@code false}), y con razón: un punto de guardado deshace la base de
 * datos, pero no la sesión de Hibernate, que seguiría con las entidades leídas
 * después. Activarlo para toda la aplicación invitaría a usarlo sin ese
 * cuidado. Aquí se hace solo donde hace falta, con JDBC sobre la conexión de
 * la transacción (la misma que usa Hibernate: {@link JdbcTemplate} la obtiene
 * de la transacción de JPA), y {@link #rollbackTo} vacía la sesión.
 * </p>
 *
 * <p>
 * Funciona igual en H2 (los tests) y en PostgreSQL: los dos implementan
 * {@link Connection#setSavepoint()} y {@link Connection#rollback(Savepoint)}.
 * </p>
 */
@Component
public class TransactionSavepoints {

    private final JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Crea el componente.
     *
     * @param jdbcTemplate acceso JDBC sobre la conexión de la transacción en curso
     */
    public TransactionSavepoints(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Toma un punto de guardado en la transacción en curso.
     *
     * <p>
     * Antes vuelca a la base de datos lo pendiente de la sesión de Hibernate
     * ({@code flush}): lo hecho antes del punto debe quedar <b>antes</b> de
     * él, no escribirse después y deshacerse al volver.
     * </p>
     *
     * @return el punto de guardado, para {@link #rollbackTo}
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso (sin ella, el punto se tomaría
     *         en una conexión suelta y no serviría de nada)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Savepoint create() {
        entityManager.flush();
        return jdbcTemplate.execute((ConnectionCallback<Savepoint>) Connection::setSavepoint);
    }

    /**
     * Deshace todo lo hecho en la transacción desde el punto de guardado
     * (escrituras y, en PostgreSQL, bloqueos de filas), que sigue valiendo
     * para volver a él otra vez.
     *
     * <p>
     * Vacía antes la sesión de Hibernate ({@code clear}): las entidades leídas
     * o cambiadas después del punto podrían no coincidir ya con la base de
     * datos, y lo que quedara sin volcar se escribiría después. Lo cargado
     * antes del punto también queda desacoplado: quien llama debe volver a
     * leer lo que necesite.
     * </p>
     *
     * @param savepoint punto devuelto por {@link #create} en esta misma transacción
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void rollbackTo(Savepoint savepoint) {
        entityManager.clear();
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            connection.rollback(savepoint);
            return null;
        });
    }
}
