package com.emilio.streambox.mapper;

import java.util.List;

import com.emilio.streambox.dto.CurrentUserResponse;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Convierte la entidad {@link User} en los DTO que devuelve la API.
 *
 * <p>
 * No existe conversión inversa: las cuentas se crean en
 * {@code UserService}, que es quien cifra la contraseña y asigna el rol.
 * </p>
 */
public final class UserMapper {

    private UserMapper() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Convierte un usuario en su DTO público (sin contraseña).
     *
     * @param user entidad a convertir
     * @return DTO con los datos públicos del usuario
     */
    public static UserResponse toResponse(User user) {

        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                user.getAvatar());
    }

    /**
     * Convierte la cuenta del propio usuario en el DTO de {@code /api/users/me}.
     *
     * <p>
     * <b>Pregunta de bienvenida pendiente</b> = rol {@code USER} y
     * {@code genresOnboardedAt} nula. El rol se mira aquí, y no se resuelve
     * rellenando la fecha al crear los administradores, porque es una regla de
     * producto (decisión del autor: el ADMIN no ve la pregunta, aunque sí puede
     * elegir sus gustos en el perfil): así ninguna cuenta ADMIN la ve, la
     * creara quien la creara, sin depender de que cada camino de alta se
     * acuerde de rellenar la columna.
     * </p>
     *
     * @param user entidad a convertir
     * @return DTO con los datos públicos y los que solo ve el propio usuario
     */
    public static CurrentUserResponse toCurrentUserResponse(User user) {

        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                user.getAvatar(),
                user.getRole() == Role.USER && user.getGenresOnboardedAt() == null);
    }

    /**
     * Convierte una lista de usuarios en una lista de DTO.
     *
     * @param users usuarios a convertir
     * @return lista de DTO en el mismo orden
     */
    public static List<UserResponse> toResponseList(List<User> users) {

        return users.stream()
                .map(UserMapper::toResponse)
                .toList();
    }
}
