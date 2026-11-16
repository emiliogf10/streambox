package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.http.Cookie;

/**
 * Tests unitarios de {@link RequestCookies}: las cookies se leen tal como
 * llegan (sin decodificar como URL, incidencia QA-DC-1), en su orden y con
 * tope.
 */
class RequestCookiesTest {

    /** Un {@code %} mal formado llega tal cual: nunca se decodifica (ni falla). */
    @Test
    void elValorNoSeDecodifica() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("a", "%zz"), new Cookie("b", "%76%31"));

        assertEquals("%zz", RequestCookies.first(request, "a"));
        assertEquals(List.of("%76%31"), RequestCookies.values(request, "b", 5));
    }

    /** {@code first} devuelve la primera con ese nombre, aunque esté vacía (como {@code WebUtils.getCookie}). */
    @Test
    void firstDevuelveLaPrimeraConEseNombre() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("otra", "x"), new Cookie("a", ""), new Cookie("a", "segunda"));

        assertEquals("", RequestCookies.first(request, "a"));
        assertNull(RequestCookies.first(request, "falta"));
        assertNull(RequestCookies.first(new MockHttpServletRequest(), "a"));
    }

    /** {@code values} respeta el orden de llegada, salta otros nombres y corta en el tope. */
    @Test
    void valuesRespetaElOrdenYElTope() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        List<Cookie> cookies = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            cookies.add(new Cookie("a", "v" + i));
            cookies.add(new Cookie("otra", "x" + i));
        }
        request.setCookies(cookies.toArray(Cookie[]::new));

        assertEquals(List.of("v0", "v1", "v2"), RequestCookies.values(request, "a", 3));
        assertEquals(List.of(), RequestCookies.values(request, "falta", 3));
        assertEquals(List.of(), RequestCookies.values(new MockHttpServletRequest(), "a", 3));
    }

    @Test
    void unTopeNoPositivoEsUnErrorDeProgramacion() {
        assertThrows(IllegalArgumentException.class,
                () -> RequestCookies.values(new MockHttpServletRequest(), "a", 0));
    }
}
