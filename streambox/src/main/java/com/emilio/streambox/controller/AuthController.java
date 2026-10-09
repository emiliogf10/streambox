package com.emilio.streambox.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.emilio.streambox.security.refresh.SessionTokens;
import com.emilio.streambox.service.AuthenticationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Controlador REST de la autenticación ({@code /api/auth}).
 *
 * <p>
 * Es público (salvo {@code /logout-all}, que necesita saber de quién son las
 * sesiones): no requiere token, porque su función es precisamente
 * entregarlo. La lógica está en {@link AuthenticationService} (credenciales,
 * bloqueo de cuentas) y en {@link RefreshTokenService} (rotación y revocación
 * de los refresh tokens); aquí solo se traducen sus resultados a cookies.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    private final RefreshTokenService refreshTokenService;

    private final AuthCookieService authCookieService;

    /**
     * Crea el controlador de autenticación.
     *
     * @param authenticationService servicio que valida las credenciales y genera los tokens
     * @param refreshTokenService   servicio que rota y revoca los refresh tokens
     * @param authCookieService     construye las cookies de sesión
     */
    public AuthController(AuthenticationService authenticationService, RefreshTokenService refreshTokenService,
            AuthCookieService authCookieService) {
        this.authenticationService = authenticationService;
        this.refreshTokenService = refreshTokenService;
        this.authCookieService = authCookieService;
    }

    /**
     * Inicia sesión: entrega el JWT de acceso y el refresh token en cookies
     * HttpOnly y no en el cuerpo.
     *
     * @param request     correo electrónico y contraseña
     * @param httpRequest petición HTTP, de la que se toma la IP de origen
     *                    ({@code getRemoteAddr()}) para el bloqueo por IP conocida
     * @param response    respuesta en la que se añaden los {@code Set-Cookie}
     */
    @PostMapping("/login")
    @Operation(summary = "Inicia sesión", description = "Autentica un usuario mediante su correo electrónico "
            + "y contraseña. Los tokens no van en el cuerpo: se entregan en dos cookies HttpOnly; "
            + "SameSite=Strict (Secure según la configuración). streambox_token: JWT de acceso, Path=/api, "
            + "Max-Age = su vida (15 minutos por defecto). streambox_refresh: refresh token opaco, "
            + "Path=/api/auth (solo viaja a login, refresh y logout), Max-Age = su vida (7 días). Cuando el "
            + "JWT caduca, POST /api/auth/refresh entrega uno nuevo sin volver a pedir la contraseña. Las "
            + "peticiones POST/PUT/PATCH/DELETE autenticadas por cookie deben llevar la cabecera "
            + "X-Requested-With: StreamBox (403 CSRF_REJECTED si falta). También se acepta "
            + "Authorization: Bearer con el JWT de acceso (clientes de API), sin esa cabecera extra.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Autenticación correcta; sin cuerpo, "
                    + "con las cookies streambox_token y streambox_refresh en Set-Cookie"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos "
                    + "(email con formato incorrecto, contraseña vacía o de más de 1024 caracteres). No "
                    + "gasta intento de la cuenta. En el login no se aplica la política de longitud del "
                    + "registro: las cuentas antiguas siguen pudiendo entrar"),
            @ApiResponse(responseCode = "401", description = "Credenciales incorrectas (código "
                    + "INVALID_CREDENTIALS, mensaje «Email o contraseña incorrectos»). El cuerpo incluye "
                    + "remainingAttempts: intentos que le quedan a la cuenta ya descontado este fallo "
                    + "(siempre 1 o más); si el siguiente intento también falla y era el último, la "
                    + "cuenta se bloquea y se responde 429 ACCOUNT_LOCKED. Desde una misma IP de origen es idéntico "
                    + "exista o no el email"),
            @ApiResponse(responseCode = "429", description = "Demasiados intentos; la cabecera Retry-After "
                    + "indica los segundos que hay que esperar. Código ACCOUNT_LOCKED: la cuenta está "
                    + "bloqueada por logins fallidos (lo devuelve el fallo que agota los intentos y "
                    + "cualquier intento durante el bloqueo, aunque la contraseña sea correcta; el mensaje "
                    + "dice cuántos minutos dura; no se aplica a una IP desde la que el titular ya inició "
                    + "sesión con éxito, que tiene su propio contador con los mismos límites). "
                    + "Código RATE_LIMIT_EXCEEDED: demasiadas peticiones de "
                    + "login desde esta IP")
    })
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {

        // getRemoteAddr() y no X-Forwarded-For: en prod la cabecera solo la
        // aplica Spring (forward-headers-strategy=native) y no se puede falsificar.
        SessionTokens tokens = authenticationService.login(
                request.getEmail(), request.getPassword(), httpRequest.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.sessionCookie(tokens.accessToken()));
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.refreshCookie(tokens.refreshToken()));
    }

    /**
     * Renueva la sesión: con el refresh token de la cookie, entrega un JWT de
     * acceso nuevo y rota el refresh token.
     *
     * <p>
     * Si el refresh token no sirve, borra las dos cookies (el navegador no
     * tiene nada que reintentar) y relanza la excepción, que
     * {@code GlobalExceptionHandler} convierte en el 401 {@code SESSION_EXPIRED}.
     * </p>
     *
     * @param refreshToken valor de la cookie {@code streambox_refresh}, o {@code null} si no viene
     * @param response     respuesta en la que se añaden los {@code Set-Cookie}
     */
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Renueva la sesión", description = "Entrega un JWT de acceso nuevo (cookie "
            + "streambox_token) a partir del refresh token de la cookie streambox_refresh, sin pedir la "
            + "contraseña. Sin cuerpo. Solo lee la cookie streambox_refresh: ignora streambox_token y la "
            + "cabecera Authorization. Exige la cabecera X-Requested-With: StreamBox (defensa CSRF). Cada "
            + "uso rota el refresh token: el viejo queda revocado y llega uno nuevo en Set-Cookie (vida de 7 "
            + "días, sin pasar de 30 días desde el login). Reutilizar un refresh token ya rotado se trata "
            + "como un robo y revoca la sesión entera (también el token nuevo), salvo en los 10 segundos "
            + "siguientes a la rotación: así dos pestañas que renuevan a la vez no se cierran la sesión; la "
            + "segunda recibe solo el JWT nuevo, sin Set-Cookie de streambox_refresh, porque el navegador ya "
            + "tiene el sucesor.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Sesión renovada; sin cuerpo. Set-Cookie con "
                    + "streambox_token y, salvo en la gracia de un refresh simultáneo, con el streambox_refresh "
                    + "nuevo"),
            @ApiResponse(responseCode = "401", description = "Código SESSION_EXPIRED: el refresh token falta, "
                    + "no existe, ha caducado (él o su sesión de 30 días), está revocado (logout) o se ha "
                    + "reutilizado (y entonces se revoca toda la sesión). Mismo cuerpo en todos los casos. La "
                    + "respuesta borra las dos cookies: hay que volver a iniciar sesión"),
            @ApiResponse(responseCode = "403", description = "Código CSRF_REJECTED: falta la cabecera "
                    + "X-Requested-With: StreamBox. No se mira el refresh token ni se gasta el límite por IP"),
            @ApiResponse(responseCode = "429", description = "Código RATE_LIMIT_EXCEEDED: demasiadas "
                    + "renovaciones desde esta IP (30 por minuto por defecto); la cabecera Retry-After indica "
                    + "los segundos que hay que esperar. Solo cuentan las peticiones con la cabecera "
                    + "X-Requested-With y con la cookie streambox_refresh: sin cookie (visita sin sesión) la "
                    + "respuesta es siempre el 401 SESSION_EXPIRED")
    })
    public void refresh(
            @Parameter(description = "Refresh token (cookie HttpOnly streambox_refresh que fija el login; "
                    + "el navegador la envía solo)")
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {

        SessionTokens tokens;
        try {
            // Sin cookie (cada visita anónima: JavaScript no sabe si la cookie
            // HttpOnly existe) o con un valor que no puede ser un token propio:
            // el mismo 401 que cualquier otro fallo, pero sin abrir la
            // transacción del servicio (no gasta una conexión del pool). El
            // límite por IP tampoco cuenta las peticiones sin cookie (ver
            // RateLimitingFilter); las de valor mal formado sí.
            if (!RefreshTokenService.hasTokenFormat(refreshToken)) {
                throw new SessionExpiredException();
            }
            tokens = refreshTokenService.refresh(refreshToken);
        } catch (SessionExpiredException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingCookie());
            response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingRefreshCookie());
            throw e;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.sessionCookie(tokens.accessToken()));
        if (tokens.refreshToken() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.refreshCookie(tokens.refreshToken()));
        }
    }

    /**
     * Cierra la sesión: revoca la familia del refresh token y borra las dos
     * cookies.
     *
     * <p>
     * Primero se revoca y después se borran las cookies: si la base de datos
     * falla, la respuesta es un 500 <em>sin</em> borrar las cookies, y el
     * frontend puede reintentar el logout en lugar de dar por cerrada una
     * sesión que en el servidor sigue viva.
     * </p>
     *
     * <p>
     * Exige {@code X-Requested-With: StreamBox} como el refresh (lo comprueba
     * {@code JwtAuthenticationFilter} antes de llegar aquí, también con
     * {@code Authorization: Bearer}): sin ella, un formulario de otra web no
     * revocaría nada (SameSite=Strict no envía las cookies), pero los
     * {@code Set-Cookie} de su respuesta sí borrarían la sesión de la víctima.
     * El 403 {@code CSRF_REJECTED} no toca la base de datos ni las cookies.
     * </p>
     *
     * @param refreshToken valor de la cookie {@code streambox_refresh}, o {@code null} si no viene
     * @param response     respuesta en la que se añaden los {@code Set-Cookie}
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cierra sesión", description = "Revoca en el servidor la sesión del refresh token de "
            + "la cookie streambox_refresh (sus tokens dejan de servir para renovar) y borra las cookies "
            + "streambox_token y streambox_refresh (Set-Cookie con Max-Age=0). Es público e idempotente: "
            + "responde 204 haya o no sesión, y con un refresh token desconocido o ya revocado. Exige la "
            + "cabecera X-Requested-With: StreamBox (defensa CSRF, también con Authorization: Bearer): sin "
            + "ella otra web podría borrar las cookies de la víctima. El JWT de "
            + "acceso es stateless y no se puede revocar: una copia seguiría valiendo hasta su caducidad "
            + "(como mucho 15 minutos por defecto), pero ya no se podría renovar.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Sesión revocada (si la había) y cookies borradas"),
            @ApiResponse(responseCode = "403", description = "Código CSRF_REJECTED: falta la cabecera "
                    + "X-Requested-With: StreamBox. No se revoca nada ni se borran las cookies: la sesión "
                    + "sigue abierta"),
            @ApiResponse(responseCode = "500", description = "Código INTERNAL_ERROR: no se ha podido revocar "
                    + "la sesión (por ejemplo, la base de datos no responde). No se borran las cookies: la "
                    + "sesión sigue viva en el servidor y el cliente debe reintentar el logout en lugar de "
                    + "darla por cerrada")
    })
    public void logout(
            @Parameter(description = "Refresh token de la sesión que se cierra (cookie HttpOnly "
                    + "streambox_refresh); opcional")
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE_NAME, required = false) String refreshToken,
            HttpServletResponse response) {

        refreshTokenService.revokeFamilyOf(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingCookie());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingRefreshCookie());
    }

    /**
     * Cierra la sesión en todos los dispositivos: revoca todas las familias de
     * refresh tokens del usuario, también la de esta petición, y borra las dos
     * cookies.
     *
     * <p>
     * A diferencia del logout, <b>exige estar autenticado</b> (regla en
     * {@code SecurityConfig}): necesita saber de quién son las sesiones, y lo
     * toma del token ({@link AuthenticatedUser}), nunca del cliente. Por eso no
     * necesita la regla especial de la cabecera CSRF del logout: por cookie,
     * {@code JwtAuthenticationFilter} exige {@code X-Requested-With} como en
     * cualquier petición no segura; un formulario de otra web no lleva la
     * cookie ({@code SameSite=Strict}), recibe un 401 antes de llegar aquí y su
     * respuesta no borra nada. Con {@code Authorization: Bearer} no hace falta
     * la cabecera (el navegador no adjunta el Bearer solo).
     * </p>
     *
     * <p>
     * Como en el logout, primero se revoca y después se borran las cookies: si
     * la base de datos falla, 500 sin tocar las cookies, y el cliente puede
     * reintentar.
     * </p>
     *
     * @param principal usuario autenticado de la petición
     * @param response  respuesta en la que se añaden los {@code Set-Cookie} de borrado
     */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cierra sesión en todos los dispositivos", description = "Revoca todas las sesiones "
            + "de la cuenta autenticada, incluida la actual: ningún refresh token suyo vuelve a servir para "
            + "renovar. Borra las cookies streambox_token y streambox_refresh de esta petición (Set-Cookie con "
            + "Max-Age=0). Sin cuerpo. Los JWT de acceso ya emitidos son stateless y no se pueden revocar: "
            + "siguen valiendo hasta caducar (como mucho 15 minutos), pero ya no se pueden renovar.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Todas las sesiones revocadas y cookies borradas"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado (no se revoca ni "
                    + "se borra nada)"),
            @ApiResponse(responseCode = "500", description = "Código INTERNAL_ERROR: no se han podido revocar "
                    + "las sesiones (por ejemplo, la base de datos no responde). No se borran las cookies: el "
                    + "cliente debe reintentar en lugar de dar las sesiones por cerradas")
    })
    public void logoutAll(@AuthenticationPrincipal AuthenticatedUser principal, HttpServletResponse response) {

        refreshTokenService.revokeAllSessions(principal.id());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingCookie());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingRefreshCookie());
    }
}
