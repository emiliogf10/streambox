package com.emilio.streambox.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Refresh token de una sesión (tabla {@code refresh_tokens}, migración {@code V4}).
 *
 * <p>
 * El token de acceso (JWT) dura poco; con el refresh token el navegador pide
 * uno nuevo sin volver a escribir la contraseña. A diferencia del JWT, este
 * token se guarda en la base de datos para poder <strong>revocarlo</strong>
 * (cerrar sesión de verdad) y <strong>detectar su reutilización</strong>.
 * </p>
 *
 * <h2>Rotación por familias</h2>
 * <ul>
 *   <li>Cada login abre una familia nueva ({@link #familyId}) con un tope
 *       absoluto ({@link #familyExpiresAt}) que ninguna rotación alarga.</li>
 *   <li>Cada refresh revoca el token usado ({@link #revokedAt}), crea otro de la
 *       misma familia y enlaza el viejo con el nuevo ({@link #replacedBy}).</li>
 *   <li>Si vuelve a llegar un token ya revocado (fuera de la gracia de unos
 *       segundos entre pestañas), alguien lo ha copiado: se revoca la familia
 *       entera ({@code RefreshTokenRepository.revokeFamily}).</li>
 * </ul>
 *
 * <h2>Lo que garantiza la base de datos</h2>
 * <p>
 * {@link #tokenHash} es único y debe ser hexadecimal en minúsculas de 64
 * caracteres ({@code ck_refresh_tokens_token_hash_format}); {@code expiresAt >
 * createdAt}; {@code familyExpiresAt >= expiresAt}; un token con sucesor debe
 * estar revocado y no puede ser su propio sucesor. Si se viola alguna, el
 * {@code INSERT}/{@code UPDATE} falla con {@code DataIntegrityViolationException}:
 * son errores de programación, no entradas del usuario.
 * </p>
 *
 * <p>
 * Esta entidad no tiene lógica (si está activo, si está en la gracia...): eso
 * lo decide el servicio de seguridad con su {@code Clock}. {@code User} no
 * tiene una colección de tokens a propósito: lo carga el filtro JWT en cada
 * petición y no debe arrastrar sus sesiones.
 * </p>
 */
@Entity
@Table(name = "refresh_tokens", uniqueConstraints = @UniqueConstraint(
        name = "uk_refresh_tokens_token_hash", columnNames = "token_hash"))
@Getter
@Setter
public class RefreshToken {

    /**
     * Identificador generado por la base de datos.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Usuario dueño de la sesión.
     *
     * <p>
     * {@code LAZY}: buscar un token no carga el usuario; pedir solo su id
     * ({@code token.getUser().getId()}) no lanza ninguna consulta.
     * {@code @OnDelete(CASCADE)}: es la base de datos quien borra los tokens al
     * borrar el usuario ({@code fk_refresh_tokens_user}), sin cargarlos.
     * </p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    /**
     * SHA-256 del token en hexadecimal en minúsculas (64 caracteres; por
     * ejemplo {@code HexFormat.of().formatHex(digest)}).
     *
     * <p>
     * Nunca se guarda el token en claro: quien obtenga una copia de la tabla no
     * puede usar los tokens. Un hash rápido basta porque el token es aleatorio
     * y largo (no se puede adivinar por diccionario como una contraseña).
     * </p>
     */
    @Column(nullable = false, length = 64, updatable = false)
    private String tokenHash;

    /**
     * Familia de rotación: todos los tokens nacidos de un mismo login.
     */
    @Column(nullable = false, updatable = false)
    private UUID familyId;

    /**
     * Instante de emisión.
     *
     * <p>
     * Lo asigna el servicio con el mismo {@code Clock} que {@link #expiresAt}
     * (no {@code @CreationTimestamp}, que usaría el reloj del sistema): el
     * {@code CHECK} {@code expires_at > created_at} compara ambos y, con un
     * reloj fijo en los tests, mezclar relojes lo haría fallar.
     * </p>
     */
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Caducidad de este token (vida de un refresh token).
     */
    @Column(nullable = false, updatable = false)
    private Instant expiresAt;

    /**
     * Tope absoluto de la familia: copia del de la familia en cada rotación.
     * Ningún token de la familia puede caducar después ({@code CHECK}).
     */
    @Column(nullable = false, updatable = false)
    private Instant familyExpiresAt;

    /**
     * Instante en que se revocó (por rotación, logout o reutilización);
     * {@code null} mientras no se haya revocado.
     */
    private Instant revokedAt;

    /**
     * Token que sustituyó a este al rotar; {@code null} si no se ha rotado.
     *
     * <p>
     * Sirve para seguir la cadena de una familia y para distinguir un token
     * rotado (tiene sucesor) de uno revocado por logout. La clave foránea es
     * {@code ON DELETE SET NULL} ({@code @OnDelete(SET_NULL)}): la limpieza de
     * caducados puede borrar el sucesor sin borrar ni bloquear este, que sigue
     * revocado.
     * </p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private RefreshToken replacedBy;

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
        return other instanceof RefreshToken that
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
        return RefreshToken.class.hashCode();
    }

    /**
     * No incluye {@link #tokenHash} ni relaciones: evita filtrar el hash a los
     * logs y disparar cargas diferidas al imprimir la entidad.
     *
     * @return descripción breve del token
     */
    @Override
    public String toString() {
        return "RefreshToken[id=" + id + ", familyId=" + familyId + ", revokedAt=" + revokedAt + "]";
    }
}
