package com.emilio.streambox.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.SeriesDetailResponse;
import com.emilio.streambox.dto.SeriesRequest;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.GenreNotFoundException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.mapper.SeriesMapper;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.specification.SeriesSpecification;

/**
 * Servicio con la lógica de negocio del catálogo de series: consultas de los
 * usuarios, vistas del panel de administración y alta, edición y borrado de
 * series (los episodios están en {@link EpisodeService}).
 *
 * <p>
 * <b>Visibilidad.</b> Una serie sin episodios existe (el administrador la
 * crea antes de subirlos), pero para los usuarios es como si no existiera: no
 * sale en el listado ni en la búsqueda ({@link SeriesSpecification#hasEpisodes()})
 * y su detalle da 404 ({@code findVisibleById}). Las vistas de administración
 * ({@code getAdmin...}) sí la muestran.
 * </p>
 *
 * <p>
 * <b>Coste en consultas</b> (comprobado en {@code SeriesCatalogIntegrationTest}):
 * </p>
 * <ul>
 * <li>Una página de series: la página, el total (si hace falta), los géneros
 * por lotes y <em>una</em> consulta agrupada con los recuentos de temporadas y
 * episodios de toda la página. No crece con el tamaño de la página.</li>
 * <li>El detalle: la serie con sus géneros y la lista ordenada de episodios;
 * dos consultas, tenga los episodios que tenga. No se traen géneros y
 * episodios en un mismo {@code JOIN} porque devolvería géneros × episodios
 * filas.</li>
 * </ul>
 *
 * <p>
 * Como el resto de servicios, devuelve DTOs y los construye dentro de la
 * transacción ({@code open-in-view=false}; los géneros son de carga diferida).
 * </p>
 */
@Service
public class SeriesService {

    /** Mensaje del 404; el mismo para "no existe" y "no tiene episodios" (no se revela cuál). */
    static final String SERIES_NOT_FOUND = "Serie no encontrada";

    private final SeriesRepository seriesRepository;
    private final EpisodeRepository episodeRepository;
    private final GenreRepository genreRepository;

    /**
     * Crea el servicio del catálogo de series.
     *
     * @param seriesRepository  repositorio de series
     * @param episodeRepository repositorio de episodios (detalle y recuentos)
     * @param genreRepository   repositorio de géneros
     */
    public SeriesService(
            SeriesRepository seriesRepository,
            EpisodeRepository episodeRepository,
            GenreRepository genreRepository) {

        this.seriesRepository = seriesRepository;
        this.episodeRepository = episodeRepository;
        this.genreRepository = genreRepository;
    }

    // ------------------------------------------------------------------
    // Consultas de los usuarios (solo series con episodios)
    // ------------------------------------------------------------------

    /**
     * Obtiene una página del catálogo de series visibles.
     *
     * @param pageable página, tamaño y ordenación solicitados
     * @return página de series con al menos un episodio
     */
    @Transactional(readOnly = true)
    public Page<SeriesResponse> getSeries(Pageable pageable) {

        return toResponsePage(seriesRepository.findAll(SeriesSpecification.hasEpisodes(), pageable));
    }

    /**
     * Busca series visibles combinando filtros opcionales.
     *
     * <p>
     * Los filtros {@code null} (o un título en blanco) se ignoran; sin ningún
     * filtro devuelve el catálogo visible completo. Mismas reglas que la
     * búsqueda de películas.
     * </p>
     *
     * @param title       texto que debe contener el título
     * @param genreId     identificador del género
     * @param releaseYear año de estreno
     * @param pageable    página, tamaño y ordenación solicitados
     * @return página de series con episodios que cumplen todos los filtros
     */
    @Transactional(readOnly = true)
    public Page<SeriesResponse> searchSeries(
            String title,
            Long genreId,
            Integer releaseYear,
            Pageable pageable) {

        List<Specification<Series>> filters = new ArrayList<>();
        filters.add(SeriesSpecification.hasEpisodes());

        if (title != null && !title.isBlank()) {
            filters.add(SeriesSpecification.hasTitle(title));
        }
        if (genreId != null) {
            filters.add(SeriesSpecification.hasGenre(genreId));
        }
        if (releaseYear != null) {
            filters.add(SeriesSpecification.hasReleaseYear(releaseYear));
        }

        return toResponsePage(seriesRepository.findAll(Specification.allOf(filters), pageable));
    }

    /**
     * Obtiene el detalle de una serie visible, con sus temporadas y episodios.
     *
     * @param id identificador de la serie
     * @return la serie con sus géneros y episodios agrupados por temporada
     * @throws SeriesNotFoundException si no existe o no tiene episodios
     */
    @Transactional(readOnly = true)
    public SeriesDetailResponse getSeriesById(Long id) {

        Series series = seriesRepository.findVisibleById(id)
                .orElseThrow(() -> new SeriesNotFoundException(SERIES_NOT_FOUND));

        return SeriesMapper.toDetailResponse(series, episodeRepository.findAllBySeriesIdOrdered(id));
    }

    // ------------------------------------------------------------------
    // Vistas de administración (incluyen las series vacías)
    // ------------------------------------------------------------------

