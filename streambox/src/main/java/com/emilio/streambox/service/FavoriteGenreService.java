package com.emilio.streambox.service;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.FavoriteGenresRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.mapper.GenreMapper;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio de los géneros favoritos del usuario autenticado («Tus gustos»,
 * {@code /api/users/me/genres}).
 *
 * <p>
 * <b>Para qué existen.</b> Resuelven el «arranque en frío» de las
 * recomendaciones: una cuenta nueva no tiene «me gusta», así que el recomendador
 * no tenía en qué basarse. Con 3 a 5 géneros elegidos al empezar (pregunta de
 * bienvenida) o desde el perfil, ya los tiene: cada favorito pesa como 2 «me
 * gusta» ({@code GenreBasedRecommender}).
 * </p>
 *
 * <p>
 * Como los «me gusta», se leen y escriben con SQL directo sobre la tabla de
 * unión {@code user_favorite_genres} ({@code GenreRepository}), sin colección en
 * {@code User}: nunca se carga la lista entera a través de la cuenta (la
 * sustitución solo lee la fila de {@code users} para bloquearla). El id del
 * usuario sale siempre del token, nunca de la URL (sin IDOR).
 * </p>
 */
@Service
public class FavoriteGenreService {

    /** Mensaje si la lista no está vacía ni tiene entre 3 y 5 elementos. */
    static final String COUNT_MESSAGE = "Elige entre " + FavoriteGenresRequest.MIN_GENRES + " y "
            + FavoriteGenresRequest.MAX_GENRES + " géneros, o ninguno.";

    /** Mensaje si un id aparece más de una vez. */
    static final String DUPLICATES_MESSAGE = "Hay géneros repetidos.";

    /** Mensaje si algún id no es un género que exista y tenga algún título visible (o es {@code null}). */
    static final String NOT_ELIGIBLE_MESSAGE = "Alguno de los géneros no existe o no tiene títulos.";

    /** Campo en el que se informa de cualquier error de la lista ({@code validationErrors.genreIds}). */
    private static final String FIELD = "genreIds";

