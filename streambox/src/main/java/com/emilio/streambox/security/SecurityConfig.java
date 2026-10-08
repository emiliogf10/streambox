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
         * <li>Las vistas de gestión ({@code /api/admin/**}) quedan reservadas a
         * {@code ADMIN} con cualquier método, también la lectura: muestran
         * datos que los usuarios no deben ver (p. ej. series sin
         * episodios).</li>
         * <li>En el catálogo ({@code /api/movies/**}, {@code /api/genres/**} y
         * {@code /api/series/**}, episodios incluidos) la lectura
         * ({@code GET}/{@code HEAD}) es para cualquier usuario autenticado y
         * cualquier otro método queda reservado a {@code ADMIN}, de modo que
         * un endpoint de escritura nuevo nace protegido.</li>
         * <li>{@code /api/users} (el listado de cuentas) es solo para
         * {@code ADMIN} con cualquier método salvo {@code POST} (registro,
         * público). La regla es por ruta y no por método: con
         * {@code HEAD}, que Spring MVC atiende con el {@code @GetMapping},
         * un usuario corriente ejecutaba el listado.</li>
         * <li>Exige autenticación para cualquier otro endpoint, incluidos los
         * personales de {@code /api/users/me/**} (como los favoritos de
         * series), que usan el id del token y nunca uno de la URL.</li>
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

                                                // Vistas de gestion (p. ej. /api/admin/series, que
                                                // incluye series sin episodios, ocultas a los
                                                // usuarios): cualquier metodo, incluida la lectura,
                                                // es solo para ADMIN. Va antes de las reglas del
                                                // catalogo para que ninguna regla de lectura
                                                // "autenticada" pueda capturarla si algun dia se
                                                // amplia un patron.
                                                .requestMatchers("/api/admin/**")
                                                .hasRole("ADMIN")

                                                // Solo ADMIN puede listar todos los usuarios. La regla
                                                // es POR RUTA (cualquier metodo), no solo para GET:
                                                // HEAD cae en el @GetMapping de Spring MVC, asi que
                                                // con una regla solo de GET un USER ejecutaba el
                                                // listado completo y veia el Content-Length (que
                                                // delata cuantas cuentas hay). El unico metodo publico
                                                // de esta ruta, POST (registro), ya se permitio arriba.
                                                // La coincidencia es exacta: /api/users/me y
                                                // /api/users/me/** no entran aqui y siguen siendo
                                                // "autenticado" (anyRequest).
                                                .requestMatchers(RateLimitingFilter.REGISTER_PATH)
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

                                                // Cualquier usuario autenticado puede consultar series
                                                // (listado, busqueda y detalle; el servicio oculta las
                                                // que no tienen episodios).
                                                .requestMatchers(HttpMethod.GET, "/api/series/**")
                                                .authenticated()
                                                .requestMatchers(HttpMethod.HEAD, "/api/series/**")
                                                .authenticated()

                                                // Solo ADMIN puede crear, modificar o eliminar series
                                                // y sus episodios (/api/series/{id}/episodes/**).
                                                .requestMatchers(HttpMethod.POST, "/api/series/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.PUT, "/api/series/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.PATCH, "/api/series/**")
                                                .hasRole("ADMIN")
                                                .requestMatchers(HttpMethod.DELETE, "/api/series/**")
                                                .hasRole("ADMIN")

                                                // Red de seguridad del catalogo: cualquier otro metodo
                                                // (OPTIONS, TRACE o uno que se use en el futuro) es
                                                // solo para ADMIN. Asi, un endpoint de escritura nuevo
                                                // queda protegido aunque se olvide su regla, en vez de
                                                // caer en anyRequest().authenticated() y quedar abierto
                                                // a cualquier USER. Las preflight de CORS no se ven
                                                // afectadas: si algun dia se configura CORS, su filtro
                                                // las responde antes de llegar a la autorizacion.
                                                // Los favoritos de series (/api/users/me/favorites/series)
                                                // no cuelgan de aqui: son personales y caen en
                                                // anyRequest().authenticated().
                                                .requestMatchers("/api/movies/**", "/api/genres/**",
                                                                "/api/series/**")
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
