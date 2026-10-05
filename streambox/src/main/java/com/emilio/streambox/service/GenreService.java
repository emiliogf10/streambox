package com.emilio.streambox.service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.GenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.exception.GenreAlreadyExistsException;
import com.emilio.streambox.exception.GenreInUseException;
import com.emilio.streambox.exception.GenreNotFoundException;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.mapper.GenreMapper;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;

/**
 * Servicio con la lógica de negocio de los géneros cinematográficos.
 *
 * <p>
 * Devuelve {@link GenreResponse} y no la entidad, igual que el resto de
 * servicios.
 * </p>
 *
 * <p>
 * <strong>Comprobación previa + restricción como respaldo:</strong> antes de
 * escribir se comprueba en el servicio si el nombre está repetido o si el
 * género está en uso, para responder con un error claro. Pero entre esa
 * comprobación y la escritura otra petición puede adelantarse, así que la
 * garantía real son las restricciones de la base de datos ({@code UNIQUE} del
 * nombre y clave foránea de {@code movie_genres}). Cuando una de ellas salta,
 * su {@link DataIntegrityViolationException} se traduce a la misma excepción
 * de dominio que la comprobación previa, para que el cliente reciba el mismo
 * 409 gane quien gane la carrera.
 * </p>
 */
@Service
public class GenreService {

    /**
     * Caracteres invisibles en los extremos del nombre: separadores Unicode,
     * controles y caracteres de formato. Se precompila porque se usa en cada
     * alta y edición.
     */
    private static final Pattern EDGES =
            Pattern.compile("^[\\p{Z}\\p{Cc}\\p{Cf}]+|[\\p{Z}\\p{Cc}\\p{Cf}]+$");

    /** Secuencias de separadores o controles dentro del nombre (se colapsan en un espacio). */
    private static final Pattern INNER_SPACES = Pattern.compile("[\\p{Z}\\p{Cc}]+");

    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;

    /**
     * Crea el servicio de géneros.
     *
     * @param genreRepository repositorio de géneros
     * @param movieRepository repositorio de películas (para saber si un género está en uso)
     */
    public GenreService(GenreRepository genreRepository, MovieRepository movieRepository) {
        this.genreRepository = genreRepository;
        this.movieRepository = movieRepository;
    }

    /**
     * Obtiene todos los géneros.
     *
     * @return lista de géneros
     */
    @Transactional(readOnly = true)
    public List<GenreResponse> getAllGenres() {

        return GenreMapper.toResponseList(genreRepository.findAll());
    }

    /**
     * Crea un género.
     *
     * <p>
     * El nombre se normaliza antes de guardarlo (ver
     * {@link #normalizeAndValidateName}), para que {@code "ACCION"} y
     * {@code "accion"} no sean dos géneros distintos, y se valida su longitud
     * ya normalizado antes de consultar duplicados o escribir nada.
     * </p>
     *
     * @param request datos del género (ya validados)
     * @return el género creado
     * @throws InvalidParameterException   si el nombre normalizado no mide entre 2 y 50 caracteres
     * @throws GenreAlreadyExistsException si ya existe un género con ese nombre
     */
    @Transactional
    public GenreResponse createGenre(GenreRequest request) {

        String name = normalizeAndValidateName(request.name());

        if (genreRepository.existsByNameIgnoreCase(name)) {
            throw alreadyExists(name);
        }

        Genre genre = GenreMapper.toEntity(request);
        genre.setName(name);

        return GenreMapper.toResponse(saveAndFlush(genre));
    }

    /**
     * Renombra un género.
     *
     * <p>
     * Aplica la misma normalización que el alta. Renombrar un género a su
     * propio nombre (o cambiar solo sus mayúsculas) no es un conflicto: la
     * comprobación de duplicados excluye al propio género. Las películas que
     * lo tienen asignado no se tocan, porque la relación va por id.
     * </p>
     *
     * <p>
     * El nombre se valida <em>antes</em> de buscar el género: un dato de
     * entrada incorrecto es un 400 aunque el id no exista (igual que cuando lo
     * rechaza Bean Validation, que actúa antes de llegar aquí) y así no se
     * consulta la base de datos para nada.
     * </p>
     *
     * @param id      identificador del género
     * @param request datos nuevos (ya validados)
     * @return el género con su nombre definitivo
     * @throws InvalidParameterException   si el nombre normalizado no mide entre 2 y 50 caracteres
     * @throws GenreNotFoundException      si el género no existe
     * @throws GenreAlreadyExistsException si otro género ya tiene ese nombre
     */
    @Transactional
    public GenreResponse updateGenre(Long id, GenreRequest request) {

        String name = normalizeAndValidateName(request.name());
        Genre genre = findGenre(id);

        if (genreRepository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw alreadyExists(name);
        }

        genre.setName(name);

        return GenreMapper.toResponse(saveAndFlush(genre));
    }