    private final GenreRepository genreRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /**
     * Crea el servicio de géneros favoritos.
     *
     * @param genreRepository repositorio de géneros (favoritos y géneros elegibles)
     * @param userRepository  repositorio de usuarios (cierra la pregunta de bienvenida)
     * @param clock           reloj de la aplicación (fecha de la elección y del cierre)
     */
    public FavoriteGenreService(GenreRepository genreRepository, UserRepository userRepository, Clock clock) {

        this.genreRepository = genreRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    /**
     * Devuelve los géneros favoritos del usuario que <strong>siguen teniendo
     * algún título visible</strong>, por nombre.
     *
     * <p>
     * <b>Por qué se filtran</b> (decisión del orquestador). Un favorito puede
     * quedarse sin títulos después de elegirlo. Se queda en la base de datos
     * (no aporta nada a las recomendaciones mientras esté vacío), pero no se
     * muestra: así lo que el usuario ve en «Tus gustos» es exactamente lo que
     * puede volver a guardar (el {@code PUT} rechaza géneros sin títulos) y lo
     * que nombra el inicio en «Porque te gustan…». Si lo vuelve a guardar sin
     * él, desaparece también de la base de datos; si el género recupera
     * títulos, vuelve a aparecer.
     * </p>
     *
     * <p>
     * Dos consultas como mucho (los favoritos y cuáles son visibles); una si no
     * tiene ninguno.
     * </p>
     *
     * @param userId identificador del usuario autenticado
     * @return sus géneros favoritos visibles; vacía si no tiene ninguno
     */
    @Transactional(readOnly = true)
    public List<GenreResponse> getFavoriteGenres(Long userId) {

        List<Genre> favorites = genreRepository.findFavoritesByUserId(userId);
        if (favorites.isEmpty()) {
            return List.of();
        }
        Set<Long> visible = new HashSet<>(genreRepository.findIdsWithVisibleTitles(
                favorites.stream().map(Genre::getId).toList()));

        return favorites.stream()
                .filter(genre -> visible.contains(genre.getId()))
                .map(GenreMapper::toResponse)
                .toList();
    }

    /**
     * Devuelve los géneros que se pueden elegir como favoritos: los que tienen
     * algún título visible (una película, o una serie con episodios), por
     * nombre. Un género vacío no se ofrece: elegirlo no daría ninguna
     * recomendación.
     *
     * @return géneros elegibles
     */
    @Transactional(readOnly = true)
    public List<GenreResponse> getGenreOptions() {

        return GenreMapper.toResponseList(genreRepository.findWithVisibleTitles());
    }

    /**
     * Sustituye la lista entera de géneros favoritos del usuario y cierra para
     * siempre su pregunta de bienvenida.
     *
     * <p>
     * <b>Validación</b>, toda antes de escribir y en este orden (cada fallo es un
     * 400 {@code VALIDATION_ERROR} en {@code validationErrors.genreIds}):
     * </p>
     * <ol>
     * <li>Número: vacía, o entre {@value FavoriteGenresRequest#MIN_GENRES} y
     * {@value FavoriteGenresRequest#MAX_GENRES}. Va primero porque acota el
     * tamaño de todo lo demás: una lista enorme no llega a la base de
     * datos.</li>
     * <li>Sin {@code null} ni repetidos: no necesitan consultar nada. Un
     * repetido no se «arregla» quitándolo: {@code [1, 1, 2]} parece de 3
     * géneros y en realidad son 2, y el cliente tiene un fallo que debe
     * ver.</li>
     * <li>Que existan y tengan algún título visible: una sola consulta
     * ({@code findIdsWithVisibleTitles}, la misma condición que la lista de
     * opciones).</li>
     * </ol>
     *
     * <p>
     * <b>Escritura</b>, en una transacción y en este orden obligatorio:
     * </p>
     * <ol>
     * <li>{@code findForUpdateById}: <strong>bloquea la fila del usuario</strong>
     * ({@code SELECT ... FOR UPDATE}) antes de leer ni escribir nada. Así dos
     * {@code PUT} simultáneos del mismo usuario van en fila y gana entero el
     * último: el segundo espera aquí y, cuando entra, ya ve lo que el primero
     * confirmó. Sin el cerrojo, el segundo podía no borrar lo que el primero
     * acababa de insertar y la lista acabaría mezclada, con más de cinco
     * géneros; y su comparación con la lista guardada (paso 3) podría hacerse
     * contra una lista que estaba a punto de cambiar. Si no encuentra la fila,
     * la cuenta ya no existe: 404, como el resto de {@code /me}.</li>
     * <li>{@code markGenresOnboarded}, <strong>solo si la fecha de la pregunta
     * es nula</strong>: la rellena (contestar <em>o</em> pulsar «Ahora no», que
     * envía la lista vacía, la cierra). Si ya estaba cerrada no se toca
     * {@code users}: el {@code UPDATE} no cambiaría nada y solo dejaría una
     * versión muerta de la fila (en PostgreSQL, cada {@code UPDATE} escribe
     * una fila nueva aunque el valor sea el mismo). La fecha leída es fiable
     * porque se leyó ya con la fila bloqueada.</li>
     * <li>Si el conjunto pedido es <strong>igual</strong> al guardado (todas las
     * filas de {@code user_favorite_genres}, también las de géneros que se
     * hayan quedado sin títulos), no se borra ni se inserta nada y se devuelve
     * esa misma lista. Es lo que evita que un bucle de {@code PUT} idénticos
     * (un doble clic, un cliente que reintenta) genere escrituras y filas
     * muertas gratis, y conserva el {@code created_at} de cada favorito.</li>
     * <li>{@code clearFavorites} (solo si había alguno): borra la lista
     * anterior.</li>
     * <li>{@code addFavorites} (solo si la nueva no está vacía): si inserta
     * menos filas de las pedidas, un género se borró entre la validación y el
     * {@code INSERT}; se lanza la misma excepción de validación para que se
     * deshaga todo, también el borrado (la lista anterior queda intacta).</li>
     * </ol>
     *
     * <p>
     * Es idempotente: repetir la misma lista (en cualquier orden) da la misma
     * respuesta, no escribe nada y conserva la fecha de la pregunta (la primera
     * vez). La validación se hace antes del cerrojo para no tener la fila
     * bloqueada mientras tanto; el hueco que deja (un género que pierda sus
     * títulos justo entre medias) es inofensivo: el favorito se guarda y,
     * simplemente, no aporta nada hasta que vuelva a tener títulos.
     * </p>
     *
     * @param userId  identificador del usuario autenticado (del token)
     * @param request lista completa de géneros (no {@code null}: lo valida el DTO)
     * @return los géneros guardados, por nombre (la misma forma que
     *         {@link #getFavoriteGenres(Long)})
     * @throws InvalidParameterException si la lista no es válida
     * @throws UserNotFoundException     si la cuenta ya no existe
     */
    @Transactional
    public List<GenreResponse> replaceFavoriteGenres(Long userId, FavoriteGenresRequest request) {

        Set<Long> genreIds = validateShape(request.genreIds());
        if (!genreIds.isEmpty()
                && new HashSet<>(genreRepository.findIdsWithVisibleTitles(genreIds)).size() != genreIds.size()) {
            throw new InvalidParameterException(FIELD, NOT_ELIGIBLE_MESSAGE);
        }

        User user = userRepository.findForUpdateById(userId)
                .orElseThrow(() -> new UserNotFoundException("Usuario no encontrado"));
        if (user.getGenresOnboardedAt() == null) {
            userRepository.markGenresOnboarded(userId, clock.instant());
        }

        List<Genre> current = genreRepository.findFavoritesByUserId(userId);
        if (sameIds(current, genreIds)) {
            return GenreMapper.toResponseList(current);
        }
        if (!current.isEmpty()) {
            genreRepository.clearFavorites(userId);
        }
        if (genreIds.isEmpty()) {
            return List.of();
        }
        if (genreRepository.addFavorites(userId, genreIds, clock.instant()) != genreIds.size()) {
            throw new InvalidParameterException(FIELD, NOT_ELIGIBLE_MESSAGE);
        }
        return GenreMapper.toResponseList(genreRepository.findFavoritesByUserId(userId));
    }

    /**
     * Indica si los favoritos guardados son exactamente los ids pedidos, sin
     * importar el orden.
     *
     * <p>
     * Se compara como conjunto porque la lista no tiene orden propio (la
     * respuesta va siempre por nombre): {@code [3, 1, 2]} y {@code [1, 2, 3]}
     * son la misma elección. Basta con comparar tamaños y pertenencia: ni los
     * guardados (clave primaria {@code (user_id, genre_id)}) ni los pedidos
     * (ya validados) tienen repetidos.
     * </p>
     *
     * @param current  favoritos guardados (todos, también los que ya no tienen títulos)
     * @param genreIds ids pedidos, ya validados y sin repetidos
     * @return {@code true} si son el mismo conjunto
     */
    private static boolean sameIds(List<Genre> current, Set<Long> genreIds) {

        return current.size() == genreIds.size()
                && current.stream().map(Genre::getId).allMatch(genreIds::contains);
    }

    /**
     * Comprueba lo que no necesita la base de datos: número de elementos, sin
     * {@code null} y sin repetidos.
     *
     * @param ids lista recibida (no {@code null})
     * @return los ids, en el orden recibido y ya sin posibilidad de repetidos
     * @throws InvalidParameterException si no cumple alguna de las tres reglas
     */
    private static Set<Long> validateShape(List<Long> ids) {

        int size = ids.size();
        if (size != 0 && (size < FavoriteGenresRequest.MIN_GENRES || size > FavoriteGenresRequest.MAX_GENRES)) {
            throw new InvalidParameterException(FIELD, COUNT_MESSAGE);
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new InvalidParameterException(FIELD, NOT_ELIGIBLE_MESSAGE);
        }
        Set<Long> distinct = new LinkedHashSet<>(ids);
        if (distinct.size() != size) {
            throw new InvalidParameterException(FIELD, DUPLICATES_MESSAGE);
        }
        return distinct;
    }
}
