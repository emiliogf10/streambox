package com.emilio.streambox.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador utilizado para comprobar que la API de Streambox
 * está iniciada y puede recibir peticiones HTTP.
 *
 * <p>Este controlador tiene carácter temporal y puede utilizarse
 * durante el desarrollo para verificar que el servidor web
 * responde correctamente.</p>
 */
@RestController
public class TestController {

    /**
     * Endpoint de comprobación de disponibilidad de la API.
     *
     * @return mensaje indicando que la API está funcionando correctamente
     */
    @GetMapping("/")
    public String home() {
        return "StreamBox API funcionando correctamente";
    }
}