    /**
     * Borra un género que ninguna película tenga asignado.
     *
     * <p>
     * Si alguna película lo usa, se rechaza con un mensaje que dice cuántas,
     * en lugar de quitárselo en cascada (ver {@link GenreInUseException}).
     * El recuento es una consulta {@code COUNT}: no se cargan las películas.
     * </p>
     *
     * <p>
     * <strong>Carrera:</strong> si entre el recuento y el borrado otra
     * petición asigna el género a una película, la clave foránea de
     * {@code movie_genres} rechaza el {@code DELETE}. Se fuerza el
     * {@code flush} aquí dentro para que esa violación salte en este método,
     * donde se puede traducir, y no al confirmar la transacción después de
     * salir de él (fuera de nuestro control, como un 409 genérico de
     * integridad). Se traduce <em>cualquier</em>
     * {@link DataIntegrityViolationException} porque un {@code DELETE} sobre
     * {@code genres} no puede violar otra cosa que una clave foránea que apunta
     * a él (no inserta valores, así que no hay unicidad, nulos ni longitudes
     * en juego).
     * </p>
     *
     * @param id identificador del género
     * @throws GenreNotFoundException si el género no existe
     * @throws GenreInUseException    si alguna película lo tiene asignado
     */
    @Transactional
    public void deleteGenre(Long id) {

        Genre genre = findGenre(id);

        long movies = movieRepository.countByGenres_Id(id);
        if (movies > 0) {
            throw new GenreInUseException(inUseMessage(genre.getName(), movies));
        }

        try {
            genreRepository.delete(genre);
            genreRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // Tras un fallo de flush la transacción queda inservible (en
            // PostgreSQL, abortada), así que no se puede repetir el recuento:
            // el mensaje no da la cifra.
            throw new GenreInUseException("No se puede eliminar el género \"" + genre.getName()
                    + "\": alguna película lo tiene asignado. "
                    + "Quítalo de esas películas antes de borrarlo.");
        }
    }

    /**
     * Normaliza el nombre de un género y comprueba que el resultado cumple el
     * contrato de longitud (entre {@value GenreRequest#NAME_MIN_LENGTH} y
     * {@value GenreRequest#NAME_MAX_LENGTH} caracteres).
     *
     * <p>
     * <strong>Normalización</strong> (ver {@link #normalizeName}): quita lo
     * invisible de los extremos, colapsa los espacios interiores en uno y deja
     * la primera letra en mayúscula y el resto en minúsculas.
     * </p>
     *
     * <p>
     * <strong>Por qué se valida otra vez:</strong> {@code @NotBlank} y
     * {@code @Size} de {@link GenreRequest} miden el texto <em>recibido</em>,
     * pero lo que se guarda es el texto normalizado, y normalizar cambia la
     * longitud en los dos sentidos:
     * </p>
     * <ul>
     * <li>La acorta al recortar: {@code " a"} mide 2 y pasa {@code @Size}, pero
     * se guardaría {@code "A"}; dos espacios duros (U+00A0) pasan
     * {@code @NotBlank} y darían un nombre invisible.</li>
     * <li>La alarga al pasar a minúsculas: la «İ» (U+0130) se convierte en
     * «i» + punto combinante, así que {@code "A" + 49 × "İ"} (50) acaba con 99
     * caracteres. Sin esta comprobación lo rechazaría la columna
     * {@code VARCHAR(50)} con un 409 {@code DATA_INTEGRITY_VIOLATION}
     * ("conflicto con datos existentes"), que es falso: es un dato de entrada
     * incorrecto.</li>
     * </ul>
     *
     * <p>
     * Se lanza {@link InvalidParameterException} sobre {@code name} con el
     * mismo mensaje que {@code @Size}, de modo que el cliente recibe el mismo
     * 400 {@code VALIDATION_ERROR} lo detecte quien lo detecte. Se llama al
     * principio del alta y de la edición, antes de comprobar duplicados y de
     * guardar: un nombre inválido nunca llega a la base de datos ni puede
     * responder 409.
     * </p>
     *
     * <p>
     * La longitud se mide con {@link String#length()} (unidades UTF-16), igual
     * que {@code @Size}, para que las dos barreras apliquen la misma regla. Es
     * además la medida más estricta: un texto de 50 unidades UTF-16 tiene como
     * mucho 50 caracteres, así que siempre cabe en {@code VARCHAR(50)}.
     * </p>
     *
     * @param name nombre recibido ({@code null} se trata como vacío)
     * @return nombre normalizado y válido
     * @throws InvalidParameterException si el nombre normalizado no mide entre 2 y 50
     */
    private static String normalizeAndValidateName(String name) {

        String normalized = name == null ? "" : normalizeName(name);
        int length = normalized.length();
        if (length < GenreRequest.NAME_MIN_LENGTH || length > GenreRequest.NAME_MAX_LENGTH) {
            throw new InvalidParameterException("name", GenreRequest.NAME_SIZE_MESSAGE);
        }
        return normalized;
    }

