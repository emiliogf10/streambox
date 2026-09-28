package com.emilio.streambox.security;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Representación ligera del usuario autenticado almacenada en el
 * {@link org.springframework.security.core.context.SecurityContextHolder}.
 *
 * <p>
 * Contiene únicamente los campos necesarios para identificar al usuario
 * y evaluar sus permisos durante el procesamiento de una petición HTTP.
 * Al no contener la contraseña ni colecciones JPA con carga diferida,
 * elimina dos riesgos del diseño anterior:
 * </p>
 *
 * <ul>
 *   <li>La contraseña cifrada ya no viaja en el contexto de seguridad.</li>
 *   <li>No hay colecciones Hibernate que puedan disparar una
 *       {@code LazyInitializationException} fuera de sesión.</li>
 * </ul>
 */
public record AuthenticatedUser(Long id, String email, Role role) {

    /**
     * Construye un {@code AuthenticatedUser} a partir de una entidad JPA.
     *
     * @param user entidad de usuario cargada desde la base de datos
     * @return instancia ligera con id, email y rol del usuario
     */
    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole());
    }
}
