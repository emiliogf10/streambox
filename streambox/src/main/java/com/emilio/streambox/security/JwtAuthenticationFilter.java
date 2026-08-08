package com.emilio.streambox.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Filtro de Spring Security encargado de autenticar las peticiones
 * que contienen un token JWT válido.
 *
 * <p>El filtro comprueba la cabecera {@code Authorization} de cada petición.
 * Cuando contiene un token con el formato {@code Bearer <token>}, el token
 * se valida mediante {@link JwtService} y se obtiene el correo electrónico
 * del usuario.</p>
 *
 * <p>Posteriormente se busca el usuario en la base de datos y, si existe,
 * se crea una autenticación de Spring Security que se almacena en el
 * {@link SecurityContextHolder}. Esto permite que Spring Security conozca
 * el usuario autenticado y sus permisos durante el procesamiento de la
 * petición.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    /**
     * Crea una instancia del filtro JWT.
     *
     * @param jwtService servicio utilizado para validar los tokens JWT
     * @param userRepository repositorio utilizado para buscar al usuario
     *                       asociado al token
     */
    public JwtAuthenticationFilter(
            JwtService jwtService,
            UserRepository userRepository) {

        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    /**
     * Procesa una petición HTTP para determinar si contiene un token JWT
     * válido y establecer la autenticación correspondiente.
     *
     * <p>Si la petición no contiene una cabecera {@code Authorization}
     * con el esquema {@code Bearer}, se continúa directamente con la
     * siguiente etapa de la cadena de filtros.</p>
     *
     * <p>Cuando existe un token, se extrae el correo electrónico mediante
     * {@link JwtService}, se busca el usuario correspondiente y se crea
     * una instancia de {@link UsernamePasswordAuthenticationToken} con
     * el rol del usuario.</p>
     *
     * <p>La autenticación se almacena en {@link SecurityContextHolder}
     * para que Spring Security pueda utilizarla durante el resto de
     * la petición.</p>
     *
     * @param request petición HTTP recibida
     * @param response respuesta HTTP
     * @param filterChain cadena de filtros que debe continuar procesando
     *                    la petición
     * @throws ServletException si se produce un error relacionado con
     *                          el procesamiento del servlet
     * @throws IOException si se produce un error de entrada o salida
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {

            String email = jwtService.extractEmail(token);

            User user = userRepository.findByEmail(email)
                    .orElse(null);

            if (user != null) {

                var authorities = List.of(
                        new SimpleGrantedAuthority(
                                "ROLE_" + user.getRole().name()
                        )
                );

                var authentication =
                        new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                authorities
                        );

                SecurityContextHolder
                        .getContext()
                        .setAuthentication(authentication);
            }

        } catch (Exception e) {

            System.out.println("JWT inválido: " + e.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
