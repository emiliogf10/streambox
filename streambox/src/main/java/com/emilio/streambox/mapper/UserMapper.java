package com.emilio.streambox.mapper;

import java.util.List;

import com.emilio.streambox.dto.UserResponse;
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
                user.getCreatedAt());
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
