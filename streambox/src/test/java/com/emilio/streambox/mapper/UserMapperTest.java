package com.emilio.streambox.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Tests unitarios de {@link UserMapper}: copia todos los datos públicos,
 * incluido el avatar (también cuando es {@code null}).
 */
class UserMapperTest {

    @Test
    void copiaTodosLosCamposPublicosConAvatar() {
        Instant createdAt = Instant.parse("2026-10-17T10:00:00Z");
        User user = user(7L, "ana", "cat");
        user.setCreatedAt(createdAt);

        UserResponse response = UserMapper.toResponse(user);

        assertEquals(new UserResponse(7L, "ana", "ana@test.com", Role.USER, createdAt, "cat"), response);
    }

    @Test
    void unAvatarNullSeCopiaComoNull() {
        assertNull(UserMapper.toResponse(user(1L, "bea", null)).avatar());
    }

    /**
     * Una clave que ya no está en la lista blanca (se retiró un avatar) se
     * devuelve tal cual: el cliente decide pintar la inicial.
     */
    @Test
    void unaClaveRetiradaSeDevuelveTalCual() {
        assertEquals("avatar-antiguo", UserMapper.toResponse(user(1L, "bea", "avatar-antiguo")).avatar());
    }

    @Test
    void laListaConservaElOrden() {
        List<UserResponse> responses = UserMapper.toResponseList(List.of(user(2L, "b", "sun"), user(1L, "a", null)));

        assertEquals(List.of(2L, 1L), responses.stream().map(UserResponse::id).toList());
        assertEquals("sun", responses.get(0).avatar());
        assertNull(responses.get(1).avatar());
    }

    private static User user(Long id, String username, String avatar) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setPassword("$2a$10$hash-que-nunca-debe-salir");
        user.setRole(Role.USER);
        user.setAvatar(avatar);
        return user;
    }
}
