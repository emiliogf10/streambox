package com.emilio.streambox.security.password;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Política de contraseñas de las cuentas <b>nuevas</b>: único sitio donde están
 * las reglas, sus límites, sus mensajes y la lista de contraseñas comunes.
 *
 * <p>
 * La usan el registro público (a través de {@link ValidPassword} sobre
 * {@code CreateUserRequest}) y {@code AdminAccountInitializer}. El login no la
 * aplica: una cuenta creada con la política anterior (mínimo 8 caracteres)
 * debe poder seguir entrando.
 * </p>
 *
 * <h2>Por qué estas reglas</h2>
 * <ul>
 * <li><b>Longitud de 12 a 64 caracteres.</b> La guía del NIST (SP 800-63B)
 * recomienda priorizar la longitud y comprobar listas de contraseñas conocidas,
 * en lugar de imponer reglas de composición ("una mayúscula, un número y un
 * símbolo"), que llevan a contraseñas previsibles como {@code Password1!}. Por
 * eso aquí no se exige ningún tipo de carácter: una frase larga es más segura
 * y más fácil de recordar. El máximo de 64 es el que el NIST pide admitir como
 * mínimo y deja margen para el límite de bytes.</li>
 * <li><b>Como máximo {@value #MAX_BYTES} bytes en UTF-8.</b> BCrypt solo usa
 * los primeros 72 bytes de la contraseña, y {@code BCryptPasswordEncoder} de
 * Spring Security 7 se niega a cifrar una más larga: lanza
 * {@code IllegalArgumentException}, que antes de esta regla acababa en un 500
 * al registrarse. 64 caracteres ASCII son 64 bytes, pero una tilde o una eñe
 * ocupan 2 y un emoji 4, así que 64 caracteres pueden pasar de 72 bytes.</li>
 * <li><b>No estar en la lista de contraseñas comunes ni ser trivial.</b> Son
 * las primeras que prueba un atacante (ataques de diccionario). La lista es
 * corta a propósito (unas 200 entradas, en el código y sin dependencias): no
 * pretende ser exhaustiva, sino cortar las más usadas, incluidas algunas en
 * español y con el nombre de la aplicación. Se consideran también triviales
 * las que, sin espacios exteriores, tienen menos de
 * {@value #MIN_DISTINCT_CHARACTERS} caracteres distintos ({@code aaaaaaaaaaaa},
 * {@code abcabcabcabc}, diez espacios y dos letras...).</li>
 * <li><b>No contener el nombre de usuario ni la parte local del email.</b> Es
 * lo primero que se prueba en un ataque dirigido. Solo se comprueba si esa
 * parte tiene al menos {@value #MIN_PERSONAL_DATA_LENGTH} caracteres: con 3
 * había demasiados falsos positivos, porque muchos nombres cortos son trozos de
 * palabras corrientes (la usuaria {@code ana} no podía usar
 * {@code mañana iremos al cine}, ni {@code eva} algo con "nueva"). Un nombre
 * de 3 letras tampoco aporta mucho a un ataque dirigido.</li>
 * </ul>
 *
 * <h2>Cómo se cuenta</h2>
 * <p>
 * La longitud se mide con {@link String#length()} (unidades UTF-16), igual que
 * {@code @Size} y que {@code .length} en JavaScript, para que el frontend y el
 * servidor coincidan: un emoji cuenta como 2. La contraseña no se recorta ni se
 * normaliza antes de cifrarla (los espacios forman parte de ella); solo se
 * normaliza para <i>compararla</i> con la lista y con los datos personales:
 * se quitan los caracteres invisibles de los extremos y se pasa a minúsculas.
 * </p>
 *
 * <p>
 * <b>Qué se considera invisible.</b> No basta con {@link String#trim()}, que
 * solo quita los caracteres de control ASCII y el espacio normal: con él,
 * {@code password1234} rodeada de espacios de no separación ({@code U+00A0}),
 * de ancho cero ({@code U+200B}) o ideográficos ({@code U+3000}) pasaba la
 * lista de comunes, aunque para un atacante (y para quien la ve escrita) es la
 * misma contraseña. Se quitan los separadores Unicode ({@code \p{Z}}), los
 * controles ({@code \p{Cc}}) y los caracteres de formato ({@code \p{Cf}}), la
 * misma regla que aplica {@code GenreService} a los nombres de género. Lo que
 * queda <i>dentro</i> de la contraseña no se toca.
 * </p>
 *
 * <h2>Prioridad</h2>
 * <p>
 * Si se incumplen varias reglas solo se informa de una, en este orden:
 * longitud &gt; bytes &gt; común &gt; datos personales. El manejador de errores
 * guarda un mensaje por campo y Bean Validation no garantiza el orden de las
 * restricciones, así que cada comprobación da por buena la contraseña en lo que
 * ya rechaza una regla anterior. El orden va de lo más fácil de corregir y
 * general a lo más concreto.
 * </p>
 */
public final class PasswordPolicy {

    /** Longitud mínima (en unidades UTF-16, como {@code @Size}). */
    public static final int MIN_LENGTH = 12;

    /** Longitud máxima (en unidades UTF-16, como {@code @Size}). */
    public static final int MAX_LENGTH = 64;

    /** Bytes en UTF-8 que BCrypt puede procesar; uno más y el cifrado falla. */
    public static final int MAX_BYTES = 72;

    /** Caracteres distintos por debajo de los cuales la contraseña es trivial. */
    public static final int MIN_DISTINCT_CHARACTERS = 5;

    /**
     * Longitud mínima del usuario o de la parte local del email para
     * comprobarlos (con menos, demasiados falsos positivos: ver la clase).
     */
    public static final int MIN_PERSONAL_DATA_LENGTH = 4;

    /** La contraseña no se ha enviado. */
    public static final String REQUIRED_MESSAGE = "La contraseña es obligatoria";

    /** Longitud fuera de rango (también una contraseña vacía). */
    public static final String LENGTH_MESSAGE =
            "La contraseña debe tener entre " + MIN_LENGTH + " y " + MAX_LENGTH + " caracteres";

    /** Longitud válida pero más de {@value #MAX_BYTES} bytes en UTF-8. */
    public static final String BYTES_MESSAGE =
            "La contraseña es demasiado larga: acórtala o usa menos letras acentuadas, eñes o emojis.";

    /** Está en la lista de contraseñas comunes o es trivial. */
    public static final String COMMON_MESSAGE =
            "La contraseña es demasiado común. Elige otra más difícil de adivinar.";

    /** Contiene el nombre de usuario o la parte local del email. */
    public static final String PERSONAL_DATA_MESSAGE =
            "La contraseña no puede contener tu nombre de usuario ni tu email.";

    /**
     * Caracteres invisibles en los extremos: separadores Unicode, controles y
     * caracteres de formato (la misma expresión que {@code GenreService}). Se
     * precompila porque se usa en cada registro.
     */
    private static final Pattern EDGES =
            Pattern.compile("^[\\p{Z}\\p{Cc}\\p{Cf}]+|[\\p{Z}\\p{Cc}\\p{Cf}]+$");

    /**
     * Contraseñas comunes, ya normalizadas (sin caracteres invisibles en los
     * extremos y en minúsculas).
     *
     * <p>
     * Incluye algunas de menos de 12 caracteres porque la comparación se hace
     * tras recortar: {@code "   123456   "} tiene 12 caracteres, pero es
     * {@code 123456}. Se normalizan al cargarse para que una entrada mal escrita
     * (con mayúsculas) no deje de detectarse sin que nadie se dé cuenta.
     * </p>
     */
    private static final Set<String> COMMON_PASSWORDS = Stream.of(
            // Las más usadas de todas (cortas: solo cuentan si se rellenan con espacios)
            "123456", "1234567", "12345678", "123456789", "1234567890", "12345", "1234",
            "111111", "000000", "123123", "654321", "121212", "112233", "666666", "696969",
            "password", "passw0rd", "p@ssw0rd", "qwerty", "qwertyuiop", "abc123", "iloveyou",
            "admin", "welcome", "letmein", "monkey", "dragon", "football", "baseball",
            "sunshine", "princess", "master", "shadow", "superman", "trustno1", "1qaz2wsx",
            "qazwsx", "zxcvbnm", "asdfghjkl", "contraseña", "contrasena", "teamo", "tequiero",
            "hola", "usuario", "streambox", "netflix",

            // Secuencias numéricas y de teclado de 12 o más caracteres
            "123456789012", "1234567890123", "12345678901234", "123456789012345",
            "1234567890123456", "012345678901", "123456789000", "123456789123",
            "987654321098", "098765432109", "123456123456", "123123123123", "123412341234",
            "123321123321", "112233445566", "111222333444", "121212121212", "111111111111",
            "000000000000", "999999999999", "1q2w3e4r5t6y", "1q2w3e4r5t6y7u", "q1w2e3r4t5y6",
            "1qaz2wsx3edc", "1qaz2wsx3edc4rfv", "zaq12wsxcde3", "qazwsxedcrfv", "qwertyuiopas",
            "qwertyuiop12", "qwertyuiop123", "qwertyuiop1234", "qwerty123456", "qwerty1234567",
            "qwerty123456789", "qwertyqwerty", "asdfghjkl123", "asdfghjklñ12", "asdfasdfasdf",
            "qwerqwerqwer", "zxcvbnm12345", "zxcvbnm123456", "1234qwerasdf", "1234567890qwerty",
            "abcdefghijkl", "abcdef123456", "abc123456789", "abcd12345678", "abc123abc123",
            "a1b2c3d4e5f6",

            // En inglés
            "password1234", "password12345", "password123456", "password1234!", "password123!",
            "passwordpassword", "password2024", "password2025", "password2026", "p@ssw0rd1234",
            "p@ssword1234", "passw0rd1234", "mypassword123", "mypassword12", "letmein12345",
            "letmein123456", "welcome12345", "welcome123456", "welcome2024!", "iloveyou1234",
            "iloveyou12345", "sunshine1234", "princess1234", "football1234", "baseball1234",
            "superman1234", "batman123456", "starwars1234", "pokemon12345", "monkey123456",
            "dragon123456", "master123456", "shadow123456", "computer1234", "internet1234",
            "whatever1234", "secret123456", "changeme1234", "default12345", "test12345678",
            "testtest1234", "guest1234567", "user12345678", "login1234567", "hello1234567",
            "helloworld12", "helloworld123", "administrator", "administrator1", "admin1234567",
            "admin12345678", "admin123456789", "adminadmin12", "adminadmin123", "rootroot1234",

            // En español
            "contraseña1", "contraseña12", "contraseña123", "contraseña1234", "contrasena12",
            "contrasena123", "contrasena1234", "contrasena12345", "micontraseña",
            "micontraseña1", "micontraseña123", "micontrasena", "micontrasena123",
            "contraseñasegura", "contrasenasegura", "clavesecreta", "clavesecreta1",
            "clavesecreta123", "miclave12345", "miclavesecreta", "secreto12345", "teamo1234567",
            "teamo123456789", "teamomucho12", "teamomucho123", "tequiero1234", "tequieromucho",
            "tequieromucho1", "hola12345678", "holamundo123", "holahola1234", "holaholahola",
            "bienvenido12", "bienvenido123", "usuario12345", "usuario123456", "administrador",
            "administrador1", "administrador123", "cambiame1234", "cambiar12345", "barcelona123",
            "barcelona1234", "realmadrid12", "realmadrid123", "realmadrid1234", "atleticomadrid",
            "españa123456", "espana123456", "mexico123456", "argentina123", "colombia1234",
            "madrid123456", "familia12345", "chocolate123", "princesa1234", "mariposa1234",
            "tesoro123456", "superclave12", "mipassword123", "abcdefg12345",

            // Con el nombre de la aplicación o del sector
            "streambox1", "streambox12", "streambox123", "streambox1234", "streambox12345",
            "streambox2024", "streambox2025", "streambox2026", "streambox!123", "streambox123!",
            "mistreambox", "mistreambox123", "streamboxadmin", "adminstreambox",
            "streamboxpassword", "streamboxcontraseña", "netflix12345", "netflix123456",
            "netflixpassword", "streaming123", "streaming1234", "peliculas123", "peliculas1234",
            "series123456", "cine12345678")
            .map(PasswordPolicy::normalize)
            .collect(Collectors.toUnmodifiableSet());

    private PasswordPolicy() {
    }

    /**
     * Comprueba todas las reglas en orden de prioridad.
     *
     * <p>
     * La usa {@code AdminAccountInitializer}, que no pasa por Bean Validation.
     * El registro público valida la obligatoriedad y la longitud con
     * {@code @NotNull} y {@code @Size} (para que aparezcan en el OpenAPI) y el
     * resto con {@link #findContentViolation(String, String, String)}.
     * </p>
     *
     * @param password contraseña tal como la escribió el usuario
     * @param username nombre de usuario de la cuenta (puede ser {@code null})
     * @param email    email de la cuenta (puede ser {@code null})
     * @return mensaje de la primera regla incumplida, o vacío si es válida
     */
    public static Optional<String> findViolation(String password, String username, String email) {
        if (password == null) {
            return Optional.of(REQUIRED_MESSAGE);
        }
        if (!hasAllowedLength(password)) {
            return Optional.of(LENGTH_MESSAGE);
        }
        return findContentViolation(password, username, email);
    }

    /**
     * Comprueba las reglas que no son de longitud, en orden de prioridad:
     * bytes &gt; común &gt; datos personales.
     *
     * <p>
     * Supone que la longitud ya es correcta (lo comprueba quien la llama); por
     * eso solo devuelve un mensaje aunque fallen varias.
     * </p>
     *
     * @param password contraseña con longitud válida
     * @param username nombre de usuario de la cuenta (puede ser {@code null})
     * @param email    email de la cuenta (puede ser {@code null})
     * @return mensaje de la primera regla incumplida, o vacío si es válida
     */
    public static Optional<String> findContentViolation(String password, String username, String email) {
        if (exceedsMaxBytes(password)) {
            return Optional.of(BYTES_MESSAGE);
        }
        if (isCommon(password)) {
            return Optional.of(COMMON_MESSAGE);
        }
        if (containsPersonalData(password, username, email)) {
            return Optional.of(PERSONAL_DATA_MESSAGE);
        }
        return Optional.empty();
    }

    /**
     * @param password contraseña (no {@code null})
     * @return si su longitud está entre {@value #MIN_LENGTH} y {@value #MAX_LENGTH}
     */
    public static boolean hasAllowedLength(String password) {
        return password.length() >= MIN_LENGTH && password.length() <= MAX_LENGTH;
    }

    /**
     * @param password contraseña (no {@code null})
     * @return si ocupa más de {@value #MAX_BYTES} bytes en UTF-8, la misma
     *         codificación que usa BCrypt
     */
    public static boolean exceedsMaxBytes(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /**
     * Indica si la contraseña está en la lista de comunes o es trivial.
     *
     * <p>
     * Se compara sin caracteres invisibles en los extremos (espacios normales,
     * de no separación, de ancho cero, tabuladores...) y sin distinguir
     * mayúsculas, para que {@code "  PASSWORD1234 "} o la misma contraseña
     * rodeada de espacios de no separación ({@code U+00A0}) se detecten igual
     * que {@code password1234}.
     * </p>
     *
     * @param password contraseña (no {@code null})
     * @return {@code true} si es común o tiene menos de
     *         {@value #MIN_DISTINCT_CHARACTERS} caracteres distintos
     */
    public static boolean isCommon(String password) {
        String normalized = normalize(password);
        return COMMON_PASSWORDS.contains(normalized)
                || normalized.codePoints().distinct().count() < MIN_DISTINCT_CHARACTERS;
    }

    /**
     * Indica si la contraseña contiene el nombre de usuario o la parte local
     * del email (lo que va antes de la {@code @}), sin distinguir mayúsculas.
     *
     * <p>
     * El usuario y la parte local se normalizan igual que en
     * {@link #isCommon(String)}: un usuario con un espacio de no separación al
     * final se busca sin él (con él nunca aparecería en la contraseña) y su
     * longitud se mide ya normalizada. La contraseña no necesita recortarse
     * para buscar algo dentro de ella.
     * </p>
     *
     * @param password contraseña (no {@code null})
     * @param username nombre de usuario (puede ser {@code null})
     * @param email    email (puede ser {@code null}; si no tiene {@code @} se
     *                 usa entero)
     * @return {@code true} si contiene alguno de los dos con al menos
     *         {@value #MIN_PERSONAL_DATA_LENGTH} caracteres
     */
    public static boolean containsPersonalData(String password, String username, String email) {
        String lowerPassword = password.toLowerCase(Locale.ROOT);
        return containsPart(lowerPassword, username) || containsPart(lowerPassword, localPart(email));
    }

    /** Lista de contraseñas comunes, para los tests. */
    static Set<String> commonPasswords() {
        return COMMON_PASSWORDS;
    }

    private static boolean containsPart(String lowerPassword, String part) {
        if (part == null) {
            return false;
        }
        String normalizedPart = normalize(part);
        return normalizedPart.length() >= MIN_PERSONAL_DATA_LENGTH
                && lowerPassword.contains(normalizedPart);
    }

    private static String localPart(String email) {
        if (email == null) {
            return null;
        }
        // lastIndexOf: el dominio no puede contener '@', la parte local entre comillas sí.
        int at = email.lastIndexOf('@');
        return at >= 0 ? email.substring(0, at) : email;
    }

    /** Quita los caracteres invisibles de los extremos y pasa a minúsculas (ver la clase). */
    private static String normalize(String value) {
        return EDGES.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
