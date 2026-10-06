package com.emilio.streambox.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.EpisodeResponse;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.exception.EpisodeAlreadyExistsException;
import com.emilio.streambox.exception.EpisodeNotFoundException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.mapper.EpisodeMapper;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Servicio con la gestión de los episodios de una serie (solo
 * administradores; la regla está en {@code SecurityConfig}).
 *
 * <p>
 * Todas las operaciones reciben el id de la serie de la ruta
 * ({@code /api/series/{id}/episodes/...}) y comprueban que el episodio
 * pertenece a ella ({@code findByIdAndSeriesId}): pedir el episodio 7 a través
 * de la serie 1 cuando es de la serie 2 da 404, en lugar de modificar un
 * episodio de otra serie.
 * </p>
 *
 * <p>
 * <strong>Comprobación previa + restricción como respaldo</strong> (mismo
 * patrón que {@link GenreService}): antes de escribir se comprueba si la
 * temporada y el número ya están ocupados, para responder con un mensaje
 * claro. Pero dos altas simultáneas pueden pasar las dos la comprobación; la
 * garantía real es {@code uk_episodes_series_season_episode}, y su violación
 * se traduce al mismo 409 {@code EPISODE_ALREADY_EXISTS}. Para poder
 * capturarla aquí se guarda con {@code saveAndFlush} (si no, el
 * {@code INSERT}/{@code UPDATE} se ejecutaría al confirmar, ya fuera de este
 * método).
 * </p>
 */
@Service
public class EpisodeService {

    private static final String EPISODE_NOT_FOUND = "Episodio no encontrado";

    private final EpisodeRepository episodeRepository;
    private final SeriesRepository seriesRepository;

    /**
     * Crea el servicio de episodios.
     *
     * @param episodeRepository repositorio de episodios
     * @param seriesRepository  repositorio de series (para comprobar que la serie existe)
     */
    public EpisodeService(EpisodeRepository episodeRepository, SeriesRepository seriesRepository) {
        this.episodeRepository = episodeRepository;
        this.seriesRepository = seriesRepository;
    }

    /**
     * Añade un episodio a una serie.
     *
     * <p>
     * La serie puede estar vacía (es lo normal al subir el primer episodio,
     * que es justo el que la hace visible para los usuarios). Se enlaza con
     * {@code getReferenceById}, que no consulta la base de datos: basta con el
     * id para rellenar la clave foránea, y la existencia ya se comprobó antes.
     * </p>
     *
     * @param seriesId identificador de la serie
     * @param request  datos del episodio
     * @return el episodio creado
     * @throws SeriesNotFoundException       si la serie no existe
     * @throws EpisodeAlreadyExistsException si ya hay un episodio con esa temporada y número
     */
    @Transactional
    public EpisodeResponse createEpisode(Long seriesId, EpisodeRequest request) {

        requireSeries(seriesId);

        if (episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumber(
                seriesId, request.seasonNumber(), request.episodeNumber())) {
            throw alreadyExists(request);
        }

        Episode episode = EpisodeMapper.toEntity(request, seriesRepository.getReferenceById(seriesId));

        return EpisodeMapper.toResponse(saveAndFlush(episode, request));
    }

    /**
     * Modifica un episodio de una serie (puede cambiar de temporada o número
     * dentro de la misma serie).
     *
     * <p>
     * La comprobación de duplicados se hace <em>antes</em> de copiar los datos
     * nuevos en la entidad: si se hiciera después, Hibernate podría volcar el
     * cambio a la base de datos antes de esa consulta (vaciado automático
     * previo a las consultas) y la violación saltaría ahí, fuera del
     * {@code try} que la traduce. Se excluye el propio episodio, para que
     * guardarlo sin cambiar su posición no sea un conflicto.
     * </p>
     *
     * @param seriesId  identificador de la serie
     * @param episodeId identificador del episodio
     * @param request   datos nuevos
     * @return el episodio actualizado
     * @throws SeriesNotFoundException       si la serie no existe
     * @throws EpisodeNotFoundException     si el episodio no existe o es de otra serie
     * @throws EpisodeAlreadyExistsException si otro episodio ocupa ya esa temporada y número
     */
    @Transactional
    public EpisodeResponse updateEpisode(Long seriesId, Long episodeId, EpisodeRequest request) {

        requireSeries(seriesId);
        Episode episode = findEpisode(seriesId, episodeId);

        if (episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(
                seriesId, request.seasonNumber(), request.episodeNumber(), episodeId)) {
            throw alreadyExists(request);
        }

        EpisodeMapper.updateEntity(request, episode);

        return EpisodeMapper.toResponse(saveAndFlush(episode, request));
    }

    /**
     * Elimina un episodio de una serie. Si era el último, la serie vuelve a
     * quedar oculta para los usuarios (y desaparece de sus listas, aunque la
     * fila de favoritos se conserva por si vuelve a tener episodios).
     *
     * @param seriesId  identificador de la serie
     * @param episodeId identificador del episodio
     * @throws SeriesNotFoundException   si la serie no existe
     * @throws EpisodeNotFoundException si el episodio no existe o es de otra serie
     */
    @Transactional
    public void deleteEpisode(Long seriesId, Long episodeId) {

        requireSeries(seriesId);
        episodeRepository.delete(findEpisode(seriesId, episodeId));
    }

    /**
     * Comprueba que la serie existe (aunque esté vacía). Se hace aparte de la
     * búsqueda del episodio para que el 404 diga qué es lo que falta: la serie
     * o el episodio.
     */
    private void requireSeries(Long seriesId) {

        if (!seriesRepository.existsById(seriesId)) {
            throw new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
        }
    }

    private Episode findEpisode(Long seriesId, Long episodeId) {

        return episodeRepository.findByIdAndSeriesId(episodeId, seriesId)
                .orElseThrow(() -> new EpisodeNotFoundException(EPISODE_NOT_FOUND));
    }

    /**
     * Guarda forzando la escritura inmediata y traduce las violaciones de
     * integridad que pueden deberse a una carrera, mirando el {@code SQLSTATE}
     * y no el texto del mensaje:
     *
     * <ul>
     *   <li>Unicidad ({@code 23505}): otra petición ocupó la misma temporada y
     *       número entre la comprobación y la escritura: 409.</li>
     *   <li>Clave foránea ({@code 23503}; {@code 23506} en H2): la serie se
     *       borró entre la comprobación y el {@code INSERT}: 404 de serie.</li>
     *   <li>Cualquier otra se relanza tal cual (acabará en un 500 y en el log),
     *       porque indicaría un fallo de programación y no conviene
     *       disfrazarla.</li>
     * </ul>
     *
     * @param episode entidad a guardar
     * @param request datos recibidos (para el mensaje del 409)
     * @return la entidad guardada
     */
    private Episode saveAndFlush(Episode episode, EpisodeRequest request) {

        try {
            return episodeRepository.saveAndFlush(episode);
        } catch (DataIntegrityViolationException e) {
            String sqlState = SqlStates.find(e);
            if (e instanceof DuplicateKeyException || SqlStates.UNIQUE_VIOLATION.equals(sqlState)) {
                throw alreadyExists(request);
            }
            if (SqlStates.FOREIGN_KEY_VIOLATION.equals(sqlState)
                    || SqlStates.H2_FOREIGN_KEY_PARENT_MISSING.equals(sqlState)) {
                throw new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
            }
            throw e;
        }
    }

    private static EpisodeAlreadyExistsException alreadyExists(EpisodeRequest request) {

        return new EpisodeAlreadyExistsException("Ya existe el episodio " + request.episodeNumber()
                + " de la temporada " + request.seasonNumber());
    }
}
