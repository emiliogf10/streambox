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
 * a un usuario, así como su rol y fecha de creación.
 * </p>
 *
 * @author Emilio
 */
@Entity
@Table(name = "users")
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
