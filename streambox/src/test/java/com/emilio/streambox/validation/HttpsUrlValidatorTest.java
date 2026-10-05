package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Tests unitarios de {@link HttpsUrl} / {@link HttpsUrlValidator}, sin Spring.
 *
 * <p>
 * Se valida con el {@link Validator} real de Hibernate Validator sobre dos
 * registros de prueba anotados igual que {@code MovieRequest} (con y sin
 * portadas propias). Así se prueba también que la anotación está bien
 * enlazada con su validador y que sus atributos llegan a {@code initialize},
 * no solo la lógica de {@code isValid}.
 * </p>
 */
class HttpsUrlValidatorTest {

    private static final int MAX = 500;

    private static ValidatorFactory factory;
    private static Validator validator;

    /** Como {@code MovieRequest.imageUrl}: https o portada propia. */
    record ImageField(@HttpsUrl(allowLocalCovers = true, maxLength = MAX) String url) {
    }

    /** Como {@code MovieRequest.videoUrl}: solo https. */
    record VideoField(@HttpsUrl(maxLength = MAX) String url) {
    }

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    // ------------------------------------------------------------------
    // URL https válidas (para imagen y para vídeo)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://image.tmdb.org/t/p/w500/x.jpg",
            "https://cdn.example.com/a.webp?v=2",
            // Caracteres codificados: es lo que genera encodeURIComponent en el frontend.
            "https://videos.streambox.example/watch/Dune%3A%20Parte%20Dos",
            "https://cdn.example.com:8443/x.jpg#inicio",
            "https://[2001:db8::1]/x.jpg",
            // El host no distingue mayúsculas (solo el esquema debe ir en minúsculas).
            "https://CDN.Example.com/x.jpg",
            "https://cdn.example.com"
    })
    void aceptaUrlsHttpsAbsolutasEnImagenYVideo(String url) {
        assertValid(new ImageField(url));
        assertValid(new VideoField(url));
    }

    @Test
    void aceptaUnaUrlHttpsDeExactamente500Caracteres() {
        String url = httpsUrlOfLength(MAX);
        assertEquals(MAX, url.length());

        assertValid(new ImageField(url));
        assertValid(new VideoField(url));
    }

    // ------------------------------------------------------------------
    // Portadas propias (/covers/<archivo>)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/covers/dune-parte-dos.webp",
            "/covers/a_b-c.1.jpg",
            "/covers/cover_matrix_1790887046226.jpg",
            "/covers/X.WEBP",
            "/covers/a"
    })
    void laImagenAceptaPortadasPropias(String url) {
        assertValid(new ImageField(url));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/covers/dune-parte-dos.webp", "/covers/a_b-c.1.jpg" })
    void elVideoRechazaLasPortadasPropias(String url) {
        assertInvalid(new VideoField(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/covers/../x.webp",       // salir de la carpeta
            "/covers/..",
            "/covers/sub/x.webp",      // subcarpetas
            "/covers//x.webp",
            "/covers/x.webp/",
            "/covers/",                // sin archivo
            "/covers/x.webp?v=1",      // parámetros
            "/covers/x.webp#a",        // fragmento
            "/covers/%2e%2e.webp",     // ".." codificado
            "/covers/x%20y.webp",
            "/covers/.oculto.webp",    // archivo oculto (empieza por punto)
            "/covers/-x.webp",
            "/covers/x .webp",         // espacio
            "/covers/ñ.webp",          // no ASCII
            "/otra/x.webp",            // otra carpeta
            "/Covers/x.webp",          // la ruta distingue mayúsculas
            "covers/x.webp",           // relativa
            "/covers"
    })
    void laImagenRechazaRutasQueNoSonUnaPortadaPropia(String url) {
        assertInvalid(new ImageField(url));
    }

    // ------------------------------------------------------------------
    // Rechazadas en imagen y en vídeo
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            // Esquemas distintos de https
            "http://cdn.example.com/x.jpg",
            "javascript:alert(1)",
            "javascript://cdn.example.com/%0Aalert(1)",
            "data:image/png;base64,iVBORw0KGgo=",
            "file:///etc/passwd",
            "ftp://cdn.example.com/x.jpg",
            "jar:file:/app.jar!/x.jpg",
            // Esquema en mayúsculas: se rechaza en lugar de normalizarlo
            "HTTPS://cdn.example.com/x.jpg",
            "Https://cdn.example.com/x.jpg",
            // Sin host o mal formadas
            "https://",
            "https:",
            "https:///ruta",
            "https:/cdn.example.com/x.jpg",
            "https:cdn.example.com/x.jpg",
            "https://:443/x.jpg",
            "https://cdn.example.com:abc/x.jpg",
            // Relativas
            "//cdn.example.com/x.jpg",
            "cdn.example.com/x.jpg",
            "/x.jpg",
            // Credenciales
            "https://u:p@cdn.example.com/x.jpg",
            "https://usuario@cdn.example.com/x.jpg",
            "https://@cdn.example.com/x.jpg",
            "https://cdn.example.com@evil.example/x.jpg",
            // Caracteres que URI no admite
            "https://cdn.example.com\\@evil.example/x.jpg",
            "https://cdn.example.com/x\".jpg",
            "https://cdn.example.com/<x>.jpg"
    })
    void rechazaEsquemasPeligrososUrlsSinHostYCredenciales(String url) {
        assertInvalid(new ImageField(url));
        assertInvalid(new VideoField(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/a b.jpg",      // espacio interior
            " https://cdn.example.com/x.jpg",       // espacio al principio
            "https://cdn.example.com/x.jpg ",       // espacio al final
            "https://cdn.example.com/x.jpg\n",      // salto de línea
            "https://cdn.example.com/x\t.jpg",      // tabulador
            "https://cdn.example.com/x\u0000.jpg",  // carácter de control
            "https://cdn.example.com/x .jpg",  // espacio de no separación
            "https://cdn.example.com/x​.jpg",  // espacio de ancho cero (invisible)
            "https://cdn.example.com/‮gpj.exe", // inversión de la dirección del texto
            "https://cdn.example.com/año.jpg",      // no ASCII sin codificar
            "https://ho st/x.jpg"
    })
    void rechazaEspaciosControlesYCaracteresNoAscii(String url) {
        assertInvalid(new ImageField(url));
        assertInvalid(new VideoField(url));
    }

    // ------------------------------------------------------------------
    // Valores que deja a otras restricciones
    // ------------------------------------------------------------------

    /**
     * {@code null} y los textos en blanco no los juzga este validador: los
     * rechaza {@code @NotBlank}, y si se quejaran los dos el campo tendría dos
     * mensajes. La combinación real se prueba en {@code MovieRequestValidationTest}.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", "\n", " " })
    void dejaElNuloYLosTextosEnBlancoANotBlank(String url) {
        assertValid(new ImageField(url));
        assertValid(new VideoField(url));
    }

    /**
     * Lo que supera {@code maxLength} lo rechaza {@code @Size}, aunque además
     * tenga un formato incorrecto: así el cliente recibe un único mensaje, el
     * de la longitud.
     */
    @ParameterizedTest
    @ValueSource(strings = { "javascript:", "http://cdn.example.com/", "/covers/" })
    void dejaLasUrlsDemasiadoLargasASize(String prefix) {
        String tooLong = prefix + "a".repeat(MAX + 1 - prefix.length());
        assertEquals(MAX + 1, tooLong.length());

        assertValid(new ImageField(tooLong));
        assertValid(new VideoField(tooLong));
    }

    @Test
    void sinMaxLengthEvaluaCualquierLongitud() {
        record Unbounded(@HttpsUrl String url) {
        }

        assertInvalid(new Unbounded("http://cdn.example.com/" + "a".repeat(1000)));
        assertValid(new Unbounded(httpsUrlOfLength(2000)));
    }

    @Test
    void elMensajePorDefectoEstaEnEspanol() {
        record Default(@HttpsUrl String url) {
        }

        Set<ConstraintViolation<Default>> violations = validator.validate(new Default("ftp://x.example/a"));

        assertEquals(1, violations.size());
        assertEquals("La URL debe empezar por https://", violations.iterator().next().getMessage());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** URL https válida de la longitud exacta indicada. */
    static String httpsUrlOfLength(int length) {
        String prefix = "https://cdn.example.com/";
        return prefix + "a".repeat(length - prefix.length());
    }

    private static void assertValid(Object holder) {
        Set<ConstraintViolation<Object>> violations = validator.validate(holder);
        assertTrue(violations.isEmpty(), () -> "debería ser válida: " + holder + " -> " + violations);
    }

    private static void assertInvalid(Object holder) {
        Set<ConstraintViolation<Object>> violations = validator.validate(holder);
        assertEquals(1, violations.size(), () -> "debería ser inválida: " + holder);
    }
}
