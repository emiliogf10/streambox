package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emilio.streambox.dto.MovieRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Casos límite de {@link HttpsUrlValidator} que no cubre {@link HttpsUrlValidatorTest}
 * (revisión independiente de QA).
 *
 * <p>
 * El validador delega el análisis en {@link java.net.URI}, así que estos tests
 * fijan qué hace {@code URI} con las formas raras que un administrador podría
 * pegar en el formulario: IPv6, puertos, rutas con {@code ..}, escapes
 * {@code %} mal formados, fragmentos y hosts que no son nombres válidos. Si
 * alguien cambiara {@code URI} por una expresión regular, o por otra clase de
 * análisis más permisiva, estos casos lo detectarían.
 * </p>
 *
 * <p>
 * Se valida directamente sobre {@link MovieRequest} (y no sobre un registro de
 * prueba) para comprobar también que los mensajes que llegan al cliente son
 * las constantes del contrato y no un texto del framework.
 * </p>
 */
class HttpsUrlValidatorEdgeCasesTest {

    private static final String VALID_VIDEO = "https://videos.streambox.example/watch/dune";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * Formas poco habituales pero correctas según el estándar. Rechazarlas
     * obligaría al administrador a "arreglar" URL que el navegador carga sin
     * problema. {@code https://host/../x} es inocua: el {@code ..} lo resuelve
     * el navegador dentro del host externo, y el servidor nunca abre la URL.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://[::1]/x.jpg",                    // IPv6 entre corchetes
            "https://[2001:db8::1]:8443/x.jpg",       // IPv6 con puerto
            "https://127.0.0.1/x.jpg",                // IPv4
            "https://cdn.example.com:443/x.jpg",      // puerto explícito
            "https://CDN.EXAMPLE.COM/X.JPG",          // host y ruta en mayúsculas
            "https://cdn.example.com/../x.jpg",       // ".." en un host externo
            "https://cdn.example.com/a/./b/../x.jpg",
            "https://cdn.example.com?v=2",            // query sin ruta
            "https://cdn.example.com#portada",        // fragmento sin ruta
            "https://cdn.example.com/x.jpg?a=1&b=%20#f",
            "https://cdn.example.com/%41%c3%b1.jpg",  // escapes bien formados (minúsculas incluidas)
            "https://xn--espaa-rta.example/x.jpg"     // dominio internacional en punycode
    })
    void aceptaFormasCorrectasPocoHabituales(String url) {
        assertNoErrors(request(url, url));
    }

    /**
     * {@code %} mal formados, IPv6 sin cerrar, puertos no numéricos, doble
     * {@code #} y hosts que no son nombres válidos: {@code URI} los rechaza o
     * no encuentra host, y el cliente recibe solo el mensaje del contrato.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/%zz.jpg",        // escape con letras no hexadecimales
            "https://cdn.example.com/%",              // escape incompleto al final
            "https://cdn.example.com/%4",
            "https://cdn.example.com/x.jpg?a=%G0",    // escape mal formado en la query
            "https://cdn.example.com/x.jpg#a%",       // y en el fragmento
            "https://[::1/x.jpg",                     // IPv6 sin corchete de cierre
            "https://::1/x.jpg",                      // IPv6 sin corchetes
            "https://[::1]:abc/x.jpg",                // puerto no numérico con IPv6
            "https://cdn.example.com:-1/x.jpg",       // puerto negativo
            "https://cdn.example.com/x.jpg#a#b",      // dos fragmentos
            "https://.example.com/x.jpg",             // etiqueta vacía
            "https://-cdn.example.com/x.jpg",         // etiqueta que empieza por guion
            "https://999.999.999.999/x.jpg",          // IPv4 imposible
            "https://cdn.example.com/x[1].jpg",       // corchetes fuera del host
            "https://cdn.example.com/x|y.jpg",
            "https://cdn.example.com/x^y.jpg",
            "https://cdn.example.com/x`y.jpg",
            "https://cdn.example.com/{x}.jpg"
    })
    void rechazaEscapesMalFormadosYHostsInvalidosConElMensajeDelContrato(String url) {
        MovieRequest request = request(url, url);

        assertEquals(Set.of("imageUrl", "videoUrl"), fieldsWithErrors(request), url);
        assertEquals(MovieRequest.IMAGE_URL_FORMAT_MESSAGE, onlyMessage(request, "imageUrl"));
        assertEquals(MovieRequest.VIDEO_URL_FORMAT_MESSAGE, onlyMessage(request, "videoUrl"));
    }

    /** Los límites exactos de longitud con una portada propia, no solo con https. */
    @Test
    void unaPortadaPropiaDe500CaracteresValeYDe501DaElMensajeDeLongitud() {
        String cover500 = "/covers/" + "a".repeat(500 - "/covers/".length());
        String cover501 = cover500 + "b";
        assertEquals(500, cover500.length());

        assertNoErrors(request(cover500, VALID_VIDEO));
        assertEquals(MovieRequest.IMAGE_URL_SIZE_MESSAGE, onlyMessage(request(cover501, VALID_VIDEO), "imageUrl"));
    }

    /**
     * Un {@code @}, {@code :} o {@code ;} en la ruta o en la query no son
     * credenciales: solo cuentan los que van en la autoridad (antes del primer
     * {@code /}). Rechazarlos rompería URL legítimas de CDN.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/perfil/@usuario/x.jpg",
            "https://cdn.example.com/x.jpg?ref=a@b.example",
            "https://cdn.example.com/a:b;c=d/x.jpg"
    })
    void unaArrobaFueraDeLaAutoridadNoSonCredenciales(String url) {
        assertNoErrors(request(url, url));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static MovieRequest request(String imageUrl, String videoUrl) {
        return new MovieRequest("Dune", "Sinopsis", 155, 2021, imageUrl, videoUrl, Set.of(1L));
    }

    private static void assertNoErrors(MovieRequest request) {
        Set<ConstraintViolation<MovieRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty(), () -> "debería ser válida: " + request + " -> " + violations);
    }

    private static Set<String> fieldsWithErrors(MovieRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String onlyMessage(MovieRequest request, String field) {
        var messages = validator.validate(request).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .toList();
        assertEquals(1, messages.size(), () -> field + ": " + messages);
        return messages.get(0);
    }
}
