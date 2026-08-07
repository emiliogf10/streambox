package com.emilio.streambox.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

//Clase que representa la respuesta de inicio de sesión, que contiene el token JWT generado para el usuario autenticado.

@Getter
@AllArgsConstructor
public class LoginResponse {

    private String token;

}