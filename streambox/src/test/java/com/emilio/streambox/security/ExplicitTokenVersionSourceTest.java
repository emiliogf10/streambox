package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * El código de producción emite los JWT de acceso siempre con la versión de
 * tokens explícita: {@link JwtService#generateToken(com.emilio.streambox.entity.User, int)},
 * nunca la variante de un argumento (INFO 7 de la revisión del lote de
 * {@code token_version}).
 *
 * <p>
 * <b>Por qué un test que lee el código fuente.</b> La variante de un argumento
 * usa {@code user.getTokenVersion()}, que puede estar desfasada (tras
 * {@code incrementTokenVersion} en la misma transacción) o leída en un momento
 * inadecuado (la gracia del refresh, BAJA-A). Compila y funciona en casi todos
 * los casos, así que ningún test de comportamiento avisaría de un uso nuevo
 * hasta que coincidiera con la carrera. Se conserva solo para los tests
 * (decenas la usan); este test impide que vuelva a {@code src/main}. Es el
 * mismo enfoque que {@code EmailLinkTtlFrontendParityTest}: leer archivos del
 * repositorio con rutas relativas al módulo ({@code streambox/}).
 * </p>
 *
 * <p>
 * Sin el cambio, falla con las llamadas de {@code AuthenticationService.login}
 * y de las dos ramas de {@code RefreshTokenService.refresh}.
 * </p>
 */
class ExplicitTokenVersionSourceTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final String CALL = "generateToken(";

    /**
     * Ninguna llamada de producción (fuera de {@code JwtService}, que define
     * las dos) usa la variante de un argumento.
     */
    @Test
    void elCodigoDeProduccionPasaSiempreLaVersion() throws IOException {
        List<String> singleArgument = new ArrayList<>();
        int calls = 0;
        for (Path file : javaSources()) {
            String code = stripCommentsAndStrings(Files.readString(file, StandardCharsets.UTF_8));
            int index = code.indexOf(CALL);
            while (index >= 0) {
                calls++;
                if (!hasTopLevelComma(code, index + CALL.length())) {
                    singleArgument.add(MAIN_SOURCES.relativize(file) + ": "
                            + code.substring(index, Math.min(code.length(), index + 60)).replaceAll("\\s+", " "));
                }
                index = code.indexOf(CALL, index + CALL.length());
            }
        }

        assertEquals(List.of(), singleArgument,
                "usa jwtService.generateToken(user, version) con una versión elegida a propósito");
        // Control: si la búsqueda no encontrara nada (ruta mal resuelta, método
        // renombrado), el test pasaría sin probar nada. Hoy hay cuatro: login,
        // las dos ramas del refresh y el cambio de contraseña.
        assertTrue(calls >= 4, "solo se han encontrado " + calls + " llamadas a generateToken en " + MAIN_SOURCES);
    }

    /** Control del analizador: distingue uno y dos argumentos, con paréntesis anidados. */
    @Test
    void elAnalizadorDistingueUnoYDosArgumentos() {
        assertTrue(hasTopLevelComma("generateToken(user, 3)", CALL.length()));
        assertTrue(hasTopLevelComma("generateToken(repo.find(a), version(b, c))", CALL.length()));
        assertEquals(false, hasTopLevelComma("generateToken(user)", CALL.length()));
        assertEquals(false, hasTopLevelComma("generateToken(repo.save(build(a, b)))", CALL.length()));
        assertEquals("x  y", stripCommentsAndStrings("x /* generateToken(u) */ y"));
        assertEquals("x \"\"", stripCommentsAndStrings("x \"generateToken(u)\""));
    }

    private static List<Path> javaSources() throws IOException {
        assertTrue(Files.isDirectory(MAIN_SOURCES), "no se encuentra " + MAIN_SOURCES.toAbsolutePath());
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.endsWith(Path.of("security", "JwtService.java")))
                    .toList();
        }
    }

    /**
     * Indica si la lista de argumentos que empieza en {@code start} (justo tras
     * el paréntesis de apertura) tiene una coma en su primer nivel.
     */
    private static boolean hasTopLevelComma(String code, int start) {
        int depth = 0;
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quita comentarios y vacía los literales de texto y de carácter, para que
     * un {@code generateToken(user)} citado en un Javadoc o en un mensaje no
     * cuente como llamada.
     */
    private static String stripCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < code.length() && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
            } else if (code.startsWith("\"\"\"", i)) {
                // Bloque de texto: hasta el cierre """ (las comillas sueltas de dentro no cierran).
                int end = code.indexOf("\"\"\"", i + 3);
                out.append("\"\"");
                i = end < 0 ? code.length() : end + 3;
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < code.length() && code.charAt(i) != c) {
                    i += code.charAt(i) == '\\' ? 2 : 1;
                }
                if (i < code.length()) {
                    out.append(c);
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
