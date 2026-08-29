package com.emilio.streambox.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.dto.LoginResponse;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.AuthenticationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar la autenticación de los usuarios.
 *
 * <p>
 * Expone los endpoints relacionados con el inicio de sesión bajo
 * la ruta {@code /api/auth}.
 * </p>
 *
 * <p>
 * El proceso de autenticación se delega en {@link AuthenticationService}.
 * Una vez autenticado el usuario, {@link JwtService} genera un token JWT
 * que se devuelve al cliente.
 * </p>
 *
 * <p>
 * Los endpoints de este controlador son públicos y no requieren
 * autenticación previa, ya que permiten a los usuarios iniciar sesión
 * para obtener un token JWT.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    private final JwtService jwtService;

    /**
     * Crea una instancia del controlador de autenticación.
     *
     * @param authenticationService servicio encargado de validar
     *                              las credenciales del usuario
     * @param jwtService            servicio encargado de generar tokens JWT
     */
    public AuthController(
            AuthenticationService authenticationService,
            JwtService jwtService) {

        this.authenticationService = authenticationService;
        this.jwtService = jwtService;
    }

    /**
     * Autentica un usuario y genera un token JWT.
     *
     * <p>
     * El proceso consiste en:
     * </p>
     *
     * <ol>
     * <li>Validar los datos recibidos.</li>
     * <li>Comprobar las credenciales mediante
     * {@link AuthenticationService}.</li>
     * <li>Generar un token JWT para el usuario autenticado.</li>
     * <li>Devolver el token al cliente.</li>
     * </ol>
     *
     * <p>
     * Este endpoint no requiere autenticación, ya que su finalidad
     * es precisamente proporcionar el token necesario para acceder
     * posteriormente a los endpoints protegidos.
     * </p>
     *
     * @param request datos de acceso proporcionados por el usuario
     * @return respuesta que contiene el token JWT generado
     */
    @PostMapping("/login")
    @Operation(summary = "Inicia sesión", description = "Autentica un usuario mediante su correo electrónico "
            + "y contraseña y devuelve un token JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Autenticación realizada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "Credenciales incorrectas")
    })
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request) {

        User user = authenticationService.authenticate(
                request.getEmail(),
                request.getPassword());

        String token = jwtService.generateToken(user);

        return new LoginResponse(token);
    }
}