    /**
     * Obtiene una página de <em>todas</em> las series, incluidas las que aún
     * no tienen episodios, filtrando opcionalmente por título.
     *
     * @param title    texto que debe contener el título ({@code null} o en blanco = todas)
     * @param pageable página, tamaño y ordenación solicitados
     * @return página de series con sus recuentos (0 en las vacías)
     */
    @Transactional(readOnly = true)
    public Page<SeriesResponse> getAdminSeries(String title, Pageable pageable) {

        List<Specification<Series>> filters = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            filters.add(SeriesSpecification.hasTitle(title));
        }

        return toResponsePage(seriesRepository.findAll(Specification.allOf(filters), pageable));
    }

    /**
     * Obtiene el detalle de cualquier serie, aunque no tenga episodios
     * ({@code seasons} vacío).
     *
     * @param id identificador de la serie
     * @return la serie con sus géneros y episodios
     * @throws SeriesNotFoundException si no existe
     */
    @Transactional(readOnly = true)
    public SeriesDetailResponse getAdminSeriesById(Long id) {

        return toDetail(findSeries(id));
    }

    // ------------------------------------------------------------------
    // Escritura (solo administradores; la regla está en SecurityConfig)
    // ------------------------------------------------------------------

    /**
     * Crea una serie (sin episodios) asociándola a los géneros indicados.
     *
     * <p>
     * La serie nace vacía y, por tanto, oculta para los usuarios hasta que se
     * le añada el primer episodio.
     * </p>
     *
     * @param request datos de la serie y de sus géneros
     * @return la serie creada, con {@code seasons} vacío
     * @throws GenreNotFoundException si algún género no existe
     */
    @Transactional
    public SeriesDetailResponse createSeries(SeriesRequest request) {

        Series series = SeriesMapper.toEntity(request);
        series.setGenres(resolveGenres(request.genreIds()));

        // Recién creada no puede tener episodios: no hace falta consultarlos.
        return SeriesMapper.toDetailResponse(seriesRepository.save(series), List.of());
    }

    /**
     * Sustituye los datos y los géneros de una serie existente. Sus episodios
     * no cambian.
     *
     * @param id      identificador de la serie
     * @param request datos nuevos
     * @return la serie actualizada con sus episodios
     * @throws SeriesNotFoundException si la serie no existe
     * @throws GenreNotFoundException  si algún género no existe
     */
    @Transactional
    public SeriesDetailResponse updateSeries(Long id, SeriesRequest request) {

        Series series = findSeries(id);

        SeriesMapper.updateEntity(request, series);
        series.setGenres(resolveGenres(request.genreIds()));

        // Entidad gestionada: los cambios se guardan al confirmar la transacción.
        return toDetail(series);
    }

    /**
     * Elimina una serie.
     *
     * <p>
     * La base de datos borra en cascada sus episodios
     * ({@code fk_episodes_series}) y la quita de todas las listas
     * ({@code fk_favorite_series_series}); Hibernate borra sus filas de
     * {@code series_genres}. No hace falta cargar los episodios ni los
     * favoritos para borrarlos uno a uno.
     * </p>
     *
     * @param id identificador de la serie
     * @throws SeriesNotFoundException si no existe
     */
    @Transactional
    public void deleteSeries(Long id) {

        seriesRepository.delete(findSeries(id));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Convierte una página de series en DTOs con sus recuentos, usando una
     * única consulta de recuentos para toda la página (ninguna si está vacía).
     */
    private Page<SeriesResponse> toResponsePage(Page<Series> page) {

        Map<Long, SeriesEpisodeStats> stats =
                SeriesEpisodeCounts.load(episodeRepository, SeriesEpisodeCounts.ids(page.getContent()));

        return page.map(series -> SeriesEpisodeCounts.toResponse(series, stats));
    }

    private SeriesDetailResponse toDetail(Series series) {

        return SeriesMapper.toDetailResponse(series, episodeRepository.findAllBySeriesIdOrdered(series.getId()));
    }

    /** Busca una serie (con sus géneros) sin mirar si tiene episodios. */
    private Series findSeries(Long id) {

        return seriesRepository.findById(id)
                .orElseThrow(() -> new SeriesNotFoundException(SERIES_NOT_FOUND));
    }

    /**
     * Carga los géneros indicados con una sola consulta. Mismo criterio que
     * en películas: si falta alguno se indica el de menor id, para que el
     * mensaje sea determinista.
     *
     * @param genreIds identificadores solicitados
     * @return géneros encontrados
     * @throws GenreNotFoundException si alguno no existe
     */
    private Set<Genre> resolveGenres(Set<Long> genreIds) {

        List<Genre> found = genreRepository.findAllById(genreIds);

        if (found.size() != genreIds.size()) {

            Set<Long> foundIds = found.stream()
                    .map(Genre::getId)
                    .collect(Collectors.toSet());

            Long missing = genreIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .sorted()
                    .findFirst()
                    .orElseThrow();

            throw new GenreNotFoundException("Género no encontrado: " + missing);
        }

        return new HashSet<>(found);
    }
}
