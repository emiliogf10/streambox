package com.emilio.streambox.specification;

import java.text.Normalizer;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/**
 * Búsqueda por título compartida por películas ({@link MovieSpecification}) y
 * series ({@link SeriesSpecification}): sin distinguir mayúsculas ni acentos y,
 * como segundo intento, tolerante a erratas.
 *
 * <p>
 * Sustituye a {@code LikePatterns}, que solo ignoraba mayúsculas. Tenerlo en un
 * solo sitio garantiza que películas y series se busquen exactamente igual.
 * </p>
 *
 * <h2>1. "Contiene", sin mayúsculas ni acentos ({@link #contains})</h2>
 * <pre>
 * length(streambox_unaccent(lower(:texto))) &gt; 0
 *   AND streambox_unaccent(lower(title)) LIKE '%' || escapar(streambox_unaccent(lower(:texto))) || '%' ESCAPE '\'
 * </pre>
 * <ul>
 * <li><b>El texto del usuario va como parámetro ({@code ?}), nunca como literal
 * dentro del SQL</b> ({@link #bound}). Con {@code criteriaBuilder.literal(text)}
 * Hibernate lo escribía entre comillas en la propia sentencia: las comillas se
 * escapaban bien, pero un carácter NUL ({@code U+0000}) viajaba dentro del
 * mensaje del protocolo de PostgreSQL, que termina las sentencias con NUL. El
 * servidor la cortaba ahí, respondía {@code insufficient data left in message}
 * y Hikari daba la conexión por rota: cada petición con {@code %00} tiraba una
 * conexión del pool. Como parámetro, el valor viaja aparte, con su longitud, y
 * PostgreSQL lo rechaza como un dato no válido ({@code 22021}): la consulta
 * falla, pero la conexión sigue sana. Además, el texto ya no tiene que
 * escaparse para formar parte del SQL. (Los NUL y demás
 * caracteres de control los rechaza antes el controlador con un 400; esto es
 * la segunda barrera, la que protege la conexión aunque algo los dejara
 * pasar.)</li>
 * <li><b>La clave del texto no puede quedar vacía.</b> {@code unaccent} quita
 * las marcas combinantes: un texto formado solo por ellas (el acento suelto
 * {@code U+0301}) no está en blanco para Java, pero su clave es {@code ''} y
 * el patrón quedaría {@code LIKE '%%'}, que encuentra <em>todos</em> los
 * títulos. Se comprueba en la base de datos, con la misma función que el
 * resto de la clave, para que las dos decisiones (qué queda del texto y qué se
 * busca) las tome el mismo motor. Como solo depende del parámetro, PostgreSQL
 * la evalúa una vez por consulta (no por fila) y no impide usar el
 * índice.</li>
 * <li><b>Clave de búsqueda = {@code streambox_unaccent(lower(x))}, a los dos
 * lados y en la base de datos.</b> "manana" encuentra "Mañana" y "pokemon",
 * "Pokémon". Nada se normaliza en Java: {@code lower()} de PostgreSQL no
 * coincide con el de Java en algunas letras ({@code İ}, sigma final), y si cada
 * lado se normalizase con un motor distinto un título con esas letras no se
 * encontraría buscándolo entero (bug real, ver
 * {@code PostgresCatalogIntegrationTest}).</li>
 * <li><b>Es exactamente la expresión del índice</b>
 * {@code idx_movies_title_search}/{@code idx_series_title_search} (GIN de
 * trigramas, migración V5 de PostgreSQL). PostgreSQL solo usa un índice de
 * expresión si la consulta repite la misma expresión: por eso el título nunca
 * se normaliza de otra forma.</li>
 * <li><b>Primero normalizar, después escapar los comodines.</b> {@code unaccent}
 * convierte las formas de ancho completo en ASCII ({@code ％} en {@code %},
 * {@code ＿} en {@code _}, {@code ＼} en {@code \}). Si se escapase en Java
 * antes de normalizar, buscar {@code ＿} acabaría siendo el comodín {@code _} y
 * encontraría todos los títulos. Por eso el escape también se hace en la base
 * de datos, con {@code replace()} sobre el texto ya normalizado.</li>
 * </ul>
 *
 * <h2>2. Tolerante a erratas ({@link #resembles})</h2>
 * <pre>
 * streambox_word_similarity(clave(:texto), clave(title)) &gt;= 0.4
 * </pre>
 * <p>
 * {@code word_similarity} (extensión {@code pg_trgm}) compara los trigramas
 * (grupos de 3 letras) de la búsqueda con el fragmento del título que más se le
 * parece. Se eligió frente a {@code similarity} porque el usuario escribe
 * <em>una parte</em> del título: {@code similarity} compara cadenas enteras y
 * penaliza los títulos largos ("senor anillo" contra "El señor de los
 * anillos": 0,52 frente a 0,60).
 * </p>
 * <ul>
 * <li><b>Umbral {@value #TYPO_SIMILARITY_THRESHOLD}</b> (medido en PostgreSQL
 * 16): "interstelar" → "Interstellar" da 0,79; "pokemno" → "Pokémon", 0,63;
 * "incepcion" → "Inception", 0,54; y una transposición en una palabra de 6
 * letras, "matirx" → "Matrix", solo 0,43 (cambia 4 de sus 7 trigramas). Con el
 * 0,6 por defecto de {@code pg_trgm} esa errata, de las más comunes, no se
 * encontraría. El precio, inevitable con trigramas: "matirx" también se parece
 * a "Matilda" (0,57) o "Matador" (0,43), porque comparten "mat".</li>
 * <li><b>Solo con {@value #TYPO_TOLERANCE_MIN_CHARACTERS} letras o dígitos o
 * más</b> ({@link #toleratesTypos}). En una palabra de 4 letras una errata
 * cambia 3 de sus 5 trigramas y lo que queda en común son las dos primeras
 * letras: "amro" se parece igual (0,4) a "Amor", "Amadeus" y "Amélie". Con
 * 1 o 2 letras, cualquier título que las contenga supera cualquier umbral.
 * Con textos más cortos el predicado es siempre falso.</li>
 * <li><b>Es un segundo intento, no una ampliación.</b> Si se sumara siempre a
 * la búsqueda exacta, buscar "matrix" bien escrito traería también "Matilda".
 * El servicio lo usa solo cuando la búsqueda exacta no encuentra nada.</li>
 * <li><b>No usa el índice</b> (recorre la tabla): los operadores de
 * {@code pg_trgm} que sí lo usan ({@code <%}, {@code %>}) toman el umbral de
 * la variable de sesión {@code pg_trgm.word_similarity_threshold} (0,6), no
 * de la consulta. Medido con 50.000 títulos: unos 300 ms recorriendo la tabla
 * frente a 3 ms con el operador y {@code SET LOCAL} del umbral. Con un
 * catálogo de cientos o pocos miles de títulos son milisegundos y solo ocurre
 * cuando no hay coincidencias exactas; si el catálogo creciera, ese es el
 * cambio.</li>
 * <li><b>Solo PostgreSQL.</b> En H2 (tests) la función devuelve 0: no hay
 * tolerancia a erratas, se prueba contra PostgreSQL real.</li>
 * </ul>
 */
