package com.emilio.streambox.security;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.RateLimitProperties;
import com.emilio.streambox.security.ratelimit.RateLimitingFilter;

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
         * Reloj de la aplicación. Se declara como bean para poder
         * sustituirlo en las pruebas de los límites de intentos.
         *
         * @return reloj UTC del sistema
         */
        @Bean
        public Clock clock() {
                return Clock.systemUTC();
        }

        /**
         * Crea el filtro que limita por IP los intentos de login y registro.
         *
         * <p>
         * Recibe el mismo {@link PathPatternRequestMatcher.Builder} que usa
         * {@code requestMatchers(...)} en las reglas de autorización (Spring Boot
         * registra uno; si no existiera, se usa el de por defecto, igual que hace
         * Spring Security). Así el filtro reconoce las rutas exactamente como
         * ellas: decodificadas y sin parámetros de matriz. Comparar el texto de
         * la URI permitía saltarse el límite con {@code /api/auth/%6cogin}.
         * </p>
         *
         * @param properties     límites configurados
         * @param clock          reloj de la aplicación
         * @param errorWriter    escritor de errores JSON de seguridad
         * @param matcherBuilder constructor de matchers de rutas de la aplicación
         * @return filtro de limitación de peticiones
         */
        @Bean
        public RateLimitingFilter rateLimitingFilter(
                        RateLimitProperties properties,
                        Clock clock,
                        SecurityErrorResponseWriter errorWriter,
                        ObjectProvider<PathPatternRequestMatcher.Builder> matcherBuilder) {

                return new RateLimitingFilter(properties, clock, errorWriter,
                                matcherBuilder.getIfAvailable(PathPatternRequestMatcher::withDefaults));
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
         * de usuarios y autenticación, a las comprobaciones de salud
         * ({@code /actuator/health}) y a la documentación OpenAPI.</li>
         * <li>En el catálogo ({@code /api/movies/**} y {@code /api/genres/**})
         * la lectura ({@code GET}/{@code HEAD}) es para cualquier usuario
         * autenticado y cualquier otro método queda reservado a
         * {@code ADMIN}, de modo que un endpoint de escritura nuevo nace
         * protegido.</li>
         * <li>Exige autenticación para cualquier otro endpoint.</li>
         * <li>Registra {@link JwtAuthenticationFilter} antes del filtro
         * estándar {@link UsernamePasswordAuthenticationFilter}.</li>
         * </ul>
         *
         * @param http                    objeto utilizado para configurar la seguridad
         *                                HTTP
         * @param jwtAuthenticationFilter filtro encargado de procesar
         *                                los tokens JWT
         * @param rateLimitingFilter      filtro que limita los intentos de
         *                                login y registro por IP
         * @return cadena de filtros de seguridad configurada
         * @throws Exception si se produce un error durante la configuración
         */
        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        JwtAuthenticationFilter jwtAuthenticationFilter,
                        RateLimitingFilter rateLimitingFilter,
                        JwtAuthenticationEntryPoint authenticationEntryPoint,
                        JwtAccessDeniedHandler accessDeniedHandler) throws Exception {

                http
                                .csrf(csrf -> csrf.disable())

                                // La API es stateless: cada peticion se autentica con el token JWT.
                                // Spring Security no creara ni consultara sesiones HTTP.
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // Respuestas JSON uniformes en lugar de las paginas HTML por defecto.
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(authenticationEntryPoint)
                                                .accessDeniedHandler(accessDeniedHandler))

                                .authorizeHttpRequests(auth -> auth
                                                // Registro y autenticacion son publicos. Las rutas
                                                // salen de RateLimitingFilter para que lo publico y
                                                // lo limitado por IP sean siempre lo mismo.
                                                .requestMatchers(HttpMethod.POST,
                                                                RateLimitingFilter.REGISTER_PATH,
                                                                RateLimitingFilter.LOGIN_PATH)
                                                .permitAll()

                                                // Solo ADMIN puede listar todos los usuarios.
                                                .requestMatchers(HttpMethod.GET, "/api/users")
                                                .hasRole("ADMIN")

                                                // Cualquier usuario autenticado puede consultar peliculas.
                                                // HEAD es la misma lectura sin cuerpo (Spring MVC lo
                                                // atiende con los @GetMapping), asi que recibe la
                                                // misma regla que GET.
                                                .requestMatchers(HttpMethod.GET, "/api/movies/**")
                                                .authenticated()
                                                .requestMatchers(HttpMethod.HEAD, "/api/movies/**")
                                                .authenticated()

                                                // Solo ADMIN puede crear, modificar o eliminar peliculas.
                                                .requestMatchers(HttpMethod.POST, "/api/movies/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.PUT, "/api/movies/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.PATCH, "/api/movies/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.DELETE, "/api/movies/**")
                                                .hasRole("ADMIN")

                                                // Cualquier usuario autenticado puede consultar generos.
                                                .requestMatchers(HttpMethod.GET, "/api/genres/**")
                                                .authenticated()
                                                .requestMatchers(HttpMethod.HEAD, "/api/genres/**")
                                                .authenticated()

                                                // Solo ADMIN puede crear, modificar o eliminar generos.
                                                .requestMatchers(HttpMethod.POST, "/api/genres/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.PUT, "/api/genres/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.PATCH, "/api/genres/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.DELETE, "/api/genres/**")
                                                .hasRole("ADMIN")

                                                // Red de seguridad del catalogo: cualquier otro metodo
                                                // (OPTIONS, TRACE o uno que se use en el futuro) es
                                                // solo para ADMIN. Asi, un endpoint de escritura nuevo
                                                // queda protegido aunque se olvide su regla, en vez de
                                                // caer en anyRequest().authenticated() y quedar abierto
                                                // a cualquier USER. Las preflight de CORS no se ven
                                                // afectadas: si algun dia se configura CORS, su filtro
                                                // las responde antes de llegar a la autorizacion.
                                                .requestMatchers("/api/movies/**", "/api/genres/**")
                                                .hasRole("ADMIN")

                                                // Comprobaciones de salud (Actuator) para balanceadores,
                                                // Docker y Kubernetes: publicas pero sin detalles (solo
                                                // UP/DOWN). El resto de endpoints de Actuator no se
                                                // exponen (ver application.properties).
                                                .requestMatchers(
                                                                "/actuator/health",
                                                                "/actuator/health/**")
                                                .permitAll()

                                                // Documentacion OpenAPI y Swagger UI sin autenticacion.
                                                .requestMatchers(
                                                                "/v3/api-docs/**",
                                                                "/swagger-ui/**",
                                                                "/swagger-ui.html")
                                                .permitAll()

                                                .anyRequest().authenticated())

                                .addFilterBefore(
                                                jwtAuthenticationFilter,
                                                UsernamePasswordAuthenticationFilter.class)

                                // El límite por IP se evalúa antes que nada, incluso
                                // antes de validar el token.
                                .addFilterBefore(
                                                rateLimitingFilter,
                                                JwtAuthenticationFilter.class);

                return http.build();
        }
}
