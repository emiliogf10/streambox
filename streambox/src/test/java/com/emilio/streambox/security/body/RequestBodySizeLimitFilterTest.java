package com.emilio.streambox.security.body;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import com.emilio.streambox.security.SecurityErrorResponseWriter;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Tests unitarios de {@link RequestBodySizeLimitFilter} y
 * {@link RequestBodyLimitProperties}: el rechazo por {@code Content-Length}, el
 * corte al leer sin él y la validación de la propiedad. El comportamiento con
 * Tomcat real (cabeceras, JSON, orden de la cadena) está en
 * {@code RequestBodySizeLimitTomcatIntegrationTest}.
 */
class RequestBodySizeLimitFilterTest {

    private static final int LIMIT = 10;

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(
            new RequestBodyLimitProperties(DataSize.ofBytes(LIMIT)), new SecurityErrorResponseWriter());

    /** Con {@code Content-Length} mayor que el límite: 413 aquí y la cadena no sigue. */
    @Test
    void conContentLengthMayorQueElLimiteResponde413SinSeguir() throws Exception {
        MockHttpServletRequest request = request(new byte[LIMIT + 1], false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertNull(chain.getRequest(), "no debe llegar al resto de la cadena");
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentType().startsWith("application/json"), response.getContentType());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("\"code\":\"PAYLOAD_TOO_LARGE\""),
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** Justo en el límite se puede leer todo (y la cadena recibe la petición envuelta). */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void justoEnElLimiteSeLeeEntero(boolean chunked) throws Exception {
        byte[] body = "0123456789".getBytes(StandardCharsets.US_ASCII);
        HttpServletRequest wrapped = passThrough(request(body, chunked));

        assertArrayEquals(body, wrapped.getInputStream().readAllBytes());
    }

    /** Sin {@code Content-Length}, al leer un byte más del límite salta la excepción, y sigue saltando. */
    @Test
    void sinContentLengthCortaAlLeerUnByteMasDelLimite() throws Exception {
        HttpServletRequest wrapped = passThrough(request(new byte[LIMIT * 100], true));
        ServletInputStream stream = wrapped.getInputStream();

        assertThrows(RequestBodyTooLargeException.class, stream::readAllBytes);
        assertThrows(RequestBodyTooLargeException.class, stream::read);
        assertThrows(RequestBodyTooLargeException.class, () -> stream.read(new byte[4], 0, 4));
    }

    /** Leyendo byte a byte: los {@value #LIMIT} primeros salen, el siguiente falla. */
    @Test
    void leyendoByteAByteFallaEnElSiguienteAlLimite() throws Exception {
        ServletInputStream stream = passThrough(request(new byte[LIMIT + 5], true)).getInputStream();
        for (int i = 0; i < LIMIT; i++) {
            assertEquals(0, stream.read());
        }

        assertThrows(RequestBodyTooLargeException.class, stream::read);
    }

    /** {@code getReader()} sale del mismo flujo vigilado: no es una forma de saltarse el límite. */
    @Test
    void elLectorTambienEstaLimitado() throws Exception {
        MockHttpServletRequest request = request("x".repeat(LIMIT * 3).getBytes(StandardCharsets.UTF_8), true);
        request.setCharacterEncoding("UTF-8");
        BufferedReader reader = passThrough(request).getReader();

        IOException thrown = assertThrows(IOException.class, () -> {
            char[] buffer = new char[8];
            while (reader.read(buffer) != -1) {
                // Leer hasta el final (o hasta la excepción).
            }
        });
        assertTrue(RequestBodyTooLargeException.isCauseOf(thrown), thrown.toString());
    }

    /** La causa se reconoce aunque venga envuelta (como la envuelven Jackson y Spring). */
    @Test
    void reconoceLaCausaEnvuelta() {
        Exception wrapped = new IllegalStateException("conversor",
                new RuntimeException("jackson", new RequestBodyTooLargeException(LIMIT)));

        assertTrue(RequestBodyTooLargeException.isCauseOf(wrapped));
        assertTrue(!RequestBodyTooLargeException.isCauseOf(new IOException("otra cosa")));
        assertTrue(!RequestBodyTooLargeException.isCauseOf(null));
    }

    // ------------------------------------------------------------------
    // RequestBodyLimitProperties
    // ------------------------------------------------------------------

    /** Sin configurar, 64 KB. */
    @Test
    void sinValorUsaElDePorDefecto() {
        assertEquals(DataSize.ofKilobytes(64), new RequestBodyLimitProperties(null).maxRequestBody());
    }

    /** Cero, negativo o más de 10 MB (p. ej. «64MB» por «64KB») impiden arrancar. */
    @ParameterizedTest
    @ValueSource(longs = { 0, -1, 10L * 1024 * 1024 + 1 })
    void rechazaValoresAbsurdos(long bytes) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new RequestBodyLimitProperties(DataSize.ofBytes(bytes)));
        assertTrue(thrown.getMessage().contains("streambox.http.max-request-body"), thrown.getMessage());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Petición con cuerpo; con {@code chunked}, sin {@code Content-Length}
     * (como la de un cliente que envía por trozos).
     */
    private static MockHttpServletRequest request(byte[] body, boolean chunked) {
        MockHttpServletRequest request = chunked
                ? new MockHttpServletRequest("PUT", "/api/users/me/genres") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }

                    @Override
                    public int getContentLength() {
                        return -1;
                    }
                }
                : new MockHttpServletRequest("PUT", "/api/users/me/genres");
        request.setContent(body);
        request.setContentType("application/json");
        return request;
    }

    /** Pasa la petición por el filtro y devuelve la que recibe el resto de la cadena. */
    private HttpServletRequest passThrough(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "la cadena debe seguir");
        return (HttpServletRequest) chain.getRequest();
    }
}
