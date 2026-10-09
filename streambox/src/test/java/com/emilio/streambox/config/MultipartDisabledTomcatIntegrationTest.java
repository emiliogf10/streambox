package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;

import jakarta.servlet.MultipartConfigElement;

/**
 * La API no analiza cuerpos {@code multipart/form-data}
 * ({@code spring.servlet.multipart.enabled=false} en
 * {@code application.properties}), probado contra el Tomcat embebido real.
 *
 * <p>
 * Bug que protege: con el multipart activo (lo que hace Spring Boot por
 * defecto), {@code DispatcherServlet} analizaba cualquier multipart (hasta
 * 10 MB, guardando las partes en ficheros temporales) antes de descubrir que
 * ningún endpoint lo acepta. Lo podía hacer cualquiera en las rutas públicas y,
 * tras la pista NV-A, un multipart al login o al registro ni siquiera cuenta en
 * el límite por IP.
 * </p>
 *
 * <p>
 * <b>Por qué con Tomcat y no con MockMvc.</b> MockMvc no analiza el multipart
 * como lo hace el servidor: no se vería la diferencia. Aquí se envía una parte
 * de algo más de 1 MB, por encima del límite por archivo que Spring Boot fija
 * por defecto ({@code max-file-size=1MB}). Si el cuerpo se analizara, el
 * análisis fallaría por tamaño y la respuesta sería 413 (y en el logout, que no
 * tiene cuerpo, también); sin análisis, el login y el registro responden 415 y
 * el logout funciona (204). Sin el arreglo, los tres tests de peticiones
 * reciben 413.
 * </p>
 *
 * <p>
 * El cuerpo se queda por debajo de los 2 MB que Tomcat descarta por su cuenta
 * ({@code maxSwallowSize}) para que la conexión no se corte antes de leer la
 * respuesta. Sin {@code @Transactional}: el servidor atiende en otro hilo y
 * estas peticiones no crean datos (el registro se rechaza antes del
 * controlador).
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MultipartDisabledTomcatIntegrationTest {

    private static final String BOUNDARY = "----streambox-multipart-test";

    /** Algo más de 1 MB: supera el {@code max-file-size} por defecto de Spring Boot. */
    private static final int PART_SIZE = 1024 * 1024 + 150_000;

    @Value("${local.server.port}") private int port;
    @Autowired private ApplicationContext context;
    @Autowired private DispatcherServlet dispatcherServlet;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * Sin {@link MultipartResolver} ni {@link MultipartConfigElement}:
     * {@code DispatcherServlet} no intenta resolver el multipart y Tomcat no
     * analiza las partes aunque alguien llame a {@code getParameter}.
     */
    @Test
    void noHayResolutorNiConfiguracionMultipart() {
        assertNull(dispatcherServlet.getMultipartResolver(), "DispatcherServlet no debe tener MultipartResolver");
        assertArrayEquals(new String[0], context.getBeanNamesForType(MultipartResolver.class));
        assertArrayEquals(new String[0], context.getBeanNamesForType(MultipartConfigElement.class));
    }

    /** El login y el registro ({@code POST /api/users}) rechazan el multipart con 415 sin analizarlo. */
    @ParameterizedTest
    @ValueSource(strings = { "/api/auth/login", "/api/users" })
    void unMultipartGrandeAlLoginOAlRegistroDa415SinAnalizarse(String path) throws Exception {
        HttpResponse<String> response = postMultipart(path);

        assertEquals(415, response.statusCode(), "con 413 el cuerpo se ha analizado: " + response.body());
        assertTrue(response.body().contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\""), response.body());
    }

    /**
     * El logout, público y sin cuerpo, ignora el multipart y cierra la sesión
     * con normalidad: antes lo analizaba y respondía 413. Lleva la cabecera
     * CSRF que exige el logout: sin ella el filtro respondería 403 antes de
     * llegar a {@code DispatcherServlet} y el test no comprobaría el análisis.
     */
    @Test
    void unMultipartGrandeAlLogoutNoSeAnaliza() throws Exception {
        HttpResponse<String> response = postMultipart("/api/auth/logout", true);

        assertEquals(204, response.statusCode(), "con 413 el cuerpo se ha analizado: " + response.body());
    }

    /** {@code POST} multipart sin la cabecera CSRF (login y registro no la exigen). */
    private HttpResponse<String> postMultipart(String path) throws Exception {
        return postMultipart(path, false);
    }

    /**
     * {@code POST} con un campo de texto y un archivo de {@value #PART_SIZE} bytes.
     *
     * @param path         ruta
     * @param csrfHeader   si se envía {@code X-Requested-With: StreamBox}
     */
    private HttpResponse<String> postMultipart(String path, boolean csrfHeader) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"email\"\r\n\r\n"
                + "nadie@test.com\r\n"
                + "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"relleno.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        byte[] padding = new byte[PART_SIZE];
        Arrays.fill(padding, (byte) 'a');
        body.writeBytes(padding);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (csrfHeader) {
            request.header("X-Requested-With", "StreamBox");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
