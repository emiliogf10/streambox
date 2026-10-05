package com.emilio.streambox.security.password;

/**
 * Petición que fija la contraseña de una cuenta y que, por tanto, debe
 * cumplir la {@link PasswordPolicy}.
 *
 * <p>
 * {@link ValidPasswordValidator} necesita, además de la contraseña, el nombre
 * de usuario y el email para comprobar que no los contiene. Validar a través
 * de esta interfaz, y no de un DTO concreto, evita que el paquete de seguridad
 * dependa de {@code dto} (el DTO ya depende de este paquete por la anotación) y
 * permite reutilizar la regla en futuras peticiones, como un cambio de
 * contraseña.
 * </p>
 */
public interface NewPasswordRequest {

    /** @return contraseña en texto plano tal como la envió el cliente */
    String getPassword();

    /** @return nombre de usuario de la cuenta */
    String getUsername();

    /** @return email de la cuenta */
    String getEmail();
}
