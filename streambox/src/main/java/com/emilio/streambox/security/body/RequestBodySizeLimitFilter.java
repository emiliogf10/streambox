package com.emilio.streambox.security.body;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.emilio.streambox.dto.ErrorCode;
import com.emilio.streambox.security.SecurityErrorResponseWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Pone un tope al tamaño del cuerpo de las peticiones: 413
 * {@code PAYLOAD_TOO_LARGE} por encima de {@code streambox.http.max-request-body}
 * (64 KB por defecto).
 *
 * <h2>Qué problema resuelve (BAJA-2 del plan)</h2>
 * <p>
 * Jackson lee el cuerpo <b>entero</b> antes de que Bean Validation mire los
 * {@code @Size}: una sinopsis de 50 MB se leía, se convertía en un
 * {@code String} de 50 MB y solo entonces daba 400. En Docker lo frenaba el
 * 1 MB por defecto de nginx; contra el backend directo (o con otro proxy),
 * nada. Y el login y el registro son públicos: cualquiera podía gastar memoria
 * y CPU del servidor a coste cero.
 * </p>
 *
 * <h2>Dos casos</h2>
 * <ul>
 *   <li><b>Con {@code Content-Length} mayor que el límite:</b> se responde 413
 *       aquí mismo, sin leer ni un byte del cuerpo, sin consultar la base de
 *       datos y sin gastar el límite por IP del login o el registro (este
 *       filtro va antes que {@code RateLimitingFilter}).</li>
 *   <li><b>Sin {@code Content-Length}</b> ({@code Transfer-Encoding: chunked}:
 *       el tamaño no se sabe hasta leerlo): se envuelve el {@code InputStream}
 *       con uno que cuenta los bytes y lanza {@link RequestBodyTooLargeException}
 *       en cuanto lee uno más del límite. Quien lee el cuerpo (Jackson) la
 *       recibe envuelta en una {@code HttpMessageNotReadableException} y
 *       {@code GlobalExceptionHandler} la reconoce y responde el mismo 413 (no
 *       el 400 {@code MALFORMED_REQUEST} de un JSON roto). En memoria nunca hay
 *       más de un trozo por encima del límite. Este caso sí pasa antes por el
 *       límite por IP (el tamaño solo se descubre al leer, ya en el
 *       controlador), pero gasta solo el presupuesto de quien lo envía: un
 *       navegador no puede mandar un cuerpo por trozos a otra web sin
 *       preflight, y sin CORS configurado no se lo permite.</li>
 * </ul>
 * <p>
 * Con {@code Content-Length} menor o igual, también se envuelve: Tomcat ya no
 * deja leer más de lo declarado, pero así la regla no depende de ese detalle.
 * </p>
 *
 * <p>
 * <b>Formularios por trozos: el único cuerpo que no pasa por aquí.</b> Un
 * {@code POST} {@code application/x-www-form-urlencoded} lo lee Tomcat por
 * dentro cuando alguien llama a {@code getParameter*()} (Spring lo hace al
 * preparar un {@code @RequestBody} de ese tipo), sin usar el flujo envuelto.
 * Lo cubren {@code server.tomcat.max-http-form-post-size}, igualado a este
 * límite en {@code application.properties}, y {@code GlobalExceptionHandler},
 * que convierte la {@code InvalidParameterException} de Tomcat en el mismo
 * 413 (o en 400 si el formulario está mal codificado) en lugar de un 500.
 * </p>
 *
 * <h2>Por qué dentro de la cadena de Spring Security, justo después de
 * {@code HeaderWriterFilter}</h2>
 * <p>
 * Un filtro de servlet con orden más alto (antes de toda la cadena de Spring
 * Security) sería aún más temprano, pero su 413 saldría <b>sin las cabeceras
 * de seguridad</b> ({@code X-Content-Type-Options}, {@code Cache-Control},
 * {@code X-Frame-Options}...), que las añade {@code HeaderWriterFilter}. Justo
 * después de ese filtro el 413 las lleva y sigue yendo antes de todo lo que
 * importa: el límite por IP, el filtro JWT (que consulta la base de datos),
 * la autorización y Spring MVC. Lo que va antes (el cortafuegos de Spring
 * Security, {@code CharacterEncodingFilter}...) no lee el cuerpo.
 * </p>
 *
 * <p>
 * <b>Se aplica a todas las rutas</b>, no solo a {@code /api/**}: los únicos
 * que leen cuerpos son los controladores de la API, así que fuera de ella no
 * cambia nada, y sin comparar rutas no hay forma de esquivarlo con una ruta
 * codificada ({@code /api/%61uth/login}). No es un {@code @Bean} a propósito:
 * Spring Boot registraría cualquier {@code Filter} del contexto también como
 * filtro de servlet; se crea en {@code SecurityConfig} y solo vive en la
 * cadena.
 * </p>
 *
 * <p>
 * nginx aplica el mismo límite ({@code client_max_body_size} en
 * {@code frontend/nginx/default.conf}): en Docker, un cuerpo mayor ni siquiera
 * llega al backend.
 * </p>
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestBodySizeLimitFilter.class);

    /** Mensaje del 413 para el cliente (igual por los dos caminos). */
    public static final String MESSAGE = "El cuerpo de la petición es demasiado grande";

    private final long maxBytes;

    private final SecurityErrorResponseWriter errorWriter;

    /**
     * Crea el filtro.
     *
     * @param properties  límite configurado (ya validado)
     * @param errorWriter escritor de los errores JSON de seguridad
     */
    public RequestBodySizeLimitFilter(RequestBodyLimitProperties properties, SecurityErrorResponseWriter errorWriter) {
        this.maxBytes = properties.maxRequestBody().toBytes();
        this.errorWriter = errorWriter;
    }

    /**
     * Rechaza la petición si declara un cuerpo demasiado grande; si no, la deja
     * seguir con el cuerpo vigilado.
     *
     * @param request     petición recibida
     * @param response    respuesta
     * @param filterChain resto de la cadena
     * @throws ServletException si lo lanza el resto de la cadena
     * @throws IOException      si falla la escritura del 413 o lo lanza el resto de la cadena
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (request.getContentLengthLong() > maxBytes) {
            // Solo la ruta y el tamaño declarado: nada del cuerpo, que no se lee.
            LOGGER.debug("Cuerpo de {} bytes en {}: 413 sin leerlo (límite {})",
                    request.getContentLengthLong(), request.getRequestURI(), maxBytes);
            errorWriter.write(request, response, HttpStatus.CONTENT_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, MESSAGE);
            return;
        }
        filterChain.doFilter(new LimitedBodyRequest(request, maxBytes), response);
    }

    /**
     * Petición cuyo cuerpo ({@code getInputStream()} y {@code getReader()}) no
     * puede pasar de {@code maxBytes}.
     */
    static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final long maxBytes;

        private LimitedInputStream stream;

        private BufferedReader reader;

        LimitedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        /**
         * El lector también sale del flujo vigilado: si no, quien leyera con
         * {@code getReader()} se saltaría el límite. Usa la codificación de la
         * petición o, si no la declara, ISO-8859-1, la que fija la
         * especificación de servlets.
         */
        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                String encoding = getCharacterEncoding();
                Charset charset;
                try {
                    charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
                } catch (IllegalArgumentException e) {
                    // Lo mismo que haría el contenedor con una codificación desconocida.
                    throw new UnsupportedEncodingException(encoding);
                }
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    /**
     * Flujo que cuenta los bytes leídos y lanza {@link RequestBodyTooLargeException}
     * en cuanto se lee uno más del límite. Nunca pide al flujo original más de
     * un byte por encima del límite, y una vez superado sigue fallando.
     */
    static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;

        private final long maxBytes;

        private long count;

        private boolean exceeded;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            failIfExceeded();
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            failIfExceeded();
            if (length == 0) {
                return 0;
            }
            // Como mucho un byte más de lo permitido: basta para saber que se
            // ha superado sin leer (ni guardar) el resto.
            int allowed = (int) Math.min(length, maxBytes - count + 1);
            int read = delegate.read(buffer, offset, allowed);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(int bytes) throws RequestBodyTooLargeException {
            count += bytes;
            if (count > maxBytes) {
                exceeded = true;
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }

        private void failIfExceeded() throws RequestBodyTooLargeException {
            if (exceeded) {
                throw new RequestBodyTooLargeException(maxBytes);
            }
        }
    }
}
