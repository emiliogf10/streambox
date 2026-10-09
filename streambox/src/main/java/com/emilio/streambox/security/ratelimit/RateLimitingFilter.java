package com.emilio.streambox.security.ratelimit;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.JwtAuthenticationFilter;
import com.emilio.streambox.security.SecurityErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Limita por IP cuántas veces se puede llamar a los endpoints públicos más
 * sensibles: el inicio de sesión ({@code POST /api/auth/login}), el registro
 * ({@code POST /api/users}) y la renovación de sesión
 * ({@code POST /api/auth/refresh}).
 *
 * <p>
 * Al superar el límite responde {@code 429 Too Many Requests} con la cabecera
 * {@code Retry-After}. Las peticiones rechazadas no se contabilizan, de modo
 * que un cliente legítimo que espera lo indicado recupera el acceso.
 * </p>
 *
 * <p>
 * La IP se toma de {@link HttpServletRequest#getRemoteAddr()}. Detrás de un
 * proxy inverso hay que activar {@code server.forward-headers-strategy=native}
 * para que sea la IP real del cliente; la cabecera {@code X-Forwarded-For} no
 * se lee a mano porque un cliente podría falsificarla para evadir el límite.
 * </p>
 *
 * <p>
 * Las direcciones IPv6 se agrupan por prefijo /64 ({@link ClientAddress}): un
 * atacante con un /64 tiene 2<sup>64</sup> direcciones, y contar cada una por
 * separado le permitiría saltarse el límite y llenar los contadores de claves.
 * La IPv4 se cuenta por dirección. Los contadores tienen un tope de claves
 * ({@code streambox.security.rate-limit.max-keys}), ver
 * {@link SlidingWindowCounter}.
 * </p>
 *
 * <h2>Cómo se reconoce la ruta</h2>
 * <p>
 * Con los mismos {@link PathPatternRequestMatcher} que usan las reglas de
 * autorización de {@code SecurityConfig} (creados con el mismo
 * {@link PathPatternRequestMatcher.Builder}), no comparando textos. Antes se
 * comparaba {@link HttpServletRequest#getRequestURI()} con
 * {@code "/api/auth/login"} mediante {@code equals}, pero ese método devuelve
 * la ruta <b>tal como llega, sin decodificar</b>, mientras que Spring MVC y
 * Spring Security decodifican cada segmento: {@code POST /api/auth/%6cogin}
 * (la {@code l} codificada) llegaba al login sin pasar por el límite, y
 * {@code POST /api/%75sers} creaba cuentas sin límite. El cortafuegos de
 * Spring Security ({@code StrictHttpFirewall}) no lo impide: rechaza
 * {@code %2F}, {@code %2E} o el punto y coma, pero no una letra codificada.
 * </p>
 *
 * <p>
 * El matcher compara cada segmento ya decodificado y sin parámetros de matriz
 * ({@code ;jsessionid=...}), así que cualquier forma de escribir la ruta que
 * Spring MVC atienda como login o registro cuenta aquí igual. Lo que no
 * reconoce (otras mayúsculas como {@code /api/Users}, o la barra final de
 * {@code /api/users/}) tampoco lo enruta Spring MVC ni lo hace público la
 * regla de autorización: nunca llega al login ni al registro. La regla general
 * es "se limita exactamente lo que es público".
 * </p>
 *
 * <h2>Qué peticiones cuentan según su {@code Content-Type}</h2>
 * <p>
 * Login y registro no exigen la cabecera {@code X-Requested-With} (no hay
 * sesión que proteger) y no hay CORS, así que una web ajena puede hacer que
 * el navegador de la víctima les envíe {@code POST} «simples», que no pasan
 * por el preflight de CORS: con un {@code Content-Type} de la lista
 * CORS-safelisted ({@code text/plain},
 * {@code application/x-www-form-urlencoded}, {@code multipart/form-data}) o
 * sin {@code Content-Type} ni cuerpo ({@code fetch(url, {method: 'POST',
 * mode: 'no-cors'})}). Spring MVC las rechaza (415, o 400 sin cuerpo) porque
 * {@code @RequestBody} no sabe leer esos tipos, pero antes este filtro ya las
 * había contado: unas pocas bastaban para dejar la IP de la víctima en 429
 * sin poder iniciar sesión ni registrarse (pista NV-A de la auditoría 2).
 * </p>
 *
 * <p>
 * Por eso esas peticiones <b>pasan sin contar</b> y siguen la cadena, donde
 * acaban en 415/400 sin llegar al controlador, es decir, sin BCrypt ni
 * consultas a la base de datos: no hace falta limitarlas para proteger nada.
 * Tampoco cuenta un {@code Content-Type} mal formado: el navegador y Spring
 * no lo analizan igual (p. ej. {@code text/plain;a=b c} es {@code text/plain}
 * para el navegador y un error para Spring) y Spring siempre lo rechaza con
 * 415.
 * </p>
 *
 * <p>
 * <b>Todo lo demás cuenta</b>, no solo el JSON. Se decidió así porque Spring
 * MVC puede leer el cuerpo con más tipos que JSON: cuando se escribió este
 * filtro, también con {@code application/yaml} (el conversor YAML de Jackson 2
 * se registraba solo porque springdoc trae {@code jackson-dataformat-yaml}).
 * Contar únicamente JSON habría dejado probar contraseñas o crear cuentas sin
 * límite con {@code Content-Type: application/yaml}. Ese conversor lo quitó
 * después {@code JsonOnlyMessageConvertersConfig} (el YAML ya recibe 415, y
 * sigue contando), pero el filtro no depende de ello. Con este criterio, cualquier
 * conversor que se añada mañana queda limitado sin tocar el filtro; lo único
 * que debe cumplirse es que ningún conversor lea el login o el registro desde
 * un tipo que no cuenta, y eso lo comprueba
 * {@code RateLimitingContentTypeIntegrationTest}. Ninguno de los tipos que
 * cuentan puede enviarse desde otra web sin preflight, y sin CORS el
 * preflight falla, así que otra web no puede gastar el presupuesto.
 * </p>
 *
 * <h2>El refresh ({@code POST /api/auth/refresh})</h2>
 * <p>
 * Tiene su propio límite por IP ({@code rate-limit.refresh}, 30 por minuto:
 * el frontend hace un refresh cada 15 minutos por navegador, así que sobra
 * margen incluso para muchas personas tras la misma IP). Cada petición busca
 * un token en la base de datos, de ahí el límite.
 * </p>
 *
 * <p>
 * La regla del {@code Content-Type} no sirve aquí: el refresh no tiene cuerpo,
 * así que una petición sin {@code Content-Type} sí llega al controlador y, con
 * esa regla, quedaría <b>sin límite</b>. El criterio equivalente es la
 * cabecera {@code X-Requested-With: StreamBox}, que el endpoint exige (403
 * {@code CSRF_REJECTED} sin ella, en {@code JwtAuthenticationFilter}, antes de
 * tocar la base de datos): una cabecera propia obliga al navegador a hacer
 * preflight, que falla sin CORS, así que otra web no puede enviarla. Por eso
 * <b>cuentan todas las peticiones con la cabecera</b> (y con la cookie del
 * refresh, ver el párrafo siguiente), tengan o no
 * {@code Content-Type}, y las que no la llevan pasan sin contar hasta su 403:
 * una web ajena no puede agotar el límite de la víctima, y quien llame
 * directamente (sin navegador) con la cabecera queda limitado.
 * </p>
 *
 * <p>
 * <b>Tampoco cuentan las que no traen la cookie {@code streambox_refresh}</b>
 * (o la traen vacía). Cada visita sin sesión hace un refresh así al arrancar:
 * {@code GET /api/users/me} da 401 y el frontend no puede saber si hay refresh
 * token, porque la cookie es HttpOnly. Si contaran, 30 visitas por minuto desde
 * la misma IP (una oficina tras un NAT) dejarían a todos en 429 nada más abrir
 * la aplicación, también a quien sí tiene sesión. No limitarlas no expone nada:
 * {@code AuthController} las rechaza con el 401 {@code SESSION_EXPIRED} sin
 * llamar al servicio (ni base de datos ni conexión del pool).
 * </p>
 *
 * <p>
 * Un valor <b>mal formado</b> sí cuenta, aunque también se rechace sin base de
 * datos. El navegador solo guarda las cookies que fija el servidor, que siempre
 * tienen buen formato, así que un valor imposible solo lo manda un cliente que
 * no es la aplicación: contarlo no perjudica a nadie legítimo y evita que esas
 * peticiones sean gratis. Y no abre un ataque nuevo contra los vecinos de NAT:
 * quien comparta la IP ya puede gastar el límite con tokens de buen formato
 * inventados. La cookie se lee con {@link WebUtils#getCookie}, igual que
 * {@code @CookieValue} en el controlador: si el filtro no cuenta una petición,
 * el controlador ve {@code null} o un valor en blanco y la rechaza antes del
 * servicio.
 * </p>
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    /**
     * Ruta del inicio de sesión. {@code SecurityConfig} la usa también para
     * hacerla pública, para que lo público y lo limitado no se separen.
     */
    public static final String LOGIN_PATH = "/api/auth/login";

    /** Ruta del registro (pública y limitada, como {@link #LOGIN_PATH}). */
    public static final String REGISTER_PATH = "/api/users";

    /**
     * Ruta de la renovación de sesión con el refresh token (pública y
     * limitada, como {@link #LOGIN_PATH}; ver «El refresh» en la clase).
     */
    public static final String REFRESH_PATH = "/api/auth/refresh";

    /**
     * Tipos de la lista CORS-safelisted del estándar Fetch: los únicos con
     * los que otra web puede enviar un {@code POST} sin preflight.
     */
    private static final List<MediaType> CORS_SAFELISTED_TYPES = List.of(
            MediaType.TEXT_PLAIN, MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA);

    private final RequestMatcher loginRequest;
    private final RequestMatcher registerRequest;
    private final RequestMatcher refreshRequest;
    private final RateLimitProperties.Rule loginRule;
    private final RateLimitProperties.Rule registerRule;
    private final RateLimitProperties.Rule refreshRule;
    private final SlidingWindowCounter loginCounter;
    private final SlidingWindowCounter registerCounter;
    private final SlidingWindowCounter refreshCounter;
    private final SecurityErrorResponseWriter errorWriter;

    /**
     * @param properties  límites configurados
     * @param clock       reloj de la aplicación
     * @param errorWriter escritor de respuestas de error de seguridad
     * @param matchers    constructor de matchers de rutas; debe ser el mismo
     *                    que usan las reglas de autorización (Spring Boot
     *                    registra uno que tiene en cuenta
     *                    {@code spring.mvc.servlet.path}), para que el filtro
     *                    reconozca las rutas exactamente igual que ellas
     */
    public RateLimitingFilter(
            RateLimitProperties properties,
            Clock clock,
            SecurityErrorResponseWriter errorWriter,
            PathPatternRequestMatcher.Builder matchers) {

        this.loginRequest = matchers.matcher(HttpMethod.POST, LOGIN_PATH);
        this.registerRequest = matchers.matcher(HttpMethod.POST, REGISTER_PATH);
        this.refreshRequest = matchers.matcher(HttpMethod.POST, REFRESH_PATH);
        this.loginRule = properties.login();
        this.registerRule = properties.register();
        this.refreshRule = properties.refresh();
        this.loginCounter = new SlidingWindowCounter(loginRule.window(), clock, properties.maxKeys());
        this.registerCounter = new SlidingWindowCounter(registerRule.window(), clock, properties.maxKeys());
        this.refreshCounter = new SlidingWindowCounter(refreshRule.window(), clock, properties.maxKeys());
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (loginRequest.matches(request)) {
            if (!acquire(request, response, loginCounter, loginRule,
                    countsTowardsLimit(request.getContentType()))) {
                return;
            }
        } else if (registerRequest.matches(request)) {
            if (!acquire(request, response, registerCounter, registerRule,
                    countsTowardsLimit(request.getContentType()))) {
                return;
            }
        } else if (refreshRequest.matches(request)) {
            // Sin cuerpo: el criterio no es el Content-Type sino la cabecera
            // CSRF y la cookie del refresh (ver «El refresh» en el Javadoc de
            // la clase).
            if (!acquire(request, response, refreshCounter, refreshRule,
                    JwtAuthenticationFilter.hasCsrfHeader(request) && hasRefreshCookie(request))) {
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Gasta un hueco del contador de la IP, o responde 429 si no quedan.
     *
     * <p>
     * Las peticiones que otra web puede enviar sin preflight pasan sin gastar
     * nada ({@code counts} a {@code false}): se rechazarán antes del
     * controlador (415/400 en login y registro, 403 en el refresh), y
     * contarlas solo serviría para agotar el límite de la víctima. Tampoco
     * gasta un refresh sin cookie, que el controlador rechaza sin llamar al
     * servicio.
     * </p>
     *
     * @param counts si esta petición gasta presupuesto (ver
     *               {@link #countsTowardsLimit(String)} y
     *               {@link JwtAuthenticationFilter#hasCsrfHeader})
     * @return {@code true} si la petición puede continuar; {@code false} si ya
     *         se ha respondido con 429
     */
    private boolean acquire(
            HttpServletRequest request,
            HttpServletResponse response,
            SlidingWindowCounter counter,
            RateLimitProperties.Rule rule,
            boolean counts) throws IOException {

        if (!counts) {
            return true;
        }
        String ip = ClientAddress.counterKey(request.getRemoteAddr());
        if (!counter.tryAcquire(ip, rule.maxRequests())) {
            reject(request, response, counter.retryAfter(ip));
            return false;
        }
        return true;
    }

    /**
     * Indica si una petición de login o registro con este {@code Content-Type}
     * gasta el presupuesto por IP (ver la sección «Qué peticiones cuentan» de
     * la clase).
     *
     * <p>
     * No cuentan las que otra web puede provocar sin preflight y que Spring
     * MVC siempre rechaza: sin {@code Content-Type}, con uno mal formado o con
     * un tipo CORS-safelisted (se comparan tipo y subtipo, sin parámetros
     * como {@code charset} y sin distinguir mayúsculas, igual que hacen el
     * navegador y Spring). Todo lo demás cuenta, también los tipos que hoy
     * Spring rechazaría: es preferible contar de más algo que una web ajena
     * no puede enviar que dejar sin límite un tipo que algún conversor sepa
     * leer.
     * </p>
     *
     * <p>
     * Se usa el mismo valor que lee Spring MVC
     * ({@link HttpServletRequest#getContentType()}) y el mismo analizador
     * ({@link MediaType#parseMediaType(String)}), para que el filtro y el
     * controlador vean el mismo tipo.
     * </p>
     *
     * @param contentType valor de la cabecera; puede ser {@code null}
     * @return {@code true} si la petición se contabiliza
     */
    static boolean countsTowardsLimit(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
        for (MediaType safelisted : CORS_SAFELISTED_TYPES) {
            if (safelisted.equalsTypeAndSubtype(mediaType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Indica si la petición trae la cookie {@code streambox_refresh} con algún
     * valor (aunque esté mal formado: ese caso cuenta, ver «El refresh» en la
     * clase).
     *
     * <p>
     * Se busca con {@link WebUtils#getCookie}, el mismo método que usa
     * {@code @CookieValue}: si hay varias cookies con ese nombre, el filtro y
     * el controlador miran la misma (la primera).
     * </p>
     *
     * @param request petición de refresh
     * @return {@code true} si la cookie está y su valor no está en blanco
     */
    static boolean hasRefreshCookie(HttpServletRequest request) {
        Cookie cookie = WebUtils.getCookie(request, AuthCookieService.REFRESH_COOKIE_NAME);
        return cookie != null && cookie.getValue() != null && !cookie.getValue().isBlank();
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            Duration retryAfter) throws IOException {

        long seconds = Math.max((retryAfter.toMillis() + 999) / 1000, 1);

        response.setHeader("Retry-After", String.valueOf(seconds));
        errorWriter.write(
                request,
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.RATE_LIMIT_EXCEEDED,
                "Demasiadas peticiones. Inténtalo de nuevo en " + seconds + " segundos.");
    }
}
