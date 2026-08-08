package com.emilio.streambox.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DTO utilizado para representar la respuesta del proceso de inicio de sesión.
 *
 * <p>Contiene el token JWT generado para el usuario después de que sus
 * credenciales hayan sido autenticadas correctamente.</p>
 */
@Getter
@AllArgsConstructor
public class LoginResponse {

    /**
     * Token JWT que debe utilizar el cliente para autenticarse
     * en las peticiones posteriores a endpoints protegidos.
     */
    private String token;
}