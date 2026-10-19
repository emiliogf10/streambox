package com.emilio.streambox.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import lombok.Getter;
import lombok.Setter;

/**
 * Token de un solo uso ligado a una cuenta (tabla {@code account_tokens},
 * migración {@code V9}): hoy, el enlace de verificación del correo; mañana,
 * también el de «He olvidado mi contraseña» ({@link AccountTokenPurpose}).
 *
 * <h2>Ciclo de vida</h2>
 * <ul>
 *   <li>El servicio genera un valor aleatorio, lo envía por correo y guarda
 *       aquí solo su SHA-256 ({@link #tokenHash}) con una caducidad
 *       ({@link #expiresAt}).</li>
 *   <li>Mientras {@link #usedAt} es {@code null} y no ha caducado, está
 *       <em>vigente</em>.</li>
 *   <li>Se consume con un único {@code UPDATE} condicional
 *       ({@code AccountTokenRepository.consume}): la base de datos garantiza que
 *       solo una petición lo gasta aunque lleguen dos a la vez. Reenviar el
 *       correo invalida los vigentes de la cuenta
 *       ({@code AccountTokenRepository.invalidateActiveTokens}).</li>
 * </ul>
 *
 * <h2>Por qué una entidad (y no solo SQL nativo, como los «me gusta»)</h2>
 * <p>
 * Emitir un token es un {@code INSERT} con varias columnas que Hibernate hace
 * bien con {@code save}, y con la entidad Hibernate <strong>valida la
 * tabla</strong> al arrancar ({@code ddl-auto=validate}). Las operaciones que
 * deciden algo (consumir, invalidar) no cargan la entidad: son sentencias
 * {@code UPDATE} en JPQL, que es lo que las hace atómicas.
 * </p>
 *
 * <h2>Lo que garantiza la base de datos</h2>
 * <p>
 * {@link #tokenHash} único y con formato de SHA-256 hexadecimal en minúsculas;
 * {@link #purpose} dentro de la lista del {@code CHECK}; {@code expiresAt >
 * createdAt}; y {@code usedAt < expiresAt} (un token caducado no se gasta).
 * Una violación lanza {@code DataIntegrityViolationException}: son errores de
 * programación, no entradas del usuario.
 * </p>
 *
 * <p>
 * {@code User} no tiene una colección de tokens a propósito (como con
 * {@link RefreshToken}): el filtro JWT carga el usuario en cada petición y no
 * debe arrastrarlos.
 * </p>
 */
@Entity
@Table(name = "account_tokens", uniqueConstraints = @UniqueConstraint(
        name = "uk_account_tokens_token_hash", columnNames = "token_hash"))
@Getter
@Setter
public class AccountToken {

    /**
     * Identificador generado por la base de datos.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Cuenta a la que pertenece el token.
     *
     * <p>
     * {@code LAZY}: buscar un token no carga el usuario. {@code @OnDelete(CASCADE)}:
     * es la base de datos ({@code fk_account_tokens_user}) quien borra los tokens
     * al borrar la cuenta, sin que Hibernate tenga que cargarlos.
     * </p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /**
     * Para qué sirve el token. Un token solo vale para su propósito.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private AccountTokenPurpose purpose;

    /**
     * SHA-256 del token en hexadecimal en minúsculas (64 caracteres).
     *
     * <p>
     * Nunca se guarda el token en claro: quien obtenga una copia de la tabla no
     * puede verificar cuentas ajenas. Basta un hash rápido (no BCrypt) porque
     * el token es aleatorio y largo, no una contraseña adivinable.
     * </p>
     */
    @Column(nullable = false, length = 64, updatable = false)
    private String tokenHash;

    /**
     * Instante de emisión.
     *
     * <p>
     * Lo asigna el servicio con el mismo {@code Clock} que {@link #expiresAt}
     * (no {@code @CreationTimestamp}): el {@code CHECK} {@code expires_at >
     * created_at} compara ambos y, con un reloj fijo en los tests, mezclar
     * relojes lo haría fallar.
     * </p>
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Caducidad: desde este instante (incluido) el token ya no sirve.
     */
    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * Instante en que se consumió o se invalidó; {@code null} mientras se puede
     * usar. Lo escriben las sentencias del repositorio, no el servicio a mano.
     */
    private Instant usedAt;

    /**
     * Dos tokens son iguales si tienen el mismo identificador (mismo criterio
     * que {@link Movie#equals(Object)}).
     *
     * @param other objeto con el que se compara
     * @return {@code true} si ambos representan la misma fila
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AccountToken that
                && id != null
                && id.equals(that.getId());
    }

    /**
     * Devuelve un valor constante por clase (ver {@link Movie#hashCode()}).
     *
     * @return código hash constante de la clase
     */
    @Override
    public int hashCode() {
        return AccountToken.class.hashCode();
    }

    /**
     * No incluye {@link #tokenHash} ni el usuario: evita llevar el hash a los
     * logs y disparar cargas diferidas al imprimir la entidad.
     *
     * @return descripción breve del token
     */
    @Override
    public String toString() {
        return "AccountToken[id=" + id + ", purpose=" + purpose + ", expiresAt=" + expiresAt
                + ", usedAt=" + usedAt + "]";
    }
}
