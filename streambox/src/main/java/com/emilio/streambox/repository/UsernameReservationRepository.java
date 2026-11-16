package com.emilio.streambox.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceso a datos de las <b>reservas de nombre de usuario</b> (tablas
 * {@code username_reservations} y {@code username_reservation_owners},
 * migración {@code V13}). La semántica (para qué sirven, cuánto duran, quién
 * es dueño) la explica {@code service.UsernameReservations}; aquí solo se
 * guarda y se consulta.
 *
 * <h2>Modelo</h2>
 * <ul>
 *   <li>Una fila de {@code username_reservations} por nombre reservado, con
 *       <b>su</b> caducidad: todos los dueños caducan a la vez, a las 24 h
 *       del primer registro. Que la caducidad esté en una sola fila (y el
 *       nombre sea la clave primaria) lo garantiza la base de datos, también
 *       con dos registros simultáneos.</li>
 *   <li>Una fila de {@code username_reservation_owners} por cada email que
 *       pidió el nombre, guardado como SHA-256 en hexadecimal
 *       ({@link #emailHash}): solo se compara por igualdad.</li>
 *   <li>Una reserva caducada ({@code expires_at <= ahora}) <b>no cuenta
 *       nunca</b>, aunque siga en la tabla hasta la limpieza
 *       ({@link #deleteExpired}).</li>
 * </ul>
 *
 * <h2>Por qué SQL con {@link JdbcClient} y no una entidad JPA</h2>
 * <p>
 * Nadie necesita cargar una reserva como objeto: solo se hacen preguntas de
 * sí/no ({@link #isReserved}, {@link #isReservedForOthers}) y escrituras que
 * tienen que ser atómicas o bloquear la fila ({@link #reserve}, {@link #lock},
 * {@link #deleteLock}, {@link #deleteExpired}). Con una entidad, reservar
 * sería «leer, decidir en Java y guardar», que con dos registros
 * simultáneos falla (los dos leen «no hay reserva», los dos insertan y uno
 * acaba en un 500 por la clave primaria). Con SQL directo cada paso es una
 * sentencia que la base de datos resuelve sola ({@code ON CONFLICT DO
 * NOTHING}), igual que los «me gusta» de {@link MovieRepository#addLike}.
 * Un repositorio de Spring Data no sirve aquí porque exige una entidad.
 * </p>
 *
 * <h2>«Ahora» lo pasa el llamador</h2>
 * <p>
 * Todas las consultas reciben el instante del {@code Clock} de la aplicación
 * (que los tests controlan) y nunca usan {@code now()} de la base de datos:
 * así la caducidad se decide con el mismo reloj en todas partes.
 * </p>
 *
 * <h2>Transacciones</h2>
 * <p>
 * {@link JdbcClient} usa la conexión de la transacción en curso (la de JPA,
 * si el servicio tiene una), así que una reserva hecha dentro del registro se
 * confirma o se deshace con él. {@link #reserve} y {@link #deleteExpired}
 * abren una propia si no hay ninguna.
 * </p>
 *
 * <h2>La fila del nombre es también su cerrojo</h2>
 * <p>
 * {@link #reserve} y {@link #lock} dejan la fila del nombre <b>bloqueada hasta
 * el final de la transacción</b>, tanto si la crean como si ya existía. Es el
 * punto por el que pasan, antes de leer nada, todas las transacciones que
 * pueden dar un nombre a alguien (registro y «Editar perfil»): dos sobre el
 * mismo nombre se ejecutan una detrás de otra, y la segunda ve lo que confirmó
 * la primera. Sin él, una reserva sin confirmar era invisible para un registro
 * o un cambio de nombre simultáneos, que daban el nombre por libre (ver
 * {@code service.UsernameReservations}, «Concurrencia»). Solo usa SQL que
 * entienden los dos motores: {@code SELECT ... FOR UPDATE}, que es estándar, y
 * {@code ON CONFLICT DO NOTHING}, que es sintaxis de PostgreSQL que H2 admite
 * en modo PostgreSQL (el de los tests). Nada de cerrojos propios de
 * PostgreSQL (como los {@code pg_advisory_lock}), que H2 no tiene.
 * </p>
 */
@Repository
public class UsernameReservationRepository {

    /**
     * Vueltas de {@link #claim} antes de rendirse. La segunda solo hace falta
     * si la fila con la que chocó el {@code INSERT} desaparece antes de poder
     * bloquearla (otra transacción la borró por caducada con un reloj algo
     * adelantado); una tercera seguida no debería ocurrir nunca.
     */
    private static final int MAX_CLAIM_ATTEMPTS = 3;

    private final JdbcClient jdbc;

    /**
     * Crea el repositorio.
     *
     * @param jdbc cliente JDBC de Spring (autoconfigurado sobre el
     *             {@code DataSource} de la aplicación)
     */
    public UsernameReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserva un nombre a nombre de un email. Atómica y segura con
     * concurrencia.
     *
     * <ul>
     *   <li>Si el nombre <b>no</b> tiene una reserva vigente, crea una que
     *       caduca en {@code now + ttl} con ese email como dueño.</li>
     *   <li>Si <b>ya</b> la tiene, añade el email como dueño (si no lo era)
     *       <b>sin alargar</b> la caducidad.</li>
     * </ul>
     *
     * <p>
     * Primero reclama la fila del nombre con {@link #claim} (que la deja
     * <b>bloqueada hasta el final de la transacción</b>, ver la clase) y
     * después añade el dueño con {@code ON CONFLICT DO NOTHING} (repetir el
     * mismo email no hace nada). Como la fila ya está bloqueada, nadie puede
     * borrarla entre las dos sentencias: antes, si caducaba y otra
     * transacción la borraba justo ahí, el {@code INSERT} del dueño violaba la
     * clave foránea y el registro respondía 500.
     * </p>
     *
     * @param username nombre <b>normalizado</b> (como mucho 50 caracteres,
     *                 como {@code users.username})
     * @param email    email <b>normalizado</b> del registro (se guarda solo su
     *                 SHA-256)
     * @param now      instante actual, del {@code Clock} de la aplicación
     * @param ttl      vida de una reserva <b>nueva</b>
     *                 ({@code streambox.email.verification-ttl}); positiva
     * @return {@code true} si esta llamada creó la reserva del nombre (no había
     *         ninguna vigente); {@code false} si ya había una vigente (solo se
     *         añadió el dueño)
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException si
     *         {@code ttl} no es positiva (es la {@link IllegalArgumentException}
     *         original, traducida por {@code @Repository}; es un error de
     *         programación, nunca de los datos del usuario)
     */
    @Transactional
    public boolean reserve(String username, String email, Instant now, Duration ttl) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(now, "now");
        requirePositive(ttl);
        // Antes de escribir nada: un email nulo no deja una reserva sin dueño.
        String hash = emailHash(email);

        boolean created = claim(username, now, ttl);

        jdbc.sql("INSERT INTO username_reservation_owners (username, email_hash)"
                + " VALUES (:username, :emailHash) ON CONFLICT DO NOTHING")
                .param("username", username)
                .param("emailHash", hash)
                .update();

        return created;
    }

    /**
     * Bloquea el nombre hasta el final de la transacción <b>sin reservarlo</b>
     * para nadie: para «Editar perfil», que tiene que serializarse con los
     * registros que piden ese nombre pero no debe dejar ninguna reserva.
     *
     * <p>
     * Si el nombre ya tenía una reserva vigente, solo la bloquea (no la
     * cambia). Si no, crea su fila (sin dueños) para tener algo que bloquear:
     * en ese caso devuelve {@code true} y quien llama <b>debe</b> quitarla con
     * {@link #deleteLock} antes de confirmar. Aunque la borre, el bloqueo sigue
     * hasta el final de la transacción: en PostgreSQL, quien espera para
     * insertar ese mismo nombre espera a la transacción que insertó la fila, no
     * a la fila.
     * </p>
     *
     * @param username nombre normalizado
     * @param now      instante actual, del {@code Clock} de la aplicación
     * @param ttl      vida que tendría la fila (positiva; no llega a verse
     *                 fuera de la transacción si se quita con {@link #deleteLock})
     * @return {@code true} si no había reserva vigente y la fila la ha creado
     *         esta llamada; {@code false} si había una vigente (ahora bloqueada)
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso (un bloqueo que se suelta al
     *         terminar la sentencia no serviría de nada)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lock(String username, Instant now, Duration ttl) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(now, "now");
        requirePositive(ttl);
        return claim(username, now, ttl);
    }

    /**
     * Quita la fila que creó {@link #lock} (la que devolvió {@code true}), para
     * que no se confirme como una reserva. Solo la borra si no tiene dueños:
     * nunca puede llevarse por delante una reserva de verdad.
     *
     * @param username nombre normalizado
     * @throws org.springframework.transaction.IllegalTransactionStateException
     *         si no hay una transacción en curso
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteLock(String username) {
        jdbc.sql("DELETE FROM username_reservations WHERE username = :username"
                + " AND NOT EXISTS (SELECT 1 FROM username_reservation_owners o WHERE o.username = :username)")
                .param("username", username)
                .update();
    }

    /**
     * Reclama la fila del nombre: al terminar, la fila existe, está
     * <b>vigente</b> y esta transacción la tiene <b>bloqueada</b> hasta el
     * final (la haya creado o no).
     *
     * <ol>
     *   <li>Borra la reserva <b>caducada</b> de ese nombre, si la hay (la
     *       cascada se lleva a sus dueños). Sin este paso, el {@code INSERT}
     *       siguiente chocaría con ella y el nombre quedaría sin reservar hasta
     *       la limpieza programada.</li>
     *   <li>La crea con {@code ON CONFLICT DO NOTHING}. Si otra transacción
     *       está creando esa misma fila, la base de datos espera a que termine:
     *       si confirma, no inserta nada (ni falla), así que el nombre queda con
     *       <b>una sola</b> caducidad, la del primero; si se deshace o la borra
     *       antes de confirmar ({@link #deleteLock}), la crea esta. Una fila
     *       recién insertada queda bloqueada hasta el final de la
     *       transacción.</li>
     *   <li>Si no la creó, la bloquea con {@code SELECT ... FOR UPDATE}.
     *       Hace falta porque un {@code ON CONFLICT DO NOTHING} que choca con
     *       una fila <b>ya confirmada</b> no la bloquea: sin este paso, dos
     *       transacciones sobre un nombre con una reserva vigente no se
     *       esperarían. Si la fila desaparece entre el paso 2 y este (se borró
     *       por caducada con el reloj de otro), se vuelve a empezar.</li>
     * </ol>
     *
     * <p>
     * <b>Sin interbloqueos:</b> cada transacción reclama un solo nombre y lo
     * hace antes de bloquear ninguna otra fila, así que nunca espera por un
     * nombre mientras retiene filas que otra necesita (ver
     * {@code service.UsernameReservations}, «Concurrencia»).
     * </p>
     *
     * @return {@code true} si la fila la ha creado esta llamada (no había
     *         reserva vigente); {@code false} si ya había una vigente
     */
    private boolean claim(String username, Instant now, Duration ttl) {
        OffsetDateTime nowUtc = utc(now);
        for (int attempt = 1; attempt <= MAX_CLAIM_ATTEMPTS; attempt++) {
            jdbc.sql("DELETE FROM username_reservations WHERE username = :username AND expires_at <= :now")
                    .param("username", username)
                    .param("now", nowUtc)
                    .update();

            int created = jdbc.sql("INSERT INTO username_reservations (username, created_at, expires_at)"
                    + " VALUES (:username, :now, :expiresAt) ON CONFLICT DO NOTHING")
                    .param("username", username)
                    .param("now", nowUtc)
                    .param("expiresAt", utc(now.plus(ttl)))
                    .update();
            if (created == 1) {
                return true;
            }

            boolean lockedExisting = !jdbc.sql("SELECT username FROM username_reservations"
                    + " WHERE username = :username AND expires_at > :now FOR UPDATE")
                    .param("username", username)
                    .param("now", nowUtc)
                    .query(String.class)
                    .list()
                    .isEmpty();
            if (lockedExisting) {
                return false;
            }
        }
        throw new IllegalStateException("No se ha podido bloquear la reserva del nombre en "
                + MAX_CLAIM_ATTEMPTS + " intentos");
    }

    /**
     * Indica si el nombre tiene una reserva vigente, de quien sea. Consulta
     * de solo lectura (tests y diagnóstico): ni el registro ni «Editar perfil»
     * la usan para decidir, porque no ve la reserva sin confirmar de una
     * transacción simultánea (deciden después de reclamar la fila con
     * {@link #reserve} o {@link #lock}).
     *
     * @param username nombre normalizado
     * @param now      instante actual, del {@code Clock} de la aplicación
     * @return {@code true} si hay una reserva con {@code expires_at > now}
     */
    public boolean isReserved(String username, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM username_reservations"
                + " WHERE username = :username AND expires_at > :now")
                .param("username", username)
                .param("now", utc(now))
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Indica si el nombre tiene una reserva vigente de la que ese email
     * <b>no</b> es dueño (para «Editar perfil»: la cuenta del email que la
     * hizo sí puede usar el nombre).
     *
     * @param username nombre normalizado
     * @param email    email normalizado de la cuenta que quiere el nombre
     * @param now      instante actual, del {@code Clock} de la aplicación
     * @return {@code true} si hay una reserva vigente y ese email no está entre
     *         sus dueños
     */
    public boolean isReservedForOthers(String username, String email, Instant now) {
        return jdbc.sql("SELECT COUNT(*) FROM username_reservations r"
                + " WHERE r.username = :username AND r.expires_at > :now"
                + " AND NOT EXISTS (SELECT 1 FROM username_reservation_owners o"
                + " WHERE o.username = r.username AND o.email_hash = :emailHash)")
                .param("username", username)
                .param("now", utc(now))
                .param("emailHash", emailHash(email))
                .query(Long.class)
                .single() > 0;
    }

    /**
     * Borra las reservas caducadas (y, por la cascada, sus dueños). Para la
     * limpieza programada: sin ella, los nombres que nadie vuelve a pedir se
     * quedarían en la tabla para siempre (no cuentan, pero ocupan). Usa el
     * índice {@code idx_username_reservations_expires_at}.
     *
     * @param now instante actual, del {@code Clock} de la aplicación; se borran
     *            las reservas con {@code expires_at <= now}
     * @return número de reservas (nombres) borradas
     */
    @Transactional
    public int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM username_reservations WHERE expires_at <= :now")
                .param("now", utc(now))
                .update();
    }

    /**
     * SHA-256 de un email en hexadecimal en minúsculas: lo que se guarda en
     * {@code username_reservation_owners.email_hash} (el mismo formato que los
     * hashes de {@code account_tokens} y {@code refresh_tokens}, y el que exige
     * el {@code CHECK} de la tabla).
     *
     * <p>
     * El email se codifica en UTF-8, no en ASCII como los tokens: un token
     * solo tiene caracteres ASCII, pero un email puede tener otros, y con
     * ASCII cada uno se convertiría en {@code ?} y dos emails distintos
     * podrían dar el mismo hash. Llega ya normalizado (el llamador lo hace),
     * así que mayúsculas y minúsculas no dan hashes distintos.
     * </p>
     *
     * @param email email normalizado
     * @return 64 caracteres hexadecimales en minúsculas
     */
    public static String emailHash(String email) {
        Objects.requireNonNull(email, "email");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(email.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Toda JVM está obligada a tener SHA-256.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Una vida nula, cero o negativa es un error de programación del llamador. */
    private static void requirePositive(Duration ttl) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("La vida de una reserva debe ser positiva: " + ttl);
        }
    }

    /**
     * Pasa un {@link Instant} a {@link OffsetDateTime} en UTC: el driver de
     * PostgreSQL no admite {@code Instant} como parámetro JDBC (Hibernate lo
     * convierte solo, pero aquí no hay Hibernate) y los dos motores aceptan
     * {@code OffsetDateTime} para {@code TIMESTAMP WITH TIME ZONE}.
     */
    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(Objects.requireNonNull(instant, "instant"), ZoneOffset.UTC);
    }
}