public final class TitleSearch {

    /**
     * Número mínimo de letras o dígitos para intentar la búsqueda tolerante a
     * erratas (ver la documentación de la clase).
     */
    public static final int TYPO_TOLERANCE_MIN_CHARACTERS = 5;

    /** Parecido mínimo ({@code word_similarity}, de 0 a 1) para aceptar un título con erratas. */
    public static final double TYPO_SIMILARITY_THRESHOLD = 0.4;

    /** Carácter de escape usado en las búsquedas {@code LIKE}. */
    private static final char LIKE_ESCAPE = '\\';

    /** Función de la migración V5 (en PostgreSQL envuelve {@code unaccent}; en H2, Java). */
    private static final String UNACCENT_FUNCTION = "streambox_unaccent";

    /** Función de la migración V5 (en PostgreSQL envuelve {@code word_similarity}; en H2 devuelve 0). */
    private static final String WORD_SIMILARITY_FUNCTION = "streambox_word_similarity";

    private TitleSearch() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Indica si un texto es lo bastante largo para la búsqueda tolerante a
     * erratas: tiene al menos {@value #TYPO_TOLERANCE_MIN_CHARACTERS} letras o
     * dígitos (los espacios y signos no cuentan, como en {@code pg_trgm}, que
     * solo forma trigramas con ellos).
     *
     * <p>
     * Es pública para que el servicio pueda ahorrarse el segundo intento cuando
     * no tiene sentido: con un texto más corto, {@link #resembles} es siempre
     * falso.
     * </p>
     *
     * <p>
     * Se cuentan las letras del texto <b>descompuesto</b> (NFKD), lo más
     * parecido en Java a lo que queda tras {@code unaccent}: las marcas
     * combinantes nunca cuentan (un texto de solo acentos sueltos no tiene
     * ninguna letra y no intenta el parecido) y las ligaduras cuentan por las
     * letras en que se convierten ({@code ﬁ} son dos). Es solo un umbral
     * aproximado: la clave con la que se compara se sigue calculando en la base
     * de datos, y si el texto normalizado no tuviera letras, {@code word_similarity}
     * daría 0 y no encontraría nada.
     * </p>
     *
     * @param text texto buscado ({@code null} = no)
     * @return {@code true} si se aplica la tolerancia a erratas
     */
    public static boolean toleratesTypos(String text) {

        return text != null
                && Normalizer.normalize(text, Normalizer.Form.NFKD).codePoints()
                        .filter(Character::isLetterOrDigit).limit(TYPO_TOLERANCE_MIN_CHARACTERS).count()
                        == TYPO_TOLERANCE_MIN_CHARACTERS;
    }

    /**
     * Predicado "{@code field} contiene {@code text}" sin distinguir mayúsculas
     * ni acentos y con los comodines del usuario como texto literal.
     *
     * @param criteriaBuilder constructor de la consulta
     * @param field           columna de texto sobre la que se busca
     * @param text            texto introducido por el usuario
     * @return predicado {@code length(clave(text)) > 0 AND clave(field) LIKE '%' || escapar(clave(text)) || '%' ESCAPE '\'}
     */
    static Predicate contains(CriteriaBuilder criteriaBuilder, Expression<String> field, String text) {

        Expression<String> textKey = searchKey(criteriaBuilder, bound(criteriaBuilder, text));
        Expression<String> pattern = criteriaBuilder.concat(
                criteriaBuilder.concat("%", escapeLike(criteriaBuilder, textKey)), "%");

        return criteriaBuilder.and(
                criteriaBuilder.greaterThan(criteriaBuilder.length(textKey), 0),
                criteriaBuilder.like(searchKey(criteriaBuilder, field), pattern, LIKE_ESCAPE));
    }

    /**
     * Predicado "{@code field} se parece a {@code text}" (tolerante a erratas).
     * Siempre falso si el texto es demasiado corto ({@link #toleratesTypos}).
     *
     * <p>
     * No necesita la comprobación de clave vacía de {@link #contains}: si el
     * texto normalizado se quedara sin letras, {@code word_similarity} no
     * tendría trigramas que comparar y daría 0, por debajo del umbral.
     * </p>
     *
     * @param criteriaBuilder constructor de la consulta
     * @param field           columna de texto sobre la que se busca
     * @param text            texto introducido por el usuario
     * @return predicado {@code word_similarity(clave(text), clave(field)) >= umbral}
     */
    static Predicate resembles(CriteriaBuilder criteriaBuilder, Expression<String> field, String text) {

        if (!toleratesTypos(text)) {
            return criteriaBuilder.disjunction();
        }
        return criteriaBuilder.greaterThanOrEqualTo(similarity(criteriaBuilder, field, text), TYPO_SIMILARITY_THRESHOLD);
    }

    /**
     * Ordena la consulta por parecido con el texto (de más a menos) y desempata
     * por {@code id}, para que la paginación sea estable.
     *
     * <p>
     * Se aplica dentro de la {@code Specification} porque Spring Data no admite
     * expresiones en el {@code Sort} de un {@code Pageable}. Spring Data solo lo
     * respeta si la página <b>no</b> trae orden propio (si lo trae, lo
     * sustituye), y lo quita de la consulta de recuento. No se aplica a
     * consultas de recuento ({@code Long}) ni a textos demasiado cortos.
     * </p>
     *
     * @param query           consulta (puede ser {@code null} en usos sin consulta)
     * @param criteriaBuilder constructor de la consulta
     * @param field           columna de texto sobre la que se busca
     * @param id              identificador de la entidad (desempate)
     * @param text            texto introducido por el usuario
     */
    static void orderBySimilarity(CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder,
            Expression<String> field, Path<?> id, String text) {

        if (query == null || Long.class.equals(query.getResultType()) || !toleratesTypos(text)) {
            return;
        }
        query.orderBy(
                criteriaBuilder.desc(similarity(criteriaBuilder, field, text)),
                criteriaBuilder.asc(id));
    }

    /** {@code word_similarity(clave(text), clave(field))}: el texto buscado va primero. */
    private static Expression<Double> similarity(CriteriaBuilder criteriaBuilder, Expression<String> field, String text) {

        return criteriaBuilder.function(WORD_SIMILARITY_FUNCTION, Double.class,
                searchKey(criteriaBuilder, bound(criteriaBuilder, text)),
                searchKey(criteriaBuilder, field));
    }

    /**
     * El texto del usuario como <b>parámetro enlazado</b> de la sentencia
     * ({@code ?} en el SQL y su valor aparte), no como literal (ver la
     * documentación de la clase: un NUL en un literal rompía la conexión).
     *
     * <p>
     * Una {@code Specification} no puede usar el mecanismo estándar de JPA
     * ({@code criteriaBuilder.parameter(...)} y {@code setParameter} sobre la
     * consulta): quien crea y ejecuta la consulta es Spring Data, y no
     * enlaza parámetros declarados dentro de la especificación. Hibernate
     * ofrece {@link HibernateCriteriaBuilder#value(Object)}: un parámetro que
     * ya lleva su valor y que Hibernate enlaza solo al ejecutar. El
     * {@code CriteriaBuilder} que Spring Data pasa a la especificación es el de
     * Hibernate (es el proveedor JPA del proyecto); si algún día no lo fuera,
     * se falla con un mensaje claro en vez de volver en silencio al literal.
     * </p>
     */
    private static Expression<String> bound(CriteriaBuilder criteriaBuilder, String text) {

        if (criteriaBuilder instanceof HibernateCriteriaBuilder hibernate) {
            return hibernate.value(text);
        }
        throw new IllegalStateException("La búsqueda por título necesita el CriteriaBuilder de Hibernate para "
                + "enlazar el texto como parámetro, y se recibió " + criteriaBuilder.getClass().getName());
    }

    /** Clave de búsqueda: {@code streambox_unaccent(lower(text))}, igual que el índice. */
    private static Expression<String> searchKey(CriteriaBuilder criteriaBuilder, Expression<String> text) {

        return criteriaBuilder.function(UNACCENT_FUNCTION, String.class, criteriaBuilder.lower(text));
    }

    /**
     * Escapa, en la base de datos, los caracteres con significado especial en
     * {@code LIKE}: primero la barra (si no, se duplicarían las barras de los
     * otros dos escapes) y después {@code %} y {@code _}.
     */
    private static Expression<String> escapeLike(CriteriaBuilder criteriaBuilder, Expression<String> text) {

        Expression<String> backslashes = criteriaBuilder.replace(text, "\\", "\\\\");
        Expression<String> percents = criteriaBuilder.replace(backslashes, "%", "\\%");
        return criteriaBuilder.replace(percents, "_", "\\_");
    }
}
