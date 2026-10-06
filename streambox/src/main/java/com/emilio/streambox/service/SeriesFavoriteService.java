package com.emilio.streambox.service;

import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.SeriesAlreadyInFavoritesException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.SeriesNotInFavoritesException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Servicio de las series de "Mi lista" de cada usuario.
 *
 * <p>
 * Funciona igual que {@link FavoriteService} (películas): cada operación es un
 * {@code INSERT} o {@code DELETE} directo sobre la tabla de unión
 * {@code user_favorite_series}, sin cargar la lista entera, y todas reciben el
 * id del usuario <em>autenticado</em> (del token, nunca de la URL ni del
 * cuerpo), de modo que nadie puede tocar la lista de otro.
 * </p>
 *
 * <p>
 * <b>Series sin episodios.</b> Para los usuarios no existen: no se pueden
 * añadir (404) y, si una serie de la lista se queda vacía, deja de aparecer en
 * ella. La fila de favoritos no se borra: si el administrador vuelve a subir
 * episodios, la serie reaparece en la lista sin que el usuario haga nada.
 * Quitarla sí se permite aunque esté vacía, porque el usuario no está pidiendo
 * verla sino dejar de tenerla.
 * </p>
 */
@Service
public class SeriesFavoriteService {

    private static final String ALREADY_IN_FAVORITES = "La serie ya está incluida en tu lista de favoritos";
    private static final String NOT_IN_FAVORITES = "La serie no está incluida en tu lista de favoritos";

    private final SeriesRepository seriesRepository;
    private final EpisodeRepository episodeRepository;
    private final UserRepository userRepository;

    /**
     * Crea el servicio de favoritos de series.
     *
     * @param seriesRepository  repositorio de series (contiene las consultas de favoritos de series)
     * @param episodeRepository repositorio de episodios (visibilidad y recuentos)
     * @param userRepository    repositorio de usuarios
     */
    public SeriesFavoriteService(
            SeriesRepository seriesRepository,
            EpisodeRepository episodeRepository,
            UserRepository userRepository) {

        this.seriesRepository = seriesRepository;
        this.episodeRepository = episodeRepository;
        this.userRepository = userRepository;
    }

    /**
     * Obtiene las series de la lista del usuario que tienen episodios,
     * ordenadas por título.
     *
     * <p>
     * Tres consultas en total, tenga las series que tenga: la lista, los
     * recuentos de todas sus series de una vez y los géneros por lotes. Los
     * mismos recuentos sirven para descartar las series vacías (no aparecen
     * en el resultado de la consulta agrupada), así que filtrar no cuesta
     * ninguna consulta más.
     * </p>
     *
     * @param userId identificador del usuario autenticado
     * @return series visibles de su lista
     */
    @Transactional(readOnly = true)
    public List<SeriesResponse> getFavorites(Long userId) {

        List<Series> favorites = seriesRepository.findFavoritesByUserId(userId);
        Map<Long, SeriesEpisodeStats> stats =
                SeriesEpisodeCounts.load(episodeRepository, SeriesEpisodeCounts.ids(favorites));

        return favorites.stream()
                .filter(series -> stats.containsKey(series.getId()))
                .map(series -> SeriesEpisodeCounts.toResponse(series, stats))
                .toList();
    }

    /**
     * Añade una serie a la lista.
     *
     * <p>
     * Que la serie sea visible se comprueba con
     * {@code episodeRepository.existsBySeriesId}: si tiene algún episodio, la
     * serie existe (clave foránea), así que una sola consulta responde a las
     * dos preguntas. Antes del {@code INSERT} se mira si ya estaba
     * ({@code isFavorite}) en lugar de provocar la violación de la clave
     * primaria, porque en PostgreSQL esa violación deja la transacción
     * abortada.
     * </p>
     *
     * @param userId   identificador del usuario autenticado
     * @param seriesId identificador de la serie
     * @throws SeriesNotFoundException           si la serie no existe o no tiene episodios
     * @throws SeriesAlreadyInFavoritesException si ya estaba en la lista
     */
    @Transactional
    public void addFavorite(Long userId, Long seriesId) {

        if (!episodeRepository.existsBySeriesId(seriesId)) {
            throw new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
        }
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException("Usuario no encontrado");
        }
        if (seriesRepository.isFavorite(userId, seriesId)) {
            throw new SeriesAlreadyInFavoritesException(ALREADY_IN_FAVORITES);
        }

