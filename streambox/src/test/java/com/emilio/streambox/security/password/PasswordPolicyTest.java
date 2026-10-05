package com.emilio.streambox.security.password;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Tests unitarios de {@link PasswordPolicy}.
 *
 * <p>
 * Los mensajes se comparan con el texto literal acordado con el frontend, no
 * con las constantes, para que un cambio accidental rompa el test.
 * </p>
 */
class PasswordPolicyTest {

    private static final String VALID = "Rio-Tajo-482";

    // Caracteres invisibles, construidos por su código para que se vean en el
    // código fuente (escritos tal cual no se distinguirían de un espacio o de nada).
    private static final String NBSP = Character.toString(0x00A0);              // espacio de no separación
    private static final String NARROW_NBSP = Character.toString(0x202F);       // espacio estrecho de no separación
    private static final String ZWSP = Character.toString(0x200B);              // espacio de ancho cero
    private static final String IDEOGRAPHIC_SPACE = Character.toString(0x3000); // espacio ideográfico
    private static final String LINE_SEPARATOR = Character.toString(0x2028);    // separador de línea
    private static final String BOM = Character.toString(0xFEFF);               // marca de orden de bytes

    // ------------------------------------------------------------------
    // Por qué existe el límite de bytes
    // ------------------------------------------------------------------

    /**
     * Documenta el comportamiento de la versión de Spring Security del
     * proyecto: {@code encode} rechaza más de 72 bytes con una excepción. Si
     * algún día cambia, este test avisa de que conviene revisar la regla.
     */
    @Test
    void bcryptRechazaCifrarMasDe72BytesYAceptaExactamente72() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

        assertThrows(IllegalArgumentException.class, () -> encoder.encode("ñ".repeat(37))); // 74 bytes
        assertThrows(IllegalArgumentException.class, () -> encoder.encode("a".repeat(73)));

