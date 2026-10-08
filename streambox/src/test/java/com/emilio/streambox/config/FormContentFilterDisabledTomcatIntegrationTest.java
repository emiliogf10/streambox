package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.FormContentFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * La aplicación no lee cuerpos {@code application/x-www-form-urlencoded} de
 * {@code PUT}/{@code PATCH}/{@code DELETE} antes de la seguridad
 * ({@code spring.mvc.formcontent.filter.enabled=false} en
 * {@code application.properties}), probado contra el Tomcat embebido real.
 *
 * <p>
 * <b>Bug que protege.</b> Spring Boot registra por defecto
 * {@link FormContentFilter} (orden -9900, antes de
 * {@code springSecurityFilterChain}). Para esos tres métodos con un formulario
 * copiaba el cuerpo entero a un {@code String}, sin límite (el
 * {@code maxPostSize} de Tomcat solo aplica a {@code POST}), y lo decodificaba.
 * Cualquiera, sin autenticarse, podía enviar cuerpos enormes a cualquier ruta:
 * un vector de agotamiento de memoria.
 * </p>
 *
 * <p>
 * <b>Cómo se demuestra que el cuerpo no se lee.</b> El formulario termina en
 * {@code %zz}, un escape inválido. Si el filtro lo lee, falla al decodificarlo
 * con una excepción que sale de la cadena de filtros; Tomcat reenvía a
 * {@code /error} y {@code ApiErrorController} responde 500
 * {@code INTERNAL_ERROR} (con la ruta pedida: el despacho de error ya no da el
 * 401 falso con {@code "path":"/error"} que daba cuando se escribió este test).
 * Sin el filtro, el cuerpo no se toca y la seguridad responde 401 con la ruta
 * pedida. El cuerpo
 * (~1 MB) se queda por debajo de los 2 MB que Tomcat descarta por su cuenta
 * ({@code maxSwallowSize}) para que la conexión no se corte antes de leer la
 * respuesta. Sin el arreglo fallan los dos tests.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FormContentFilterDisabledTomcatIntegrationTest {

    /** Algo más de 1 MB de formulario. */
    private static final int FIELD_SIZE = 1024 * 1024;

    @Value("${local.server.port}") private int port;
    @Autowired private ApplicationContext context;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** No existe ningún {@link FormContentFilter} registrado. */
    @Test
    void noHayFiltroDeFormularios() {
        assertArrayEquals(new String[0], context.getBeanNamesForType(FormContentFilter.class));
    }

    /**
     * Un formulario grande y mal codificado de un anónimo no se lee: 401 con la
     * ruta real, como cualquier otra petición sin token.
     */
    @ParameterizedTest
    @ValueSource(strings = { "PUT", "PATCH", "DELETE" })
    void unFormularioGrandeAnonimoNoSeLeeAntesDeLaSeguridad(String method) throws Exception {
        String path = "/api/movies/1";
        String form = "a=" + "x".repeat(FIELD_SIZE) + "&b=%zz";

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode(),
                "un 500 INTERNAL_ERROR indica que el cuerpo se ha leído (y fallado al decodificarse) "
                        + "antes de la seguridad: " + response.body());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(path, body.path("path").asText(),
                "el 401 debe ser el de la petición original, con la ruta pedida: " + response.body());
    }
}
