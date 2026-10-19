package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.AccountToken;
import com.emilio.streambox.entity.AccountTokenPurpose;

/**
 * Acceso a datos de {@link AccountToken} (tabla {@code account_tokens},
 * migración {@code V9}): tokens de un solo uso de una cuenta.
 *
 * <h2>Cómo se usa (verificar el email)</h2>
 * <pre>{@code
 * // Dentro de UNA transacción del servicio, con el instante de su Clock:
 * if (accountTokenRepository.consume(hash, EMAIL_VERIFICATION, now) == 1) {
 *     Long userId = accountTokenRepository
 *             .findUserIdByTokenHashAndPurpose(hash, EMAIL_VERIFICATION).orElseThrow();
 *     userRepository.markEmailVerified(userId, now);
 * } else {
 *     // inexistente, caducado, ya usado o de otro propósito: mismo error para todos
 * }
 * }</pre>
 *
 * <h2>Por qué {@code UPDATE} condicional y no «leer, comprobar y guardar»</h2>
 * <p>
 * Si el servicio leyera el token, comprobara en Java que no está usado y luego
 * lo marcara, dos confirmaciones simultáneas (un doble clic, el enlace abierto
 * en dos pestañas) leerían los dos «sin usar» y lo gastarían
 * dos veces. Con un único {@code UPDATE ... WHERE used_at IS NULL AND
 * expires_at > :now}, la base de datos bloquea la fila: el segundo
 * {@code UPDATE} espera al primero y, cuando este confirma, vuelve a evaluar
 * la condición sobre la fila ya actualizada y no la cumple (0 filas). Lo
 * prueba {@code PostgresAccountTokenIntegrationTest} con hilos de verdad.
 * </p>
 *
 * <h2>Sesión de Hibernate</h2>
 * <p>
 * Las escrituras masivas llevan {@code flushAutomatically} (los cambios
 * pendientes se escriben antes) pero, a diferencia de
 * {@link RefreshTokenRepository}, <strong>no</strong> vacían la sesión
 * ({@code clearAutomatically = false}): vaciarla desacoplaría también el
 * {@code User} que el servicio tenga cargado y sus cambios posteriores se
 * perderían sin aviso. A cambio, un {@link AccountToken} leído antes de
 * consumirlo o invalidarlo conserva en memoria su {@code usedAt} antiguo: el
 * servicio no debe decidir nada con él después (para eso están los resultados
 * de estos métodos o {@link #hasActiveToken}).
 * </p>
 *
 * <p>
 * Todas las consultas filtran por {@link AccountTokenPurpose}: un token de un
 * propósito nunca sirve para otro.
 * </p>
 */