        String max = "a".repeat(PasswordPolicy.MAX_BYTES);
        assertTrue(encoder.matches(max, encoder.encode(max)));
    }

    @Test
    void ningunaContrasenaQueCumpleLaPoliticaHaceFallarABcrypt() {
        // La peor combinación aceptada: 64 caracteres en 72 bytes.
        String password = "Lince-Iberico-Donana-2026!Halcon-Peregrino-Gredos#Oso-Pardo".substring(0, 56)
                + "ñ".repeat(8);
        assertEquals(64, password.length());
        assertEquals(72, password.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(Optional.empty(), PasswordPolicy.findViolation(password, "user", "user@test.com"));

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        assertTrue(encoder.matches(password, encoder.encode(password)));
    }

    // ------------------------------------------------------------------
    // Contrato
    // ------------------------------------------------------------------

    @Test
    void lasConstantesCoincidenConElContrato() {
        assertEquals(12, PasswordPolicy.MIN_LENGTH);
        assertEquals(64, PasswordPolicy.MAX_LENGTH);
        assertEquals(72, PasswordPolicy.MAX_BYTES);
        assertEquals("La contraseña es obligatoria", PasswordPolicy.REQUIRED_MESSAGE);
        assertEquals("La contraseña debe tener entre 12 y 64 caracteres", PasswordPolicy.LENGTH_MESSAGE);
        assertEquals("La contraseña es demasiado larga: acórtala o usa menos letras acentuadas, eñes o emojis.",
                PasswordPolicy.BYTES_MESSAGE);
        assertEquals("La contraseña es demasiado común. Elige otra más difícil de adivinar.",
                PasswordPolicy.COMMON_MESSAGE);
        assertEquals("La contraseña no puede contener tu nombre de usuario ni tu email.",
                PasswordPolicy.PERSONAL_DATA_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Longitud
    // ------------------------------------------------------------------

    @Test
    void laLongitudVaDe12A64Caracteres() {
        assertFalse(PasswordPolicy.hasAllowedLength("a".repeat(11)));
        assertTrue(PasswordPolicy.hasAllowedLength("a".repeat(12)));
        assertTrue(PasswordPolicy.hasAllowedLength("a".repeat(64)));
        assertFalse(PasswordPolicy.hasAllowedLength("a".repeat(65)));
    }

    @Test
    void laLongitudSeCuentaComoEnJavaScriptUnEmojiSonDos() {
        // 6 emojis = 12 unidades UTF-16: igual que .length en el navegador.
        assertTrue(PasswordPolicy.hasAllowedLength("😀".repeat(6)));
        assertFalse(PasswordPolicy.hasAllowedLength("😀".repeat(5) + "a"));
    }

    // ------------------------------------------------------------------
    // Bytes
    // ------------------------------------------------------------------

    @Test
    void elLimiteDeBytesEsDe72EnUtf8() {
        String exactly72 = "a".repeat(56) + "ñ".repeat(8);
        String over72 = "a".repeat(55) + "ñ".repeat(9);
        assertEquals(72, exactly72.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(73, over72.getBytes(StandardCharsets.UTF_8).length);

        assertFalse(PasswordPolicy.exceedsMaxBytes(exactly72));
        assertTrue(PasswordPolicy.exceedsMaxBytes(over72));
        assertTrue(PasswordPolicy.exceedsMaxBytes("😀".repeat(19))); // 38 unidades, 76 bytes
    }

    // ------------------------------------------------------------------
    // Comunes y triviales
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "123456789012", "qwertyuiopas", "contraseña123", "password1234", "streambox123",
            "PASSWORD1234", "  Password1234  ", "StreamBox2026", "   123456   ", "Contraseña1234"
    })
    void detectaLasComunesSinDistinguirMayusculasYTrasRecortar(String password) {
        assertTrue(PasswordPolicy.isCommon(password));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aaaaaaaaaaaa", "AAAAaaaaAAAA", "abababababab", "abcabcabcabc", "121212121212",
            "abcdabcdabcd", "            ", "     ab     "
    })
    void detectaLasTrivialesConMenosDe5CaracteresDistintos(String password) {
        assertTrue(PasswordPolicy.isCommon(password));
    }

    @ParameterizedTest
    @ValueSource(strings = { VALID, "un paseo por el retiro en otoño", "abcdeabcdeab", "Password-del-Tajo" })
    void noRechazaContrasenasQueNoSonComunes(String password) {
        assertFalse(PasswordPolicy.isCommon(password));
    }

    /**
     * Regresión: con {@code trim()} solo se quitaban el espacio normal y los
     * controles ASCII, así que una contraseña común rodeada de espacios de no
     * separación, de ancho cero o ideográficos pasaba la lista.
     */
    @ParameterizedTest
    @MethodSource("comunesRodeadasDeInvisibles")
    void detectaLasComunesRodeadasDeEspaciosUnicodeOInvisibles(String password) {
        assertTrue(PasswordPolicy.isCommon(password));
    }

    static Stream<String> comunesRodeadasDeInvisibles() {
        return Stream.of(
                NBSP + NBSP + "password1234" + NBSP + NBSP,
                ZWSP + "password1234" + ZWSP,
                IDEOGRAPHIC_SPACE + "Password1234" + IDEOGRAPHIC_SPACE,
                BOM + "streambox123",
                "\t\n" + NBSP + "contraseña123" + ZWSP + " \r",
                LINE_SEPARATOR + "123456789012" + NARROW_NBSP);
    }

    /**
     * Las triviales también se miden sin los invisibles de los extremos: si
     * no, rodear {@code abab} de cinco invisibles distintos sumaría cinco
     * "caracteres distintos" y dejaría de ser trivial.
     */
    @Test
    void lasTrivialesSeMidenSinLosInvisiblesDeLosExtremos() {
        assertTrue(PasswordPolicy.isCommon(NBSP + ZWSP + IDEOGRAPHIC_SPACE + "abab" + NARROW_NBSP + BOM));
        assertTrue(PasswordPolicy.isCommon(ZWSP.repeat(12)));
    }

    /** Solo se recortan los extremos: un espacio de no separación en medio forma parte de la contraseña. */
    @Test
    void losInvisiblesDeEnMedioNoSeQuitan() {
        assertFalse(PasswordPolicy.isCommon("password" + NBSP + "1234"));
    }

    @Test
    void laListaEstaNormalizadaYTieneUnTamanoRazonable() {
        for (String entry : PasswordPolicy.commonPasswords()) {
            assertEquals(entry.trim().toLowerCase(Locale.ROOT), entry, "Entrada sin normalizar: " + entry);
        }
        int size = PasswordPolicy.commonPasswords().size();
        assertTrue(size >= 100 && size <= 300, "Tamaño de la lista: " + size);
    }

    // ------------------------------------------------------------------
    // Datos personales
    // ------------------------------------------------------------------

    @Test
    void detectaElNombreDeUsuarioSinDistinguirMayusculas() {
        assertTrue(PasswordPolicy.containsPersonalData("Hola-LUCIA_dev-26", "lucia_dev", "x@test.com"));
        assertTrue(PasswordPolicy.containsPersonalData("Hola-lucia_dev-26", "  Lucia_Dev ", "x@test.com"));
    }

    @Test
    void detectaLaParteLocalDelEmail() {
        assertTrue(PasswordPolicy.containsPersonalData("xx-marta.ruiz-2026", "otra", "Marta.Ruiz@test.com"));
        // El dominio no cuenta: "test" está en la contraseña pero no es la parte local.
        assertFalse(PasswordPolicy.containsPersonalData("mi-test-seguro-1", "otra", "marta@test.com"));
    }

    /**
     * El mínimo es de 4 caracteres (límite inclusivo). Con 3 había demasiados
     * falsos positivos: la usuaria {@code ana} no podía usar
     * "mañana iremos al cine".
     */
    @Test
    void noCompruebaPartesDeMenosDe4Caracteres() {
        assertFalse(PasswordPolicy.containsPersonalData("Cabra-Montes-77", "ab", "ab@test.com"));
        assertFalse(PasswordPolicy.containsPersonalData("Cabra-Montes-77", "abr", "abr@test.com"));
        assertTrue(PasswordPolicy.containsPersonalData("Cabra-Montes-77", "abra", "zz@test.com"));
        assertTrue(PasswordPolicy.containsPersonalData("Cabra-Montes-77", "otra", "abra@test.com"));
    }

    @Test
    void unUsuarioDe3LetrasQueApareceDentroDeUnaPalabraYaNoSeRechaza() {
        assertEquals(Optional.empty(),
                PasswordPolicy.findViolation("mañana iremos al cine", "ana", "ana@test.com"));
        assertEquals(Optional.empty(),
                PasswordPolicy.findViolation("la nueva temporada", "eva", "eva@test.com"));
    }

    /**
     * El usuario se normaliza igual que la contraseña: con un espacio de no
     * separación al final se busca sin él (con él nunca se encontraría), y su
     * longitud se mide ya normalizada.
     */
    @Test
    void losDatosPersonalesSeComparanSinLosInvisiblesDeLosExtremos() {
        assertTrue(PasswordPolicy.containsPersonalData("Hola-lucia_dev-26", NBSP + "lucia_dev" + ZWSP, "x@test.com"));
        assertFalse(PasswordPolicy.containsPersonalData("mañana iremos al cine", NBSP + "ana" + NBSP, "x@test.com"));
    }

    @Test
    void toleraDatosPersonalesNulos() {
        assertFalse(PasswordPolicy.containsPersonalData(VALID, null, null));
        assertEquals(Optional.empty(), PasswordPolicy.findViolation(VALID, null, null));
    }

    // ------------------------------------------------------------------
    // Prioridad
    // ------------------------------------------------------------------

    @Test
    void laPrioridadEsLongitudBytesComunDatosPersonales() {
        // nulo
        assertEquals(Optional.of(PasswordPolicy.REQUIRED_MESSAGE), PasswordPolicy.findViolation(null, "u", "e"));
        // corta, común y con el usuario -> longitud
        assertEquals(Optional.of(PasswordPolicy.LENGTH_MESSAGE),
                PasswordPolicy.findViolation("password", "password", "password@test.com"));
        // demasiados bytes y con el usuario -> bytes
        assertEquals(Optional.of(PasswordPolicy.BYTES_MESSAGE),
                PasswordPolicy.findViolation("pepito" + "áéíóú".repeat(11), "pepito", "pepito@test.com"));
        // común y con el usuario -> común
        assertEquals(Optional.of(PasswordPolicy.COMMON_MESSAGE),
                PasswordPolicy.findViolation("password1234", "password", "password@test.com"));
        // solo datos personales
        assertEquals(Optional.of(PasswordPolicy.PERSONAL_DATA_MESSAGE),
                PasswordPolicy.findViolation("Soy-Pepito-2026", "pepito", "otro@test.com"));
        // válida
        assertEquals(Optional.empty(), PasswordPolicy.findViolation(VALID, "pepito", "pepito@test.com"));
    }

    @Test
    void lasReglasDeContenidoNoOpinanSobreLaLongitud() {
        // findContentViolation supone la longitud ya validada (@Size).
        assertEquals(Optional.empty(), PasswordPolicy.findContentViolation("Ab1-x", "u", "e@test.com"));
    }
}
