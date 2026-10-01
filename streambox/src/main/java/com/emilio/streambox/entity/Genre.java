package com.emilio.streambox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Entidad que representa un género cinematográfico dentro de Streambox.
 *
 * <p>
 * Los géneros permiten clasificar las películas y posteriormente
 * facilitar su búsqueda y filtrado.
 * </p>
 */
@Entity
@Table(name = "genres")
@Getter
@Setter
public class Genre {

    /**
     * Identificador único del género.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nombre del género cinematográfico.
     *
     * <p>
     * El nombre es obligatorio y no puede repetirse dentro
     * de la base de datos.
     * </p>
     */
    @Column(nullable = false, unique = true, length = 50)
    private String name;

    /**
     * Dos géneros son iguales si tienen el mismo identificador.
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
        return other instanceof Genre that
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
        return Genre.class.hashCode();
    }
}
