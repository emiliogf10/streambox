package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.RateLimitProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;

/**
 * Tests unitarios de {@link UserService#updateProfile} y de la normalización
 * del nombre de usuario y del email, con el repositorio simulado. La carrera
 * del registro está en {@code RegistrationServiceTest}.
 *
 * <p>
 * Cubren lo que una petición HTTP no puede provocar a voluntad: que otra
 * petición se quede el nombre entre la comprobación y el {@code UPDATE}
 * (la restricción {@code UNIQUE} salta y debe acabar en el mismo 409), o
 * que la cuenta pendiente dueña del nombre cambie mientras se espera su
 * bloqueo.
 * </p>
 */
class UserServiceTest {

    private static final Long USER_ID = 5L;

    private UserRepository userRepository;
    private UserService service;
    private UsernameReservations reservations;
    private EmailVerificationService emailVerificationService;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        RateLimitProperties rateLimit = mock(RateLimitProperties.class);
        when(rateLimit.maxKeys()).thenReturn(1000);
        reservations = new UsernameReservations(new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofHours(1), false,
                new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ZERO)), rateLimit, Clock.systemUTC());
        emailVerificationService = mock(EmailVerificationService.class);
        // Por defecto, el bloqueo devuelve la misma cuenta (nadie la cambió entre medias).
        when(emailVerificationService.lockForUpdate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));
        service = new UserService(userRepository, reservations, emailVerificationService);

        user = new User();
        user.setId(USER_ID);
        user.setUsername("ana");
        user.setEmail("ana@test.com");
        user.setRole(Role.USER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void unaCarreraQueChocaConLaUnicidadDa409YNoUnErrorDeIntegridadGenerico() {
        when(userRepository.findByUsername("bea")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenThrow(violation("23505"));

        assertThrows(UserAlreadyExistsException.class,
                () -> service.updateProfile(USER_ID, request("bea")));
    }

    @Test
    void otraViolacionAlGuardarNoSeDisfrazaDeNombreEnUso() {
        DataIntegrityViolationException notNull = violation("23502");
        when(userRepository.findByUsername("bea")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenThrow(notNull);

        assertSame(notNull, assertThrows(DataIntegrityViolationException.class,
                () -> service.updateProfile(USER_ID, request("bea"))));
    }

    @Test
    void elMismoNombreNoConsultaNiEscribe() {
        assertEquals("ana", service.updateProfile(USER_ID, request(" ana ")).username());

        verify(userRepository, never()).findByUsername(anyString());
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

    /**
     * Oráculo del nombre por «Editar perfil»: registrarse con el email de otro
     * y el nombre {@code bea} reserva {@code bea} si ese email ya tenía cuenta;
     * cambiarse después el nombre a {@code bea} con una cuenta propia debe dar
     * el mismo 409 que si la cuenta pendiente se hubiera creado.
     */
    @Test
    void unNombreReservadoPorOtroEmailDa409() {
        reservations.reserve("bea", "otra@test.com");

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.updateProfile(USER_ID, request("bea")));

        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    /** La cuenta del email de la reserva sí puede usar el nombre: la reserva no la estorba. */
    @Test
    void elDuenoDelEmailDeLaReservaSiPuedeUsarElNombre() {
        reservations.reserve("bea", "ana@test.com");
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("bea", service.updateProfile(USER_ID, request("bea")).username());
    }

    // ------------------------------------------------------------------
    // QA-2 / MEDIA-1: el nombre de una pendiente que ya no lo conserva se libera
    // ------------------------------------------------------------------

    /**
     * El nombre lo tiene una pendiente que ya no lo conserva (la creó el
     * registro hace 24 h o más): se borra con la misma regla que el registro
     * ({@code deleteIfUsernameReleased}, la regla por nombre) y el cambio
     * sale bien. Las dos filas se bloquean de menor a mayor id (la pendiente,
     * id 3, antes que la propia, id 5) y el borrado va después de los dos
     * bloqueos.
     */
    @Test
    void elNombreDeUnaPendienteQueYaNoLoConservaSeLiberaBloqueandoEnOrdenDeId() {
        User stale = pending(3L, "bea");
        when(userRepository.findByUsername("bea")).thenReturn(Optional.of(stale));
        when(emailVerificationService.deleteIfUsernameReleased(stale)).thenReturn(true);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("bea", service.updateProfile(USER_ID, request("bea")).username());

        InOrder order = inOrder(emailVerificationService, userRepository);
        order.verify(emailVerificationService).lockForUpdate(stale);
        order.verify(emailVerificationService).lockForUpdate(user);
        order.verify(emailVerificationService).deleteIfUsernameReleased(stale);
        order.verify(userRepository).saveAndFlush(user);
    }

    /** Con la propia cuenta de id menor, se bloquea primero la propia. */
    @Test
    void siLaPropiaCuentaTieneElIdMenorSeBloqueaPrimero() {
        User stale = pending(9L, "bea");
        when(userRepository.findByUsername("bea")).thenReturn(Optional.of(stale));
        when(emailVerificationService.deleteIfUsernameReleased(stale)).thenReturn(true);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateProfile(USER_ID, request("bea"));

        InOrder order = inOrder(emailVerificationService);
        order.verify(emailVerificationService).lockForUpdate(user);
        order.verify(emailVerificationService).lockForUpdate(stale);
    }

    /**
     * Una pendiente que conserva su nombre (creada hace menos de 24 h, o
     * heredada) da el 409 de siempre y no se guarda nada.
     */
    @Test
    void elNombreDeUnaPendienteQueLoConservaDa409() {
        User legacy = pending(3L, "bea");
        when(userRepository.findByUsername("bea")).thenReturn(Optional.of(legacy));
        when(emailVerificationService.deleteIfUsernameReleased(legacy)).thenReturn(false);

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.updateProfile(USER_ID, request("bea")));

        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
        verify(userRepository, never()).saveAndFlush(any());
    }

    /** El nombre de una cuenta confirmada da 409 sin bloquear ni intentar borrar nada. */
    @Test
    void elNombreDeUnaCuentaConfirmadaDa409SinBloquear() {
        User confirmed = pending(3L, "bea");
        confirmed.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByUsername("bea")).thenReturn(Optional.of(confirmed));

        assertThrows(UserAlreadyExistsException.class, () -> service.updateProfile(USER_ID, request("bea")));

        verify(emailVerificationService, never()).lockForUpdate(any());
        verify(emailVerificationService, never()).deleteIfUsernameReleased(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    /**
     * Si la pendiente desaparece (otro registro la sustituyó) o cambia de
     * nombre mientras se espera su bloqueo, el nombre se da por libre: no se
     * intenta borrar nada y el {@code UNIQUE} queda de respaldo. Se guarda la
     * cuenta releída bajo el bloqueo, no la leída antes.
     */
    @Test
    void siLaPendienteDesapareceAlBloquearlaElNombreSeDaPorLibre() {
        User vanished = pending(3L, "bea");
        User lockedUser = new User();
        lockedUser.setId(USER_ID);
        lockedUser.setUsername("ana");
        lockedUser.setEmail("ana@test.com");
        lockedUser.setRole(Role.USER);
        when(userRepository.findByUsername("bea")).thenReturn(Optional.of(vanished));
        when(emailVerificationService.lockForUpdate(vanished)).thenReturn(Optional.empty());
        when(emailVerificationService.lockForUpdate(user)).thenReturn(Optional.of(lockedUser));
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("bea", service.updateProfile(USER_ID, request("bea")).username());

        verify(emailVerificationService, never()).deleteIfUsernameReleased(any());
        // User compara por id: se comprueba la instancia, no la igualdad.
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertSame(lockedUser, saved.getValue());
        assertEquals("ana", user.getUsername(), "La instancia leída antes del bloqueo no se toca");
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
        verify(userRepository, never()).findByUsername(anyString());
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
        verify(userRepository, never()).findByUsername(anyString());
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

    /** Violación de unicidad con el nombre de la restricción, como la entrega Hibernate. */
    private static DataIntegrityViolationException uniqueViolation(String constraintName) {
        SQLException driver = new SQLException("violación de integridad", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraintName));
    }

    /** Cuenta pendiente (sin verificar) dueña de un nombre. */
    private static User pending(Long id, String username) {
        User pending = new User();
        pending.setId(id);
        pending.setUsername(username);
        pending.setEmail(username + "@otro.test");
        pending.setRole(Role.USER);
        return pending;
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
