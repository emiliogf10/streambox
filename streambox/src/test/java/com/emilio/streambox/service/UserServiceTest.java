package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link UserService#updateProfile} y de la normalización
 * del nombre de usuario, con el repositorio simulado.
 *
 * <p>
 * Cubren lo que una petición HTTP no puede provocar a voluntad: que otra
 * petición se quede el nombre entre la comprobación y el {@code UPDATE}
 * (la restricción {@code UNIQUE} salta y debe acabar en el mismo 409).
 * </p>
 */
class UserServiceTest {

    private static final Long USER_ID = 5L;

    private UserRepository userRepository;
    private UserService service;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new UserService(userRepository, mock(PasswordEncoder.class));

        user = new User();
        user.setId(USER_ID);
        user.setUsername("ana");
        user.setEmail("ana@test.com");
        user.setRole(Role.USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void unaCarreraQueChocaConLaUnicidadDa409YNoUnErrorDeIntegridadGenerico() {
        when(userRepository.existsByUsername("bea")).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(violation("23505"));

        assertThrows(UserAlreadyExistsException.class,
                () -> service.updateProfile(USER_ID, request("bea")));
    }

    @Test
    void otraViolacionAlGuardarNoSeDisfrazaDeNombreEnUso() {
        DataIntegrityViolationException notNull = violation("23502");
        when(userRepository.existsByUsername("bea")).thenReturn(false);
        when(userRepository.saveAndFlush(any())).thenThrow(notNull);

        assertSame(notNull, assertThrows(DataIntegrityViolationException.class,
                () -> service.updateProfile(USER_ID, request("bea"))));
    }

    @Test
    void elMismoNombreNoConsultaNiEscribe() {
        assertEquals("ana", service.updateProfile(USER_ID, request(" ana ")).username());

        verify(userRepository, never()).existsByUsername(anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void soloCambiaElNombre() {
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateProfile(USER_ID, request("bea"));

        assertEquals("bea", user.getUsername());
        assertEquals("ana@test.com", user.getEmail());
        assertEquals(Role.USER, user.getRole());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'  ana  '|ana",
            "' ana '|ana",
            "'​ana⁠'|ana",
            "'ana  \t lópez'|ana lópez",
            "'Ana'|Ana"
    })
    void normalizaExtremosInvisiblesYEspaciosInterioresSinTocarMayusculas(String raw, String expected) {
        assertEquals(expected, UserService.normalizeUsername(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "    ", " ab ", "​ab​" })
    void unNombreQueNormalizadoEsCortoSeRechazaSinConsultarLaBase(String raw) {
        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.updateProfile(USER_ID, request(raw)));

        assertEquals("username", error.getParameter());
        assertEquals(UpdateProfileRequest.USERNAME_SIZE_MESSAGE, error.getMessage());
        verify(userRepository, never()).findById(any());
    }

    private static UpdateProfileRequest request(String username) {
        return new UpdateProfileRequest(username, null, null, null);
    }

    /**
     * Reproduce la forma real de la excepción: Spring envuelve a Hibernate y este
     * al {@link SQLException} del driver, que es quien lleva el SQLSTATE.
     */
    private static DataIntegrityViolationException violation(String sqlState) {
        SQLException driver = new SQLException("violación de integridad", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("hibernate", driver));
    }
}
