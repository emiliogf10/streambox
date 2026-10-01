package com.emilio.streambox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Punto de entrada de la aplicación Streambox.
 *
 * <p>
 * Se excluye {@link UserDetailsServiceAutoConfiguration}: sin un
 * {@code UserDetailsService} propio, Spring Security crearía un usuario
 * "user" con una contraseña aleatoria que escribe en el log. Streambox
 * autentica con su propio servicio y con tokens JWT, así que ese usuario
 * por defecto no se usa y no debe existir.
 * </p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class StreamboxApplication {

	/**
	 * Arranca la aplicación.
	 *
	 * @param args argumentos de línea de comandos
	 */
	public static void main(String[] args) {
		SpringApplication.run(StreamboxApplication.class, args);
	}

}