        try {
            seriesRepository.addFavorite(userId, seriesId);
        } catch (DataIntegrityViolationException e) {
            throw translateInsertViolation(e);
        }
    }

    /**
     * Quita una serie de la lista.
     *
     * <p>
     * <b>Por qué se borra primero y se pregunta después.</b> Las series sin
     * episodios tienen que ser, para un usuario, indistinguibles de las que no
     * existen. Si se comprobara antes la existencia con {@code existsById}
     * (que no mira los episodios), una serie oculta que el usuario no tenía
     * daría "no está en tu lista" y una inexistente "Serie no encontrada": con
     * ids secuenciales, recorriéndolos se sabría qué series vacías está
     * preparando el administrador.
     * </p>
     *
     * <ul>
     *   <li>Si el {@code DELETE} borra la fila, se termina (204), esté la serie
     *       oculta o no: quitar de la lista una serie que ya estaba en ella es la
     *       excepción deliberada del contrato, y no revela nada que el usuario no
     *       supiera (la añadió él cuando era visible).</li>
     *   <li>Si no borra ninguna, la serie no estaba en su lista. Solo entonces se
     *       mira si es <em>visible</em> ({@code existsBySeriesId}): si lo es,
     *       {@code SERIES_NOT_IN_FAVORITES}; si no existe o está oculta, el mismo
     *       "Serie no encontrada" en los dos casos.</li>
     * </ul>
     *
     * <p>
     * En el caso normal (la serie estaba en la lista) es una sola consulta.
     * </p>
     *
     * @param userId   identificador del usuario autenticado
     * @param seriesId identificador de la serie
     * @throws SeriesNotFoundException       si no estaba en la lista y la serie no existe o no tiene episodios
     * @throws SeriesNotInFavoritesException si no estaba en la lista y la serie es visible
     */
    @Transactional
    public void removeFavorite(Long userId, Long seriesId) {

        if (seriesRepository.removeFavorite(userId, seriesId) > 0) {
            return;
        }
        if (episodeRepository.existsBySeriesId(seriesId)) {
            throw new SeriesNotInFavoritesException(NOT_IN_FAVORITES);
        }
        throw new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
    }

    /**
     * Quita todas las series de la lista del usuario (sus películas no se
     * tocan). Si ya estaba vacía no es un error.
     *
     * @param userId identificador del usuario autenticado
     */
    @Transactional
    public void clearFavorites(Long userId) {

        seriesRepository.clearFavorites(userId);
    }

    /**
     * Traduce una violación de integridad del {@code INSERT}, con el mismo
     * criterio que {@code FavoriteService.translateInsertViolation}:
     *
     * <ul>
     *   <li>Unicidad ({@code 23505}): dos altas simultáneas pasaron las dos la
     *       comprobación {@code isFavorite}; la clave primaria impide el
     *       duplicado: 409, igual que si se hubiera detectado antes.</li>
     *   <li>Clave foránea ({@code 23503}; {@code 23506} en H2): la serie se
     *       borró entre la comprobación y el {@code INSERT}: 404.</li>
     *   <li>Cualquier otra se relanza tal cual (500 y traza en el log).</li>
     * </ul>
     *
     * @param error excepción lanzada por el repositorio
     * @return la excepción de dominio a lanzar; si no es reconocida, el propio {@code error}
     */
    private RuntimeException translateInsertViolation(DataIntegrityViolationException error) {

        String sqlState = SqlStates.find(error);

        if (error instanceof DuplicateKeyException || SqlStates.UNIQUE_VIOLATION.equals(sqlState)) {
            return new SeriesAlreadyInFavoritesException(ALREADY_IN_FAVORITES);
        }
        if (SqlStates.FOREIGN_KEY_VIOLATION.equals(sqlState)
                || SqlStates.H2_FOREIGN_KEY_PARENT_MISSING.equals(sqlState)) {
            return new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
        }
        return error;
    }
}
