package com.emilio.streambox.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador utilizado para comprobar que la autenticación mediante
 * tokens JWT funciona correctamente.
 *
 * <p>Este controlador es temporal y se utilizará durante el desarrollo
 * para verificar que Spring Security permite el acceso únicamente a
 * usuarios autenticados.</p>
 */
@RestController
public class ProtectedTestController {

    /**
     * Comprueba que la petición ha sido realizada por un usuario
     * autenticado mediante JWT.
     *
     * @return mensaje indicando que el acceso protegido funciona
     */
    @GetMapping("/api/test/protected")
    public String protectedEndpoint() {
        return "Acceso protegido correctamente";
    }
}
