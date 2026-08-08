package com.emilio.streambox.mapper;

import java.util.List;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.User;

/**
 * Clase encargada de realizar las conversiones entre las entidades
 * {@link User} y los DTOs utilizados por la API.
 *
 * <p>Esta clase utiliza métodos estáticos y no necesita ser instanciada.
 * Su objetivo es mantener separada la representación interna de las
 * entidades JPA de los objetos utilizados para recibir y devolver
 * información mediante la API REST.</p>
 */
public final class UserMapper {

    /**
     * Constructor privado para evitar la instanciación de la clase.
     *
     * <p>Todos los métodos de esta clase son estáticos, por lo que no
     * es necesario crear objetos {@code UserMapper}.</p>
     */
    private UserMapper() {
        // Evita instanciar la clase
    }

    /**
     * Convierte una petición de creación de usuario en una entidad
     * {@link User}.
     *
     * <p>La entidad resultante contiene los datos proporcionados por
     * el cliente. Los valores gestionados por el servidor, como el rol
     * y la fecha de creación, se establecen posteriormente en la capa
     * de servicio.</p>
     *
     * @param request DTO que contiene los datos enviados por el cliente
     * @return entidad {@link User} construida a partir de la petición
     */
    public static User toEntity(CreateUserRequest request) {

        User user = new User();

        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());

        return user;
    }

    /**
     * Convierte una entidad {@link User} en un {@link UserResponse}
     * para devolverla mediante la API REST.
     *
     * <p>La contraseña no se copia al DTO de respuesta, evitando que
     * información sensible sea expuesta al cliente.</p>
     *
     * @param user entidad de usuario que se desea convertir
     * @return DTO con la información del usuario que puede exponerse
     *         mediante la API
     */
    public static UserResponse toResponse(User user) {

        UserResponse response = new UserResponse();

        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setCreatedAt(user.getCreatedAt());

        return response;
    }

    /**
     * Convierte una lista de entidades {@link User} en una lista de
     * {@link UserResponse}.
     *
     * <p>La conversión de cada elemento se realiza mediante
     * {@link #toResponse(User)}.</p>
     *
     * @param users lista de entidades de usuario que se desea convertir
     * @return lista de DTOs {@link UserResponse}
     */
    public static List<UserResponse> toResponseList(List<User> users) {

        return users.stream()
                .map(UserMapper::toResponse)
                .toList();
    }
}
