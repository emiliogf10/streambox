package com.emilio.streambox.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Lee las cookies de una petición <b>tal como llegan</b>, sin decodificarlas.
 *
 * <h2>Por qué no {@code @CookieValue}</h2>
 * <p>
 * {@code @CookieValue} de Spring MVC decodifica el valor como si fuera parte de
 * una URL ({@code UrlPathHelper.decodeRequestString}). El {@code %} es un
 * carácter válido en el valor de una cookie (RFC 6265) y Tomcat lo deja pasar,
 * así que con {@code Cookie: streambox_device=%zz} la decodificación lanza
 * {@code IllegalArgumentException("Invalid encoded sequence")} antes de llegar
 * al controlador: un 500 {@code INTERNAL_ERROR} (y una traza de error en el log)
 * por cada petición, sin autenticar. Además la decodificación no aporta nada:
 * las cookies propias (JWT, refresh token, cookie de dispositivo) son Base64URL
 * y puntos, nunca llevan {@code %}, y decodificar daría a un mismo valor varias
 * escrituras posibles ({@code %76%31} y {@code v1}).
 * </p>
 *
 * <p>
 * Aquí el valor nunca se decodifica: un valor mal formado llega tal cual a la
 * validación de cada cookie, que lo rechaza como a cualquier otro valor que no
 * es suyo (la misma respuesta que sin cookie o con una cookie inválida).
 * </p>
 *
 * <h2>Orden y duplicados</h2>
 * <p>
 * Se respeta el orden de {@link HttpServletRequest#getCookies()}, que en Tomcat
 * es el de la cabecera {@code Cookie} (y el navegador pone primero la de ruta
 * más específica). {@link #first} devuelve la primera con ese nombre, igual que
 * {@code WebUtils.getCookie}: así el filtro del límite por IP y el controlador
 * miran siempre la misma cookie.
 * </p>
 */
public final class RequestCookies {

    private RequestCookies() {
    }

    /**
     * Valor de la primera cookie con ese nombre, sin decodificar.
     *
     * @param request petición
     * @param name    nombre de la cookie
     * @return su valor tal como llegó (puede estar vacío), o {@code null} si
     *         no viene ninguna cookie con ese nombre
     */
    public static String first(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Valores de las cookies con ese nombre, en el orden en que llegan y sin
     * decodificar, hasta un máximo.
     *
     * <p>
     * El tope acota el trabajo de quien procese los valores: una petición con
     * cientos de cookies repetidas no puede convertirse en cientos de
     * comprobaciones. Las que sobran se ignoran.
     * </p>
     *
     * @param request petición
     * @param name    nombre de la cookie
     * @param max     cuántos valores, como mucho (mayor que cero)
     * @return lista inmutable con los valores (vacía si no viene ninguna)
     */
    public static List<String> values(HttpServletRequest request, String name, int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("max debe ser mayor que cero");
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(Math.min(max, cookies.length));
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                values.add(cookie.getValue());
                if (values.size() == max) {
                    break;
                }
            }
        }
        // unmodifiableList y no List.copyOf, que lanza NullPointerException con
        // un null: una cookie construida a mano (p. ej. en MockMvc) puede
        // llegar sin valor, y eso nunca debe acabar en un 500.
        return Collections.unmodifiableList(values);
    }
}
