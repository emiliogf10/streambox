package com.emilio.streambox.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.emilio.streambox.repository.UserRepository;

/**
 * Configuración de seguridad de la aplicación Streambox.
 *
 * <p>
 * Define los componentes utilizados por Spring Security para proteger
 * los endpoints de la aplicación, gestionar el cifrado de contraseñas y
 * procesar la autenticación mediante tokens JWT.
 * </p>
 *
 * <p>
 * Los endpoints relacionados con el registro y la autenticación son
 * accesibles sin autenticación. El resto de endpoints requieren que el
 * usuario esté autenticado.
 * </p>
 */
@Configuration
public class SecurityConfig {

        /**
         * Crea el componente encargado de cifrar y verificar contraseñas
         * mediante el algoritmo BCrypt.
         *
         * @return {@link PasswordEncoder} configurado con BCrypt
         */
        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        /**
         * Crea el filtro encargado de procesar los tokens JWT incluidos
         * en las peticiones HTTP.
         *
         * <p>
         * Spring inyecta automáticamente las dependencias necesarias
         * para que el filtro pueda validar los tokens y localizar a los
         * usuarios asociados.
         * </p>
         *
         * @param jwtService     servicio encargado de generar y validar tokens JWT
         * @param userRepository repositorio utilizado para buscar usuarios
         * @return instancia configurada de {@link JwtAuthenticationFilter}
         */
        @Bean
        public JwtAuthenticationFilter jwtAuthenticationFilter(
                        JwtService jwtService,
                        UserRepository userRepository) {

                return new JwtAuthenticationFilter(
                                jwtService,
                                userRepository);
        }

        /**
         * Configura la cadena de filtros de seguridad de Spring Security.
         *
         * <p>
         * La configuración establece las siguientes reglas:
         * </p>
         *
         * <ul>
         * <li>Desactiva CSRF, ya que la API utiliza autenticación
         * mediante tokens JWT.</li>
         * <li>Permite el acceso sin autenticación a los endpoints
         * de usuarios y autenticación.</li>
         * <li>Exige autenticación para cualquier otro endpoint.</li>
         * <li>Registra {@link JwtAuthenticationFilter} antes del filtro
         * estándar {@link UsernamePasswordAuthenticationFilter}.</li>
         * </ul>
         *
         * @param http                    objeto utilizado para configurar la seguridad
         *                                HTTP
         * @param jwtAuthenticationFilter filtro encargado de procesar
         *                                los tokens JWT
         * @return cadena de filtros de seguridad configurada
         * @throws Exception si se produce un error durante la configuración
         */
        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        JwtAuthenticationFilter jwtAuthenticationFilter)
                        throws Exception {

                http
                                .csrf(csrf -> csrf.disable())
                                .authorizeHttpRequests(auth -> auth

                                                // Los usuarios pueden registrarse sin estar autenticados.
                                                .requestMatchers(
                                                                HttpMethod.POST,
                                                                "/api/users")
                                                .permitAll()

                                                // Registro y login no requieren autenticación.
                                                .requestMatchers(
                                                                "/api/auth/**")
                                                .permitAll()

                                                // Solo los administradores pueden consultar la lista completa de
                                                // usuarios.
                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/api/users")
                                                .hasRole("ADMIN")

                                                // Cualquier usuario autenticado puede consultar películas.
                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/api/movies/**")
                                                .authenticated()

                                                // Solo los administradores pueden crear películas.
                                                .requestMatchers(
                                                                HttpMethod.POST,
                                                                "/api/movies/**")
                                                .hasRole("ADMIN")

                                                // Solo los administradores pueden modificar películas.
                                                .requestMatchers(
                                                                HttpMethod.PUT,
                                                                "/api/movies/**")
                                                .hasRole("ADMIN")

                                                // Solo los administradores pueden eliminar películas.
                                                .requestMatchers(
                                                                HttpMethod.DELETE,
                                                                "/api/movies/**")
                                                .hasRole("ADMIN")

                                                // Cualquier usuario autenticado puede consultar los géneros.
                                                .requestMatchers(
                                                                HttpMethod.GET,
                                                                "/api/genres/**")
                                                .authenticated()

                                                // Solo los administradores pueden crear géneros.
                                                .requestMatchers(
                                                                HttpMethod.POST,
                                                                "/api/genres/**")
                                                .hasRole("ADMIN")
                                                // Permite acceder a la documentación OpenAPI y Swagger UI sin
                                                // autenticación.
                                                .requestMatchers(
                                                                "/v3/api-docs/**",
                                                                "/swagger-ui/**",
                                                                "/swagger-ui.html")
                                                .permitAll()

                                                .anyRequest().authenticated())
                                .addFilterBefore(
                                                jwtAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class);

                System.out.println(">>> SECURITY CONFIG CARGADA");

                return http.build();
        }
}
