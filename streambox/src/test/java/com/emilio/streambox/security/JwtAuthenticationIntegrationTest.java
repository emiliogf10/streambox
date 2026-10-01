package com.emilio.streambox.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Comprueba que {@link JwtAuthenticationFilter} acepta únicamente tokens
 * legítimos y que el acceso se decide con los datos actuales del usuario.
 *
 * <p>
 * Se prueba a través de un endpoint protegido real ({@code /api/users/me}) y
 * de uno solo para administradores ({@code /api/users}).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class JwtAuthenticationIntegrationTest {

    private static final String PROTECTED = "/api/users/me";
    private static final String ADMIN_ONLY = "/api/users";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;

    private User user;

    @BeforeEach
    void setUp() {
        user = saveUser("jwtuser", Role.USER);
    }

    // --- Sin token / token no válido ---

    @Test
    void sinTokenRetorna401ConFormatoDeErrorDeLaApi() throws Exception {
        mockMvc.perform(get(PROTECTED))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value(PROTECTED));
    }

    @Test
    void tokenValidDaAcceso() throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(jwtService.generateToken(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jwtuser@test.com"));
    }

    @Test
    void textoCualquieraComoTokenRetorna401() throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer esto-no-es-un-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void esquemaDistintoDeBearerRetorna401() throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
        // El token correcto sin el prefijo "Bearer " tampoco vale
        mockMvc.perform(get(PROTECTED).header("Authorization", jwtService.generateToken(user)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenExpiradoRetorna401() throws Exception {
        long now = System.currentTimeMillis();
        String expired = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(user.getEmail())
                .issuedAt(new Date(now - 120_000))
                .expiration(new Date(now - 60_000))
                .signWith(realKey())
                .compact();

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenFirmadoConOtraClaveRetorna401() throws Exception {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "otra-clave-distinta-de-al-menos-32-caracteres!!".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(user.getEmail())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(otherKey)
                .compact();

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(forged)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenConFirmaDeOtroTokenRetorna401() throws Exception {
        // Se combina la cabecera y el contenido de un token con la firma de
        // otro: simula alterar el contenido de un token ya emitido.
        User other = saveUser("otheruser", Role.USER);
        String[] mine = jwtService.generateToken(user).split("\\.");
        String[] theirs = jwtService.generateToken(other).split("\\.");
        String tampered = mine[0] + "." + mine[1] + "." + theirs[2];

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(tampered)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSinFirmaRetorna401() throws Exception {
        // Ataque clásico "alg=none": token sin firma
        String unsigned = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(user.getEmail())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .compact();

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(unsigned)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDeOtroEmisorRetorna401() throws Exception {
        String foreignIssuer = Jwts.builder()
                .issuer("otro-sistema")
                .subject(user.getEmail())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(realKey())
                .compact();
        String noIssuer = Jwts.builder()
                .subject(user.getEmail())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(realKey())
                .compact();

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(foreignIssuer)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(noIssuer)))
                .andExpect(status().isUnauthorized());
    }

    // --- El acceso depende del estado ACTUAL del usuario ---

    @Test
    void tokenDeUsuarioEliminadoRetorna401() throws Exception {
        String token = jwtService.generateToken(user);
        userRepository.delete(user);
        userRepository.flush();

        mockMvc.perform(get(PROTECTED).header("Authorization", bearer(token)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usuarioNormalNoAccedeAEndpointsDeAdministrador() throws Exception {
        mockMvc.perform(get(ADMIN_ONLY).header("Authorization", bearer(jwtService.generateToken(user))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void ascenderUnUsuarioConcedeAccesoSinEmitirUnTokenNuevo() throws Exception {
        String token = bearer(jwtService.generateToken(user));
        mockMvc.perform(get(ADMIN_ONLY).header("Authorization", token))
                .andExpect(status().isForbidden());

        user.setRole(Role.ADMIN);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get(ADMIN_ONLY).header("Authorization", token))
                .andExpect(status().isOk());
    }

    @Test
    void degradarUnAdministradorLeQuitaElAccesoConElMismoToken() throws Exception {
        User admin = saveUser("exadmin", Role.ADMIN);
        String token = bearer(jwtService.generateToken(admin));
        mockMvc.perform(get(ADMIN_ONLY).header("Authorization", token))
                .andExpect(status().isOk());

        admin.setRole(Role.USER);
        userRepository.saveAndFlush(admin);

        mockMvc.perform(get(ADMIN_ONLY).header("Authorization", token))
                .andExpect(status().isForbidden());
    }

    // --- Utilidades ---

    private User saveUser(String name, Role role) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@test.com");
        u.setPassword(passwordEncoder.encode("password123"));
        u.setRole(role);
        u.setCreatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    private SecretKey realKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
