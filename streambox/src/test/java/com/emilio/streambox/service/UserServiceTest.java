package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

/**
 * Tests unitarios de {@link UserService#updateProfile}, de la carrera en
 * {@link UserService#registerUser} y de la normalización del nombre de
 * usuario, con el repositorio simulado.
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

    // ------------------------------------------------------------------
    // Avatar
    // ------------------------------------------------------------------

    @Test
    void soloAvatarLoGuardaSinMirarElNombre() {
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("cat", service.updateProfile(USER_ID, avatarRequest(null, text("cat"))).avatar());

        assertEquals("cat", user.getAvatar());
        assertEquals("ana", user.getUsername());
        verify(userRepository, never()).existsByUsername(anyString());
    }

    @Test
    void avatarNullLoQuita() {
        user.setAvatar("ghost");
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertNull(service.updateProfile(USER_ID, avatarRequest(null, NullNode.getInstance())).avatar());

        assertNull(user.getAvatar());
    }

    @Test
    void sinAvatarElAvatarSeConserva() {
        user.setAvatar("moon");
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("moon", service.updateProfile(USER_ID, request("bea")).avatar());

        assertEquals("moon", user.getAvatar());
    }

    @Test
    void elMismoNombreYElMismoAvatarNoEscriben() {
        user.setAvatar("sun");

        service.updateProfile(USER_ID, avatarRequest("ana", text("sun")));
        service.updateProfile(USER_ID, avatarRequest(null, text("sun")));

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void elMismoNombreConAvatarNuevoSoloEscribeElAvatar() {
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateProfile(USER_ID, avatarRequest(" ana ", text("star")));

        assertEquals("star", user.getAvatar());
        assertEquals("ana", user.getUsername());
        verify(userRepository, never()).existsByUsername(anyString());
    }

    /** Ni nombre ni avatar: 400 sin consultar la base de datos (un 200 vacío engañaría al cliente). */
    @Test
    void unaPeticionSinCambiosSeRechazaSinConsultarLaBase() {
        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.updateProfile(USER_ID, avatarRequest(null, null)));

        assertEquals("username", error.getParameter());
        assertEquals(UpdateProfileRequest.NO_CHANGES_MESSAGE, error.getMessage());
        verify(userRepository, never()).findById(any());
    }

    /** Con nombre y avatar, una violación de unicidad sigue siendo la del nombre. */
    @Test
    void conAvatarUnaCarreraPorElNombreSigueDando409() {
        when(userRepository.saveAndFlush(any())).thenThrow(uniqueViolation("uk_users_username"));

        assertThrows(UserAlreadyExistsException.class,
                () -> service.updateProfile(USER_ID, avatarRequest("bea", text("cat"))));
    }

    /** El CHECK de formato del avatar (si el validador fallara) no se disfraza de nombre en uso. */
    @Test
    void unaViolacionDelCheckDelAvatarSeRelanzaTalCual() {
        DataIntegrityViolationException check = violation("23514");
        when(userRepository.saveAndFlush(any())).thenThrow(check);

        assertSame(check, assertThrows(DataIntegrityViolationException.class,
                () -> service.updateProfile(USER_ID, avatarRequest(null, text("cat")))));
    }

    // ------------------------------------------------------------------
    // Normalización del email del registro
    // ------------------------------------------------------------------

    @Test
    void elEmailSeNormalizaSinEspaciosYEnMinusculas() {
        assertEquals("ana.lopez@test.com", UserService.normalizeEmail("  Ana.Lopez@TEST.com "));
    }

    /**
     * Pasar a minúsculas puede alargar el texto ({@code İ}, U+0130, son dos
     * caracteres en minúscula): un email de 100 caracteres, que pasa
     * {@code @Size}, quedaría con 160 y fallaría en la base de datos. El
     * servicio lo rechaza con el mismo campo y mensaje que {@code @Size} y sin
     * llegar a consultar ni guardar nada.
     */
    @Test
    void unEmailQueCreceAlPasarloAMinusculasSeRechazaSinTocarLaBase() {
        String email = "İ".repeat(60) + "@" + "d".repeat(35) + ".com";
        assertEquals(100, email.length());

        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("ana_nueva");
        request.setEmail(email);
        request.setPassword("un paseo por el retiro en otoño");

        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.registerUser(request));

        assertEquals("email", error.getParameter());
        assertEquals("El correo electrónico no puede tener más de 100 caracteres", error.getMessage());
        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    // Carrera en el registro (la restricción UNIQUE como respaldo)
    // ------------------------------------------------------------------

    /**
     * Dos registros simultáneos pasan los dos {@code existsBy...} y el segundo
     * choca con el {@code UNIQUE} del email. Antes la violación llegaba sin
     * traducir a {@code GlobalExceptionHandler} (409 genérico; hoy sería un
     * 500): debe ser el mismo {@link UserAlreadyExistsException} que da la
     * comprobación previa, con el mensaje del email.
     */
    @Test
    void unaCarreraEnElRegistroPorElEmailDaElMismo409QueLaComprobacionPrevia() {
        when(userRepository.saveAndFlush(any())).thenThrow(uniqueViolation("uk_users_email"));

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.registerUser(registration()));

        assertEquals("El correo electrónico ya está en uso", error.getMessage());
    }

    /** Igual con el nombre de usuario; en H2 el nombre llega con esquema y sufijo. */
    @Test
    void unaCarreraEnElRegistroPorElNombreDaElMensajeDelNombre() {
        when(userRepository.saveAndFlush(any()))
                .thenThrow(uniqueViolation("PUBLIC.UK_USERS_USERNAME_INDEX_4 ON PUBLIC.USERS(USERNAME)"));

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.registerUser(registration()));

        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
    }

    /** Si el motor no dice qué restricción falló, el mensaje cubre las dos (y sigue siendo cierto). */
    @Test
    void unaCarreraEnElRegistroSinNombreDeRestriccionDaUnMensajeQueCubreLasDos() {
        when(userRepository.saveAndFlush(any())).thenThrow(violation("23505"));

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.registerUser(registration()));

        assertEquals("El nombre de usuario o el correo electrónico ya están en uso", error.getMessage());
    }

    /** Lo que no es unicidad no se disfraza de "ya está en uso": se relanza (500 y log). */
    @Test
    void otraViolacionEnElRegistroSeRelanzaTalCual() {
        DataIntegrityViolationException check = violation("23514");
        when(userRepository.saveAndFlush(any())).thenThrow(check);

        assertSame(check, assertThrows(DataIntegrityViolationException.class,
                () -> service.registerUser(registration())));
    }

    private static CreateUserRequest registration() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("ana_nueva");
        request.setEmail("ana.nueva@test.com");
        request.setPassword("un paseo por el retiro en otoño");
        return request;
    }

    /** Violación de unicidad con el nombre de la restricción, como la entrega Hibernate. */
    private static DataIntegrityViolationException uniqueViolation(String constraintName) {
        SQLException driver = new SQLException("violación de integridad", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraintName));
    }

    private static UpdateProfileRequest request(String username) {
        return new UpdateProfileRequest(username, null, null, null, null);
    }

    /**
     * Petición con avatar. {@code avatar} es el valor JSON tal como lo entrega
     * Jackson: {@code null} de Java = campo ausente, {@code NullNode} =
     * {@code "avatar": null}.
     */
    private static UpdateProfileRequest avatarRequest(String username, JsonNode avatar) {
        return new UpdateProfileRequest(username, avatar, null, null, null);
    }

    private static JsonNode text(String value) {
        return JsonNodeFactory.instance.stringNode(value);
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
