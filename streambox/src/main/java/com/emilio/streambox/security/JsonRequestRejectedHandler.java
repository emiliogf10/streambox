package com.emilio.streambox.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import com.emilio.streambox.dto.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Responde 400 {@code MALFORMED_REQUEST} en JSON ({@code ErrorResponse}) a las
 * peticiones que rechaza el cortafuegos HTTP de Spring Security
 * ({@code StrictHttpFirewall}).
 *
 * <p>
 * <b>Qué rechaza el cortafuegos.</b> URLs que podrían interpretarse de forma
 * distinta según quién las lea y servir para saltarse una regla de
 * autorización: parámetros de matriz ({@code ;x=1}), barras dobles
 * ({@code //}), {@code /../}, puntos, barras o {@code %} codificados
 * ({@code %2e}, {@code %2f}, {@code %25}), caracteres de control, además de
 * métodos HTTP no estándar y cabeceras con caracteres no válidos. Rechazarlas
 * está bien; el problema era <i>cómo</i>.
 * </p>
 *
 * <p>
 * <b>El problema que resuelve.</b> El manejador por defecto
 * ({@code HttpStatusRequestRejectedHandler}) llama a
 * {@code response.sendError(400)}. {@code sendError} no escribe la respuesta:
 * pide a Tomcat que reenvíe la petición a {@code /error}. Ese reenvío vuelve a
 * pasar por la seguridad sin el usuario del token (el cortafuegos corta antes
 * de {@code JwtAuthenticationFilter}), y la autorización lo rechazaba con el
 * 401 del punto de entrada: el cliente recibía un <b>401 falso</b>
 * {@code INVALID_CREDENTIALS} con {@code "path":"/error"}, y el frontend, que
 * cierra la sesión ante un 401, echaba al usuario por un error de formato. Este
 * manejador escribe el 400 directamente, sin {@code sendError}, así que no hay
 * reenvío.
 * </p>
 *
 * <p>
 * <b>Qué se cuenta al cliente.</b> Un mensaje propio y genérico. El de la
 * excepción describe la regla exacta que saltó y la cadena «maliciosa»
 * encontrada: es información interna y sirve para afinar un ataque. Se
 * registra solo en {@code DEBUG}, como hace Spring, porque cualquiera puede
 * provocar este rechazo en masa (los escáneres lo hacen) y a {@code WARN}
 * llenaría el log.
 * </p>
 *
 * <p>
 * <b>Cabeceras.</b> Al rechazarse la petición antes de la cadena de filtros, el
 * {@code HeaderWriterFilter} de Spring Security no llega a ejecutarse y la
 * respuesta saldría sin sus cabeceras. Se añade {@code X-Content-Type-Options:
 * nosniff}, la que importa aquí: el cuerpo repite la ruta enviada por el
 * cliente, y así ningún navegador intenta interpretarlo como otra cosa que
 * JSON. (Detrás de nginx, el resto las añade él con {@code always}.)
 * </p>
 */
public class JsonRequestRejectedHandler implements RequestRejectedHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonRequestRejectedHandler.class);

    /** Mensaje para el cliente. Genérico a propósito (ver el Javadoc de la clase). */
    public static final String MESSAGE = "La petición no es válida: la URL, el método o alguna cabecera "
            + "tienen un formato que no se admite";

    private final SecurityErrorResponseWriter errorWriter;

    /**
     * @param errorWriter escritor de los errores JSON de seguridad (el mismo de
     *                    los 401 y 403)
     */
    public JsonRequestRejectedHandler(SecurityErrorResponseWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    /**
     * Escribe el 400 en JSON. Si la respuesta ya se ha enviado (el cortafuegos
     * también puede rechazar más tarde, al leer una cabecera no válida durante
     * la petición), no se puede cambiar y solo se registra.
     *
     * @param request                  petición rechazada
     * @param response                 respuesta en la que se escribe el error
     * @param requestRejectedException motivo del rechazo (no se envía al cliente)
     * @throws IOException si falla la escritura
     */
    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestRejectedException requestRejectedException) throws IOException {

        LOGGER.debug("Petición rechazada por el cortafuegos HTTP: {}", requestRejectedException.getMessage());

        if (response.isCommitted()) {
            return;
        }
        // Descarta lo que se hubiera escrito ya en el búfer (rechazo tardío);
        // las cabeceras se mantienen y el escritor fija estado y tipo.
        response.resetBuffer();
        response.setHeader("X-Content-Type-Options", "nosniff");
        errorWriter.write(request, response, HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, MESSAGE);
    }
}