public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    /**
     * Busca un token por su hash y su propósito, esté como esté (vigente,
     * caducado o usado).
     *
     * <p>
     * Para decidir si un token vale, usa {@link #consume}: esta lectura no
     * bloquea nada y su resultado puede quedar viejo en cuanto otra petición
     * lo consuma.
     * </p>
     *
     * @param tokenHash SHA-256 del token en hexadecimal en minúsculas
     * @param purpose   propósito del token
     * @return el token, o vacío si no existe para ese propósito
     */
    Optional<AccountToken> findByTokenHashAndPurpose(String tokenHash, AccountTokenPurpose purpose);

    /**
     * <strong>Consume</strong> el token de forma atómica: lo marca como usado
     * si existe para ese propósito, no se había usado y no ha caducado.
     *
     * <p>
     * Devuelve las filas afectadas: {@code 1} = el token era válido y esta
     * llamada lo ha gastado; {@code 0} = no existe, es de otro propósito, ya se
     * usó (o se invalidó) o caducó ({@code expires_at <= now}). Dos llamadas
     * simultáneas con el mismo token: exactamente una recibe {@code 1}.
     * </p>
     *
     * <p>
     * {@code MANDATORY}: debe ir en la misma transacción que lo que se hace con
     * el token (marcar el email verificado). Si fuera en una propia, un fallo
     * después del {@code commit} dejaría el token gastado y la cuenta sin
     * verificar; fuera de una transacción lanza
     * {@code IllegalTransactionStateException}.
     * </p>
     *
     * @param tokenHash SHA-256 del token en hexadecimal en minúsculas
     * @param purpose   propósito que se espera
     * @param now       instante actual (del {@code Clock} del servicio)
     * @return {@code 1} si se ha consumido ahora, {@code 0} si no era válido
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true)
    @Query("update AccountToken t set t.usedAt = :now"
            + " where t.tokenHash = :tokenHash and t.purpose = :purpose"
            + " and t.usedAt is null and t.expiresAt > :now")
    int consume(@Param("tokenHash") String tokenHash, @Param("purpose") AccountTokenPurpose purpose,
            @Param("now") Instant now);

    /**
     * Devuelve el id de la cuenta dueña de un token, sin cargar ni el token ni
     * el usuario ({@code user_id} sale de la propia fila del token).
     *
     * <p>
     * Se llama tras un {@link #consume} que devolvió {@code 1}, en la misma
     * transacción: la fila sigue bloqueada por ese {@code UPDATE} y
     * {@code user_id} no cambia nunca, así que el resultado es seguro.
     * </p>
     *
     * @param tokenHash SHA-256 del token en hexadecimal en minúsculas
     * @param purpose   propósito del token
     * @return el id de la cuenta, o vacío si el token no existe
     */
    @Query("select t.user.id from AccountToken t where t.tokenHash = :tokenHash and t.purpose = :purpose")
    Optional<Long> findUserIdByTokenHashAndPurpose(@Param("tokenHash") String tokenHash,
            @Param("purpose") AccountTokenPurpose purpose);

    /**
     * Invalida (marca como usados) los tokens <strong>vigentes</strong> de una
     * cuenta para un propósito: al reenviar el correo, los enlaces anteriores
     * dejan de servir.
     *
     * <p>
     * Se marcan en vez de borrarse para que sigan contando en
     * {@link #countIssuedSince} (límite de reenvíos por cuenta) hasta que la
     * limpieza los borre. Los ya usados conservan su {@code used_at} original y
     * los caducados no se tocan (ya no sirven; marcarlos violaría
     * {@code ck_account_tokens_used_before_expiry}). Usa
     * {@code idx_account_tokens_user_purpose}.
     * </p>
     *
     * @param userId  id de la cuenta
     * @param purpose propósito de los tokens que se invalidan
     * @param now     instante actual (del {@code Clock} del servicio)
     * @return número de tokens invalidados ahora
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("update AccountToken t set t.usedAt = :now"
            + " where t.user.id = :userId and t.purpose = :purpose"
            + " and t.usedAt is null and t.expiresAt > :now")
    int invalidateActiveTokens(@Param("userId") Long userId, @Param("purpose") AccountTokenPurpose purpose,
            @Param("now") Instant now);

    /**
     * Indica si la cuenta tiene algún token vigente (sin usar y sin caducar)
     * de ese propósito.
     *
     * <p>
     * Es lo que distingue, en el registro, una cuenta pendiente que aún puede
     * verificarse (se respeta) de una cuyo enlace ya caducó (se puede
     * sustituir: quien la creó nunca demostró que el email era suyo).
     * </p>
     *
     * @param userId  id de la cuenta
     * @param purpose propósito del token
     * @param now     instante actual (del {@code Clock} del servicio)
     * @return {@code true} si hay al menos uno vigente
     */
    @Query("select count(t) > 0 from AccountToken t"
            + " where t.user.id = :userId and t.purpose = :purpose"
            + " and t.usedAt is null and t.expiresAt > :now")
    boolean hasActiveToken(@Param("userId") Long userId, @Param("purpose") AccountTokenPurpose purpose,
            @Param("now") Instant now);

    /**
     * Cuenta los tokens emitidos a una cuenta para un propósito desde un
     * instante (incluidos los ya usados o invalidados).
     *
     * <p>
     * Sirve para limitar cuántos correos se envían a un mismo buzón (p. ej.
     * «como mucho 3 por hora») con un contador que sobrevive a los reinicios.
     * Solo cuenta lo que no haya borrado aún {@link #deleteExpired}: la
     * limpieza debe conservar los tokens de la ventana que se quiera limitar.
     * </p>
     *
     * @param userId  id de la cuenta
     * @param purpose propósito del token
     * @param since   se cuentan los emitidos después de este instante
     * @return número de tokens emitidos en la ventana
     */
    @Query("select count(t) from AccountToken t"
            + " where t.user.id = :userId and t.purpose = :purpose and t.createdAt > :since")
    long countIssuedSince(@Param("userId") Long userId, @Param("purpose") AccountTokenPurpose purpose,
            @Param("since") Instant since);

    /**
     * Indica si a la cuenta se le emitió algún token de ese propósito
     * <b>hasta</b> un instante (incluido), esté como esté.
     *
     * <p>
     * Lo usa el registro para distinguir una cuenta creada por el propio
     * registro con verificación (su primer token se emite en la misma
     * transacción que la cuenta) de una cuenta <b>heredada</b>, anterior a la
     * {@code V9}, que recibió su primer enlace mucho después de crearse: la
     * primera se puede sustituir si su enlace caduca; la segunda nunca.
     * </p>
     *
     * @param userId  id de la cuenta
     * @param purpose propósito del token
     * @param instant se buscan los emitidos con {@code created_at <= instant}
     * @return {@code true} si hay alguno
     */
    boolean existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(Long userId, AccountTokenPurpose purpose,
            Instant instant);

    /**
     * Borra los tokens caducados antes de un instante, usados o no (limpieza
     * periódica opcional: un token caducado ya no sirve para nada).
     *
     * <p>
     * Pasar un instante anterior al actual (p. ej. {@code now} menos un día)
     * conserva un tiempo los recientes, por si se cuentan con
     * {@link #countIssuedSince}. Sin índice en {@code expires_at} a propósito
     * (ver la cabecera de la {@code V9}).
     * </p>
     *
     * @param expiredBefore se borran los que caducaron antes de este instante
     * @return número de tokens borrados
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("delete from AccountToken t where t.expiresAt < :expiredBefore")
    int deleteExpired(@Param("expiredBefore") Instant expiredBefore);
}
