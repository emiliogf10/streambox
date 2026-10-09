package com.emilio.streambox.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.ChangePasswordRequest;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.security.refresh.SessionTokens;
import com.emilio.streambox.service.PasswordChangeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Cambio de contraseña del usuario autenticado
 * ({@code PUT /api/users/me/password}).
 *
 * <p>
 * Está aparte de {@code UserController} porque, además de la cuenta, toca la
 * sesión: entrega cookies nuevas, como el login. La cuenta sale del token
 * ({@link AuthenticatedUser}), nunca de la URL (sin IDOR). La regla de
 * autorización está en {@code SecurityConfig}
 * ({@code SecurityConfig.PASSWORD_PATH}); el 403 {@code CSRF_REJECTED} de las
 * peticiones por cookie sin {@code X-Requested-With} lo da
 * {@code JwtAuthenticationFilter} y lo documenta
 * {@code CookieAuthOperationCustomizer}.
 * </p>
 */
@RestController
@RequestMapping("/api/users/me")
public class PasswordController {

    private final PasswordChangeService passwordChangeService;

    private final AuthCookieService authCookieService;

    /**
     * Crea el controlador.
     *
     * @param passwordChangeService servicio que cambia la contraseña y las sesiones
     * @param authCookieService     construye las cookies de la sesión nueva
     */
    public PasswordController(PasswordChangeService passwordChangeService, AuthCookieService authCookieService) {
        this.passwordChangeService = passwordChangeService;
        this.authCookieService = authCookieService;
    }

    /**
     * Cambia la contraseña, cierra las demás sesiones y renueva la actual.
     *
     * @param principal usuario autenticado de la petición
     * @param request   contraseña actual y nueva
     * @param response  respuesta en la que se añaden los {@code Set-Cookie} de la sesión nueva
     */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cambia mi contraseña", description = "Cambia la contraseña de la cuenta autenticada "
            + "comprobando antes la actual. Cierra todas las demás sesiones de la cuenta (sus refresh tokens "
            + "quedan revocados) y mantiene la actual: la respuesta trae en Set-Cookie un streambox_token y un "
            + "streambox_refresh nuevos (una sesión nueva, así que tampoco sirve una copia anterior del refresh "
            + "token de esta). Los JWT de acceso ya emitidos a otras sesiones no se pueden revocar: siguen "
            + "valiendo hasta caducar (como mucho 15 minutos), pero ya no se pueden renovar. Si la contraseña "
            + "actual es incorrecta no se cambia nada.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña cambiada; sin cuerpo, con las cookies "
                    + "streambox_token y streambox_refresh nuevas en Set-Cookie"),
            @ApiResponse(responseCode = "400", description = "Código CURRENT_PASSWORD_INCORRECT: la contraseña "
                    + "actual no es correcta (mensaje en validationErrors.currentPassword; gasta uno de los 5 "
                    + "intentos). Código VALIDATION_ERROR: datos no válidos, con un mensaje por campo en "
                    + "validationErrors: currentPassword vacía o de más de 1024 caracteres; newPassword que no "
                    + "cumple la política (12 a 64 caracteres, como mucho 72 bytes, no común, sin el nombre de "
                    + "usuario ni la parte del email anterior a la @) o igual a la actual. Los errores de la "
                    + "política no gastan intento"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "429", description = "Código RATE_LIMIT_EXCEEDED: demasiados intentos "
                    + "con la contraseña actual incorrecta (5 cada 15 minutos por cuenta); la cabecera "
                    + "Retry-After indica los segundos que hay que esperar. No bloquea el login")
    })
    public void changePassword(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletResponse response) {

        SessionTokens tokens = passwordChangeService.changePassword(
                principal.id(), request.currentPassword(), request.newPassword());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.sessionCookie(tokens.accessToken()));
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.refreshCookie(tokens.refreshToken()));
    }
}
