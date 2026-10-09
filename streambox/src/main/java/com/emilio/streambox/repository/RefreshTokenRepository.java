package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.RefreshToken;

import jakarta.persistence.LockModeType;

/**
 * Acceso a datos de {@link RefreshToken} (tabla {@code refresh_tokens}).
 *
 * <p>
 * Las revocaciones y la limpieza son sentencias {@code UPDATE}/{@code DELETE}
 * masivas (una sola sentencia, sin cargar entidades): revocar una familia o
 * todas las sesiones de un usuario no depende de cuántos tokens haya. Llevan
 * {@code flushAutomatically} (los cambios pendientes se escriben antes) y
 * {@code clearAutomatically} (se vacía la sesión de Hibernate después, para no
 * leer entidades con un {@code revokedAt} ya desfasado). Consecuencia para quien
 * las llame: las entidades cargadas antes quedan <em>desacopladas</em>; si hay
 * que modificarlas después, se vuelven a leer.
 * </p>
 *
 * <p>
 * Transacciones: la búsqueda con bloqueo es {@code MANDATORY} (fuera de una
 * transacción el bloqueo se liberaría en el acto y no protegería nada, así que
 * se prefiere un error inmediato). Las escrituras masivas son
 * {@code @Transactional} normales: se unen a la del servicio o, si se llaman
 * sueltas (p. ej. desde una tarea programada de limpieza), abren la suya.
 * </p>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Busca un token por su hash <strong>bloqueando la fila</strong> hasta el
     * final de la transacción ({@code SELECT ... FOR NO KEY UPDATE} en
     * PostgreSQL, {@code FOR UPDATE} en H2).
     *
     * <p>
     * <strong>Por qué el bloqueo:</strong> dos refresh simultáneos con el mismo
     * token (dos pestañas que despiertan a la vez) leerían ambos
     * {@code revoked_at IS NULL} y rotarían los dos, dejando dos sucesores
     * válidos en la familia (o, si uno es un atacante con un token copiado,
     * ninguno detectaría la reutilización). Con el bloqueo, la segunda
     * transacción <em>espera</em> a que la primera confirme y entonces lee el
     * token ya revocado: el servicio decide con datos reales (gracia o
     * reutilización). Se prefiere al bloqueo optimista ({@code @Version}) porque
     * aquí la colisión es normal, no excepcional, y un reintento no aportaría
     * nada. El bloqueo dura lo que dure la transacción del refresh, que debe ser
     * corta (sin llamadas externas dentro).
     * </p>
     *
     * <p>
     * No carga el usuario (relación {@code LAZY}): {@code getUser()} lo trae con
     * una consulta por clave primaria, sin bloquear la fila de {@code users}.
     * Debe llamarse dentro de una transacción de escritura ya abierta (la del
     * servicio); si no, lanza {@code IllegalTransactionStateException}.
     * </p>
     *
     * @param tokenHash SHA-256 del token en hexadecimal en minúsculas
     * @return el token bloqueado, o vacío si no existe (token inventado o ya
     *         borrado por la limpieza)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Revoca todos los tokens aún activos de una familia (logout de esa sesión
     * o reutilización detectada). Los ya revocados conservan su
     * {@code revoked_at} original.
     *
     * <p>
     * Usa {@code idx_refresh_tokens_family_id}.
     * </p>
     *
     * @param familyId familia de rotación
     * @param now      instante de revocación (del {@code Clock} del servicio)
     * @return número de tokens revocados ahora (0 si ya estaban todos revocados)
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Revoca todos los tokens aún activos de un usuario ("cerrar sesión en
     * todos los dispositivos", cambio de contraseña, cambio de rol...).
     *
     * <p>
     * {@code t.user.id} no necesita {@code JOIN}: es la columna
     * {@code user_id}, cubierta por {@code idx_refresh_tokens_user_id}.
     * </p>
     *
     * @param userId identificador del usuario
     * @param now    instante de revocación (del {@code Clock} del servicio)
     * @return número de tokens revocados ahora
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
            + "where t.user.id = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Borra los tokens que ya no sirven para nada (limpieza periódica).
     *
     * <p>
     * Se borra un token si su familia ha caducado ({@code family_expires_at <
     * now}: ninguno de la familia puede volver a usarse) o si él mismo caducó
     * antes de {@code expiredBefore}. Que {@code expiredBefore} sea anterior a
     * {@code now} (p. ej. {@code now} menos unos días) permite conservar un
     * tiempo los tokens rotados de familias vivas, que son los que delatan una
     * reutilización. Si un token borrado era el sucesor de otro, la base de
     * datos pone a {@code NULL} el {@code replaced_by_id} de su predecesor
     * ({@code ON DELETE SET NULL}, con su índice); este sigue revocado.
     * </p>
     *
     * @param now           instante actual
     * @param expiredBefore se borran también los tokens caducados antes de este
     *                      instante aunque su familia siga viva
     * @return número de tokens borrados
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RefreshToken t "
            + "where t.familyExpiresAt < :now or t.expiresAt < :expiredBefore")
    int deleteExpired(@Param("now") Instant now, @Param("expiredBefore") Instant expiredBefore);
}
