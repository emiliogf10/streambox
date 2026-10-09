package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.method.HandlerMethod;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;

/**
 * Pruebas unitarias de {@link CookieAuthOperationCustomizer} con operaciones
 * construidas a mano: casos que la API real no tiene hoy (operación sin
 * respuestas, 403 sin descripción, lista de seguridad compartida) y la
 * idempotencia.
 */
class CookieAuthOperationCustomizerTest {

    private final CookieAuthOperationCustomizer customizer = new CookieAuthOperationCustomizer();

    @Test
    void anadeLaCookieComoPrimeraAlternativaSinModificarLaListaOriginal() throws Exception {
        List<SecurityRequirement> shared = new ArrayList<>(List.of(bearer()));
        Operation operation = new Operation().security(shared).responses(new ApiResponses());

        customizer.customize(operation, handler("listar"));

        assertEquals(List.of(requirement(OpenApiConfig.COOKIE_AUTH), bearer()), operation.getSecurity());
        assertEquals(List.of(bearer()), shared, "La lista original puede ser compartida: no se toca");
    }

    @Test
    void lasOperacionesSegurasNoRecibenEl403() throws Exception {
        Operation operation = securedOperation(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("ok")));

        customizer.customize(operation, handler("listar"));

        assertNull(operation.getResponses().get("403"));
    }

    @Test
    void creaEl403EnSuSitioSiLaOperacionNoLoTenia() throws Exception {
        Operation operation = securedOperation(new ApiResponses()
                .addApiResponse("204", new ApiResponse().description("ok"))
                .addApiResponse("401", new ApiResponse().description("sin sesión"))
                .addApiResponse("404", new ApiResponse().description("no existe")));

        customizer.customize(operation, handler("crear"));

        assertEquals(List.of("204", "401", "403", "404"), List.copyOf(operation.getResponses().keySet()));
        assertEquals(CookieAuthOperationCustomizer.CSRF_DESCRIPTION,
                operation.getResponses().get("403").getDescription());
    }

    @Test
    void anadeElCasoCsrfAlFinalDeUn403ExistenteSinDuplicarlo() throws Exception {
        ApiResponse forbidden = new ApiResponse().description("Sin permisos de administrador.");
        Operation operation = securedOperation(new ApiResponses().addApiResponse("403", forbidden));

        customizer.customize(operation, handler("borrar"));
        customizer.customize(operation, handler("borrar"));

        assertSame(forbidden, operation.getResponses().get("403"));
        assertEquals("Sin permisos de administrador. " + CookieAuthOperationCustomizer.CSRF_APPENDIX,
                forbidden.getDescription());
        assertEquals(2, operation.getSecurity().size(), "Idempotente: la cookie no se añade dos veces");
    }

    @Test
    void un403SinDescripcionRecibeLaDelCasoCsrf() throws Exception {
        Operation operation = securedOperation(new ApiResponses().addApiResponse("403", new ApiResponse()));

        customizer.customize(operation, handler("crear"));

        assertEquals(CookieAuthOperationCustomizer.CSRF_DESCRIPTION,
                operation.getResponses().get("403").getDescription());
    }

    @Test
    void unaOperacionSinRespuestasRecibeEl403() throws Exception {
        Operation operation = new Operation().security(List.of(bearer()));

        customizer.customize(operation, handler("crear"));

        assertEquals(List.of("403"), List.copyOf(operation.getResponses().keySet()));
    }

    /** Login, registro, refresh y logout no declaran seguridad: no se tocan. */
    @Test
    void noTocaLasOperacionesPublicas() throws Exception {
        ApiResponses responses = new ApiResponses().addApiResponse("204", new ApiResponse().description("ok"));
        Operation operation = new Operation().responses(responses);

        customizer.customize(operation, handler("crear"));

        assertNull(operation.getSecurity());
        assertEquals(List.of("204"), List.copyOf(operation.getResponses().keySet()));
    }

    /** Si ya estaba la cookie (por ejemplo, declarada a mano), no se repite. */
    @Test
    void noRepiteLaCookieSiYaEstaba() throws Exception {
        Operation operation = new Operation()
                .security(List.of(requirement(OpenApiConfig.COOKIE_AUTH), bearer()))
                .responses(new ApiResponses());

        customizer.customize(operation, handler("listar"));

        assertEquals(2, operation.getSecurity().size());
        assertTrue(operation.getSecurity().get(0).containsKey(OpenApiConfig.COOKIE_AUTH));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static Operation securedOperation(ApiResponses responses) {
        return new Operation().security(List.of(bearer())).responses(responses);
    }

    private static SecurityRequirement bearer() {
        return requirement(OpenApiConfig.BEARER_AUTH);
    }

    private static SecurityRequirement requirement(String scheme) {
        return new SecurityRequirement().addList(scheme);
    }

    private static HandlerMethod handler(String method) throws NoSuchMethodException {
        return new HandlerMethod(new FakeController(), FakeController.class.getMethod(method));
    }

    /** Controlador mínimo para obtener el método HTTP de cada operación. */
    static class FakeController {

        @GetMapping("/fake")
        public void listar() {
            // Sin cuerpo: solo importa la anotación.
        }

        @PostMapping("/fake")
        public void crear() {
            // Sin cuerpo: solo importa la anotación.
        }

        @DeleteMapping("/fake/{id}")
        public void borrar() {
            // Sin cuerpo: solo importa la anotación.
        }
    }
}
