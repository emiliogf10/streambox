package com.emilio.streambox.entity;

import java.time.LocalDateTime;
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
     * Fecha y hora en la que se creó el usuario.
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

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
}
