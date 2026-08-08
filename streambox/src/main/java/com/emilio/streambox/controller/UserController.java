package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.mapper.UserMapper;
import com.emilio.streambox.service.UserService;

import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar las operaciones relacionadas
 * con los usuarios de Streambox.
 *
 * <p>Expone los endpoints disponibles bajo la ruta
 * {@code /api/users} y delega la lógica de negocio en
 * {@link UserService}.</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * Crea una instancia del controlador de usuarios.
     *
     * @param userService servicio encargado de gestionar la lógica
     *                    relacionada con los usuarios
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Obtiene todos los usuarios registrados.
     *
     * <p>Las entidades {@link User} obtenidas desde el servicio se
     * convierten en {@link UserResponse} antes de devolverlas al cliente,
     * evitando exponer directamente las entidades JPA.</p>
     *
     * @return lista de usuarios representados mediante {@link UserResponse}
     */
    @GetMapping
    public List<UserResponse> getUsers() {
        return UserMapper.toResponseList(userService.getAllUsers());
    }

    /**
     * Crea un nuevo usuario.
     *
     * <p>El cuerpo de la petición se valida mediante {@link Valid}.
     * Posteriormente, el {@link UserMapper} convierte el DTO recibido
     * en una entidad {@link User}, que es procesada y almacenada por
     * {@link UserService}.</p>
     *
     * @param request datos necesarios para crear el usuario
     * @return información del usuario creado
     */
    @PostMapping
    public UserResponse createUser(
            @Valid @RequestBody CreateUserRequest request) {

        User user = UserMapper.toEntity(request);

        User savedUser = userService.saveUser(user);

        return UserMapper.toResponse(savedUser);
    }
}