package com.emilio.streambox.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;

import lombok.Getter;
import lombok.Setter;

/**
 * Entidad que representa un usuario registrado en Streambox.
 *
 * <p>
 * Esta clase se mapea mediante JPA con la tabla {@code users}
 * de la base de datos.
 * </p>
 *
 * <p>
 * Contiene la información necesaria para identificar y autenticar
 * a un usuario, así como su rol, su icono de perfil, si su email está
 * verificado y su fecha de creación.
 * </p>
 *
 * <p>
 * <strong>Por qué {@code @DynamicUpdate}.</strong> Sin ella, Hibernate genera
 * un único {@code UPDATE} que reescribe <em>todas</em> las columnas con los
 * valores que la entidad tenía al leerse. Si dos peticiones leen la misma fila
 * sin bloquearla y cada una cambia una cosa distinta, la que escribe la
 * última devuelve a su valor antiguo lo que cambió la otra («actualización
 * perdida»). Ocurría de verdad: un {@code PATCH /api/users/me} (cambio de
 * avatar o de nombre) que coincidía con un {@code PUT /api/users/me/password}
 * ya confirmado volvía a escribir el hash antiguo, de modo que la contraseña
 * nueva se perdía y la antigua (quizá filtrada) volvía a valer. Con
 * {@code @DynamicUpdate} el {@code UPDATE} solo incluye las columnas
 * modificadas, así que cambiar el perfil nunca toca {@code password} y cambiar
 * la contraseña nunca toca el perfil. Lo prueba
 * {@code PostgresIntegrityRaceTranslationQaIntegrationTest}.
 * </p>
 *
 * <p>
 * No sustituye a un bloqueo cuando la escritura <em>depende</em> del valor
 * leído: {@code PasswordChangeService} sigue bloqueando la fila
 * ({@code PESSIMISTIC_WRITE}) porque comprueba la contraseña actual antes de
 * cambiarla. El coste es que Hibernate genera el SQL del {@code UPDATE} en
 * cada escritura en vez de reutilizar uno precalculado; con lo poco que se
 * modifica una cuenta, es irrelevante.
 * </p>
 *
 * @author Emilio
 */
@Entity
@Table(name = "users")
@DynamicUpdate
@Getter
@Setter
public class User {

    /**
     * Identificador único del usuario.
     *
     * <p>
     * Su valor es generado automáticamente por la base de datos.
     * </p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nombre de usuario.
     *
     * <p>
     * Debe ser único y no puede ser {@code null}.
     * </p>
     */
    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /**
     * Dirección de correo electrónico del usuario.
     *
     * <p>
     * Debe ser única y no puede ser {@code null}.
     * </p>
     */
    @Column(nullable = false, unique = true, length = 100)
    private String email;

    /**
     * Contraseña del usuario almacenada de forma cifrada.
     *
     * <p>
     * La contraseña no debe almacenarse nunca en texto plano.
     * </p>
     */
    @Column(nullable = false)
    private String password;

    /**
     * Rol que determina los permisos del usuario dentro de la aplicación.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /**
     * Clave del icono de perfil elegido (por ejemplo {@code "rocket"}), o
     * {@code null} si el usuario no ha elegido ninguno y la interfaz muestra su
     * inicial.
     *
     * <p>
     * Es la clave de uno de los avatares predefinidos, no una imagen ni una URL:
     * el frontend la traduce a un icono. La lista de claves válidas la valida el
     * backend y no la base de datos, para que añadir un avatar no exija una
     * migración; la base solo exige el formato (de 1 a 32 caracteres de
     * {@code [a-z0-9-]}, restricción {@code ck_users_avatar} de la {@code V7}).
     * </p>
     */
    @Column(name = "avatar", length = 32)
    private String avatar;

    /**
     * Instante desde el que el email de la cuenta cuenta como verificado, o
     * {@code null} si la cuenta está <strong>pendiente</strong> (se registró y
     * aún no ha pulsado el enlace del correo).
     *
     * <p>
     * Columna {@code email_verified_at} de la {@code V9}. De las cuentas que ya
     * existían antes de esa migración, solo las ADMIN quedaron verificadas (con
     * el instante en que se aplicó); las USER quedaron pendientes por decisión
     * del autor, para probar el flujo con cuentas reales. No tiene valor por defecto ni lo asigna Hibernate: una
     * cuenta nueva nace pendiente salvo que el código la marque a propósito
     * (el ADMIN de {@code AdminAccountInitializer}). Para marcarla tras el clic
     * en el enlace se usa {@code UserRepository.markEmailVerified}, que es
     * idempotente y no pisa la fecha de una verificación anterior.
     * </p>
     */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /**
     * Instante en el que se creó el registro.
     *
     * <p>
     * Lo asigna Hibernate automáticamente al insertar ({@code @CreationTimestamp})
     * y no se modifica después. Se guarda como {@link Instant} (un momento
     * absoluto, independiente de la zona horaria del servidor) en una columna
     * {@code TIMESTAMP WITH TIME ZONE}.
     * </p>
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Películas favoritas añadidas por el usuario a su lista.
     *
     * <p>
     * Un usuario puede tener muchas películas favoritas y una película
     * puede estar en las listas de favoritos de muchos usuarios.
     * </p>
     */
    @ManyToMany
    @JoinTable(
            name = "user_favorite_movies",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "movie_id"))
    private Set<Movie> favoriteMovies = new HashSet<>();

    /**
     * Indica si el email de la cuenta está verificado.
     *
     * <p>
     * Es solo una lectura de {@link #emailVerifiedAt} (no hay columna booleana:
     * la fecha ya lo dice y dos columnas podrían contradecirse). Hibernate no lo
     * confunde con un atributo porque la entidad usa acceso por campo (el
     * {@code @Id} está en el campo).
     * </p>
     *
     * @return {@code true} si la cuenta ya no está pendiente de verificar
     */
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    /**
     * Dos usuarios son iguales si tienen el mismo identificador.
     *
     * <p>
     * La igualdad se basa solo en el {@code id} asignado por la base de
     * datos, como recomienda Hibernate: así una entidad sigue siendo la misma
     * aunque se cargue en sesiones distintas, y una entidad aún no guardada
     * (sin {@code id}) solo es igual a sí misma. Se compara con
     * {@code instanceof} y con el getter para funcionar también con los
     * proxies de carga diferida.
     * </p>
     *
     * @param other objeto con el que se compara
     * @return {@code true} si ambos representan la misma fila
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof User that
                && id != null
                && id.equals(that.getId());
    }

    /**
     * Devuelve un valor constante por clase.
     *
     * <p>
     * El {@code id} cambia al guardar la entidad por primera vez, y un
     * {@code hashCode} que cambiara rompería las colecciones {@code Set}
     * que la contienen. Un valor constante es correcto (aunque agrupa todas
     * las instancias en un mismo bucket, algo irrelevante con las pocas
     * entidades que maneja una colección aquí).
     * </p>
     *
     * @return código hash constante de la clase
     */
    @Override
    public int hashCode() {
        return User.class.hashCode();
    }
}