    /**
     * Normaliza el nombre de un género.
     *
     * <ol>
     * <li><strong>Extremos:</strong> quita separadores Unicode
     * ({@code \p{Z}}: espacio, espacio duro U+00A0, U+2007, U+202F, U+3000...),
     * caracteres de control ({@code \p{Cc}}: tabulador, saltos de línea...) y
     * de formato invisibles ({@code \p{Cf}}: espacio de ancho cero U+200B, BOM
     * U+FEFF...). No se usa {@link String#trim()} (solo quita caracteres
     * {@code <= U+0020}) ni {@link String#strip()}: este se basa en
     * {@link Character#isWhitespace}, que excluye a propósito los espacios
     * duros, justo el caso que permitía un nombre invisible. Los de formato se
     * quitan solo en los extremos: dentro del texto pueden tener sentido (el
     * unidor de ancho cero de algunos emojis).</li>
     * <li><strong>Interior:</strong> cualquier secuencia de separadores o
     * controles se sustituye por un único espacio normal, para que
     * {@code "Ciencia  ficción"} (dos espacios) o {@code "Ciencia ficción"}
     * con un espacio duro no sean un
     * género distinto de {@code "Ciencia ficción"} (la comprobación de
     * duplicados compara el texto ya normalizado).</li>
     * <li><strong>Mayúsculas:</strong> primera letra en mayúscula y el resto en
     * minúsculas. Se usa {@link Locale#ROOT} para que el resultado no dependa
     * del idioma del servidor (con el turco, por ejemplo,
     * {@code "I".toLowerCase()} no da {@code "i"}).</li>
     * </ol>
     *
     * <p>
     * Lo comparten el alta y la edición para que un mismo texto acabe siempre
     * igual, se escriba por donde se escriba.
     * </p>
     *
     * @param name nombre recibido (no nulo)
     * @return nombre normalizado (puede quedar vacío o fuera de longitud: lo
     *         comprueba {@link #normalizeAndValidateName})
     */
    private static String normalizeName(String name) {

        String collapsed = INNER_SPACES.matcher(EDGES.matcher(name).replaceAll("")).replaceAll(" ");
        if (collapsed.isEmpty()) {
            return collapsed;
        }
        return Character.toUpperCase(collapsed.charAt(0))
                + collapsed.substring(1).toLowerCase(Locale.ROOT);
    }

    private Genre findGenre(Long id) {

        return genreRepository.findById(id)
                .orElseThrow(() -> new GenreNotFoundException("Género no encontrado: " + id));
    }

    /**
     * Guarda el género forzando la escritura inmediata y traduce la violación
     * de unicidad del nombre a {@link GenreAlreadyExistsException}.
     *
     * <p>
     * El {@code flush} hace que el {@code INSERT}/{@code UPDATE} se ejecute
     * aquí y no al confirmar la transacción, para poder capturar el error.
     * Solo se traduce la unicidad (mirando el {@code SQLSTATE}, no el texto):
     * cualquier otra violación indicaría un fallo distinto y se relanza tal
     * cual para no disfrazarla de "nombre repetido".
     * </p>
     *
     * @param genre entidad a guardar
     * @return la entidad guardada
     */
    private Genre saveAndFlush(Genre genre) {

        try {
            return genreRepository.saveAndFlush(genre);
        } catch (DataIntegrityViolationException e) {
            if (e instanceof DuplicateKeyException
                    || SqlStates.UNIQUE_VIOLATION.equals(SqlStates.find(e))) {
                throw alreadyExists(genre.getName());
            }
            throw e;
        }
    }

    private static GenreAlreadyExistsException alreadyExists(String name) {

        return new GenreAlreadyExistsException("Ya existe un género con el nombre \"" + name + "\"");
    }

    /**
     * Construye el mensaje del 409 de borrado, con el recuento y la
     * concordancia en singular o plural.
     */
    private static String inUseMessage(String name, long movies) {

        String usage = movies == 1
                ? "lo usa 1 película. Quítalo de esa película"
                : "lo usan " + movies + " películas. Quítalo de esas películas";
        return "No se puede eliminar el género \"" + name + "\": " + usage + " antes de borrarlo.";
    }
}
