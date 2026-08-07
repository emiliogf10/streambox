package com.emilio.streambox.controller;

import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.dto.LoginResponse;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

//Clase encargada de manejar las solicitudes relacionadas con la autenticación de usuarios

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;
    private final JwtService jwtService;

    public AuthController(
        AuthenticationService authenticationService,
        JwtService jwtService) {

    this.authenticationService = authenticationService;
    this.jwtService = jwtService;
}

    @PostMapping("/login")
public LoginResponse login(@Valid @RequestBody LoginRequest request) {

    User user = authenticationService.authenticate(
            request.getEmail(),
            request.getPassword()
    );

    String token = jwtService.generateToken(user);

    return new LoginResponse(token);
}
}
