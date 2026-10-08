package com.emilio.streambox.exception;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.ErrorResponse;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Responde en {@code /error} con el {@link ErrorResponse} de la API, siempre en
 * JSON. Sustituye al {@code BasicErrorController} de Spring Boot.
 *
 * <p>
 * <b>Cuándo se llega aquí.</b> Casi todos los errores los resuelve
 * {@link GlobalExceptionHandler} dentro de Spring MVC. Los que nacen fuera de él
 * no: una excepción que escapa de un filtro, o un {@code response.sendError(...)}
 * de Tomcat o de un filtro. En esos casos Tomcat reenvía la petición a
 * {@code /error} (despacho {@link DispatcherType#ERROR}) dejando en atributos de
 * la petición el estado ({@code jakarta.servlet.error.status_code}), la ruta
 * original ({@code jakarta.servlet.error.request_uri}) y la excepción, si la hay.
 * </p>
 *
 * <p>
 * <b>El problema que resuelve.</b> {@code BasicErrorController} respondía con su
 * propio formato ({@code timestamp}, {@code status}, {@code error},
 * {@code path}), sin el {@code code} estable de la API ni un mensaje en
 * español, o directamente con su página HTML «whitelabel» si el cliente mandaba
 * {@code Accept: text/html} (un navegador, por ejemplo). El frontend
 * decide por {@code status} y {@code code}: con ese cuerpo no tenía
 * {@code code}. Ahora todos los errores de la API tienen la misma forma.
 * </p>
 *
 * <p>
 * <b>Cómo sustituye al de Spring Boot.</b> {@code ErrorMvcAutoConfiguration}
 * crea {@code BasicErrorController} con
 * {@code @ConditionalOnMissingBean(ErrorController.class)} (comprobado en
 * Spring Boot 4.1): basta con que exista este bean, que implementa
 * {@link ErrorController}. La ruta usa la misma propiedad que el de Boot
 * ({@code spring.web.error.path}, por defecto {@code /error}), que es la que
 * Boot registra como página de error en Tomcat.
 * </p>
 *
 * <p>
 * <b>Por qué vive en {@code exception} y no en {@code controller}.</b> No es un
 * endpoint de la API (no se documenta y el cliente no lo pide): es el último
 * eslabón del tratamiento de errores, junto a {@link GlobalExceptionHandler}, y
 * comparte con él la tabla de códigos {@link GenericHttpError}, que así puede
 * quedarse como detalle interno del paquete.
 * </p>
 *
 * <p>
 * <b>Por qué devuelve {@link ResponseEntity}</b> (la convención de los
 * controladores es devolver el DTO con {@code @ResponseStatus}): el estado no es
 * fijo, depende del error, y hay que fijar {@code Content-Type: application/json}
 * para que Spring no lo negocie con la cabecera {@code Accept} del cliente. Es
 * el mismo motivo por el que {@link GlobalExceptionHandler} lo fija en todos sus
 * errores (ver su Javadoc): con {@code Accept: text/html} o
 * {@code application/yaml}, negociar acabaría en un error al escribir el error.
 * Tampoco se declara {@code produces} en el mapeo: con él, esas peticiones ni
 * siquiera llegarían a este método.
 * </p>
 *
 * <p>
 * <b>Qué no se cuenta al cliente.</b> Ni el mensaje de la excepción ni el de
 * {@code sendError} ({@code jakarta.servlet.error.message}) ni la traza: pueden
 * revelar clases, rutas internas o datos. Los mensajes salen de
 * {@link GenericHttpError}. La excepción solo va al log.
 * </p>
 *
 * <p>
 * <b>Seguridad.</b> {@code SecurityConfig} permite el despacho {@code ERROR}
 * (si no, este reenvío recibía un 401 falso), pero un {@code GET /error} pedido
 * por el cliente es un despacho {@code REQUEST} y sigue exigiendo token. Si un
 * usuario autenticado lo pide, no hay ningún error que describir: se responde
 * 404, como cualquier ruta que no es de la API (ver
 * {@link #statusOf(HttpServletRequest)}).
 * </p>
 */
@Hidden
@RestController
@RequestMapping("${spring.web.error.path:${error.path:/error}}")
public class ApiErrorController implements ErrorController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorController.class);

    /** Cabecera que impide al navegador interpretar el JSON como otro tipo (ver {@link #error}). */
    private static final String NOSNIFF_HEADER = "X-Content-Type-Options";

    /**
     * Atiende el despacho de error con cualquier método HTTP: sin
     * {@code method} en el mapeo, Spring MVC lo asocia a todos (también a
     * {@code OPTIONS} en el despacho de error), porque el reenvío conserva el
     * método de la petición original.
     *
     * <p>
     * <b>{@code X-Content-Type-Options: nosniff}.</b> Se fija aquí porque hay
     * errores que nacen en Tomcat antes de cualquier filtro (por ejemplo, el 405
     * con el que rechaza {@code TRACE}) y, en el despacho de error, el
     * {@code HeaderWriterFilter} de Spring Security no actúa (es un
     * {@code OncePerRequestFilter}, que por defecto no filtra ese despacho): la
     * respuesta salía sin la cabecera, y el cuerpo repite la ruta que envió el
     * cliente. Se usa {@code setHeader}, que sustituye el valor si ya existe, y
     * no {@code addHeader} ni las cabeceras del {@link ResponseEntity} (que se
     * añaden): si la petición ya pasó por la seguridad, la cabecera ya está y
     * así no sale repetida. A la inversa, si la seguridad escribe sus cabeceras
     * después, solo añade las que faltan.
     * </p>
     *
     * @param request  petición reenviada por Tomcat, con los atributos del error
     * @param response respuesta en la que se fija {@code nosniff}
     * @return el error en formato {@link ErrorResponse}, siempre en JSON
     */
    @RequestMapping
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = statusOf(request);
        String path = originalPath(request);
        log(request, status, path);
        response.setHeader(NOSNIFF_HEADER, "nosniff");

        GenericHttpError generic = GenericHttpError.forStatus(status);
        ErrorResponse body = new ErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(),
                generic.code(), generic.message(), path);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    /**
     * Estado HTTP del error.
     *
     * <ul>
     * <li>Con el atributo {@code jakarta.servlet.error.status_code} (lo pone
     * Tomcat al reenviar; el cliente no puede fijar atributos de la petición):
     * ese estado. Si no es un código de error conocido (4xx/5xx estándar), 500:
     * no hay forma honesta de describir otra cosa y {@link ErrorResponse}
     * necesita la frase estándar del estado.</li>
     * <li>Sin el atributo en un despacho {@code ERROR}: 500, el error genérico
     * del servidor.</li>
     * <li>Sin el atributo en cualquier otro despacho: el cliente ha pedido
     * {@code /error} directamente. No hay error que describir y esa ruta no es
     * de la API: 404. Responder 500 aquí haría que cualquier usuario autenticado
     * pudiera llenar el log de errores falsos.</li>
     * </ul>
     *
     * @param request petición atendida
     * @return estado con el que se responde
     */
    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            return status != null && status.isError() ? status : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return request.getDispatcherType() == DispatcherType.ERROR
                ? HttpStatus.INTERNAL_SERVER_ERROR
                : HttpStatus.NOT_FOUND;
    }

    /**
     * Ruta que pidió el cliente, no {@code /error}: es la que le sirve para
     * saber qué falló y la misma que pone {@link GlobalExceptionHandler}
     * ({@code getRequestURI()}, sin la query string).
     */
    private static String originalPath(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri instanceof String value ? value : request.getRequestURI();
    }

    /**
     * Los 5xx son fallos del servidor: van a {@code ERROR} con la excepción
     * completa (si la hay) para poder diagnosticarlos. Los 4xx son errores del
     * cliente que cualquiera puede provocar en masa: solo en {@code DEBUG}, como
     * hace Spring con los suyos. Solo se registran el método y la ruta; nada de
     * cabeceras, cuerpo ni parámetros.
     */
    private static void log(HttpServletRequest request, HttpStatus status, String path) {
        if (status.is5xxServerError()) {
            Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            LOGGER.error("Error no controlado al procesar {} {} (respuesta {})", request.getMethod(), path,
                    status.value(), exception instanceof Throwable throwable ? throwable : null);
        } else {
            LOGGER.debug("Error {} al procesar {} {}", status.value(), request.getMethod(), path);
        }
    }
}
