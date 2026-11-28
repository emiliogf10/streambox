package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Series;

/**
 * Casos de prueba de la consulta de «series similares»
 * ({@link SeriesRepository#findSimilarSeriesIds(Long, int)} y
 * {@link SeriesRepository#findAllWithGenresByIdIn(java.util.Collection)}),
 * escritos <strong>una sola vez</strong> y ejecutados en los dos motores.
 *
 * <p>
 * Gemela de {@link SimilarMoviesQueryContract} (mismo criterio de orden), con
 * lo propio de las series: <strong>solo son candidatas las que tienen al menos
 * un episodio</strong>. Por eso {@link #series(String, int, Genre...)} crea
 * siempre una serie <em>visible</em> (con un episodio) y las ocultas se crean
 * aparte con {@link #emptySeries(String, int, Genre...)}: así ningún caso de
 * orden puede pasar «por casualidad» por culpa del filtro de episodios.
 * </p>
 *
 * <p>
 * La implementan {@link SimilarSeriesRepositoryIntegrationTest} (H2) y
 * {@code PostgresSimilarSeriesIntegrationTest} (PostgreSQL real con
 * Testcontainers). Cada caso fija los datos para que <strong>solo</strong> el
 * criterio que prueba decida el orden; las fechas de creación se fijan con SQL
 * porque {@code @CreationTimestamp} las asigna al insertar.
 * </p>
 */
public interface SimilarSeriesQueryContract {

    /** Instante base para fijar {@code created_at} a mano. */
    Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Lo que aporta cada motor
    // ------------------------------------------------------------------

    /** @return el repositorio bajo prueba */
    SeriesRepository seriesRepo();

    /** Guarda un género. */
    Genre genre(String name);

    /** Guarda una serie del año dado con esos géneros y <strong>sin episodios</strong>. */
    Series emptySeries(String title, int year, Genre... genres);

    /** Añade a la serie el episodio {@code season}×{@code number}. */
    void addEpisode(Series series, int season, int number);

    /** Sobrescribe {@code created_at} con SQL directo (la entidad no lo deja cambiar). */
    void setCreatedAt(Series series, Instant createdAt);

    /** Número de sentencias SQL preparadas mientras se ejecuta la operación. */
    long statementsFor(Runnable operation);

    /** Plantilla de transacción, para medir varias consultas como lo haría un servicio. */
    TransactionTemplate tx();

    /**
     * Guarda una serie <strong>visible</strong>: con géneros y un episodio.
     *
     * @return la serie guardada
     */
    default Series series(String title, int year, Genre... genres) {
        Series series = emptySeries(title, year, genres);
        addEpisode(series, 1, 1);
        return series;
    }

    // ------------------------------------------------------------------
    // Orden
    // ------------------------------------------------------------------

    /**
     * Primer criterio: cuantos más géneros en común, antes. Se excluyen la
     * propia serie y las que no comparten ningún género. Las candidatas se
     * crean de menos a más parecida (y la menos parecida tiene la distancia en
     * años más corta) para que ni el orden de inserción ni los criterios
     * siguientes puedan dar el resultado por casualidad.
     */
    @Test
    default void ordenaPorNumeroDeGenerosEnComunYExcluyeLaPropiaSerie() {
        Genre action = genre("Accion");
        Genre drama = genre("Drama");
        Genre scifi = genre("Ciencia ficcion");
        Genre comedy = genre("Comedia");
        Series origin = series("Origen", 2000, action, drama, scifi);
        Series one = series("Uno en comun", 2000, action);
        Series two = series("Dos en comun", 1980, action, drama, comedy);
        Series three = series("Tres en comun", 1950, action, drama, scifi);
        series("Sin nada en comun", 2000, comedy);

        List<Long> similar = seriesRepo().findSimilarSeriesIds(origin.getId(), 10);

        assertEquals(List.of(three.getId(), two.getId(), one.getId()), similar);
    }

    /**
     * Segundo criterio: con los mismos géneros en común, la de año más cercano
     * va antes, tanto si es anterior como posterior ({@code ABS}). La de mayor
     * {@code created_at} es la más lejana, para que el tercer criterio no pueda
     * explicar el resultado.
     */
    @Test
    default void conLosMismosGenerosDesempataPorDistanciaEnAnios() {
        Genre drama = genre("Drama");
        Series origin = series("Origen", 2000, drama);
        Series far = series("Diez antes", 1990, drama);
        Series after = series("Tres despues", 2003, drama);
        Series sameYear = series("Mismo anio", 2000, drama);
        Series before = series("Uno antes", 1999, drama);
        setCreatedAt(far, BASE.plusSeconds(40));
        setCreatedAt(after, BASE.plusSeconds(30));
        setCreatedAt(sameYear, BASE.plusSeconds(10));
        setCreatedAt(before, BASE.plusSeconds(20));

        assertEquals(List.of(sameYear.getId(), before.getId(), after.getId(), far.getId()),
                seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    /**
     * El número de géneros manda sobre el año: una serie con dos géneros en
     * común y 50 años de diferencia va antes que una del mismo año con uno.
     */
    @Test
    default void elNumeroDeGenerosPesaMasQueLaDistanciaEnAnios() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Series origin = series("Origen", 2000, drama, comedy);
        Series sameYearOneGenre = series("Mismo anio, un genero", 2000, drama);
        Series farTwoGenres = series("Lejana, dos generos", 1950, drama, comedy);

        assertEquals(List.of(farTwoGenres.getId(), sameYearOneGenre.getId()),
                seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    /**
     * Tercer criterio: mismos géneros y misma distancia (dos 2 años antes y una
     * 2 después) → la creada más recientemente primero. Las ids crecen en
     * sentido contrario al resultado esperado para que el cuarto criterio no
     * pueda explicarlo.
     */
    @Test
    default void conLaMismaDistanciaDesempataPorFechaDeCreacionDescendente() {
        Genre drama = genre("Drama");
        Series origin = series("Origen", 2000, drama);
        Series oldest = series("Creada primero", 1998, drama);
        Series middle = series("Creada en medio", 2002, drama);
        Series newest = series("Creada la ultima", 1998, drama);
        setCreatedAt(oldest, BASE.plusSeconds(1));
        setCreatedAt(middle, BASE.plusSeconds(2));
        setCreatedAt(newest, BASE.plusSeconds(3));

        assertEquals(List.of(newest.getId(), middle.getId(), oldest.getId()),
                seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    /**
     * Cuarto criterio: empate en todo lo demás (mismos géneros, mismo año y el
     * mismo {@code created_at}) → por {@code id} ascendente. Garantiza que el
     * orden no cambie entre dos llamadas.
     */
    @Test
    default void conTodoIgualDesempataPorIdAscendente() {
        Genre drama = genre("Drama");
        Series origin = series("Origen", 2000, drama);
        List<Series> twins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Series twin = series("Gemela " + i, 2001, drama);
            setCreatedAt(twin, BASE);
            twins.add(twin);
        }

        List<Long> similar = seriesRepo().findSimilarSeriesIds(origin.getId(), 10);

        assertEquals(twins.stream().map(Series::getId).sorted().toList(), similar);
        assertEquals(similar, seriesRepo().findSimilarSeriesIds(origin.getId(), 10),
                "misma respuesta en cada llamada");
    }

    /**
     * Oráculo independiente: un catálogo pseudoaleatorio (semilla fija) con
     * empates múltiples a la vez en cada nivel, algunas series
     * <strong>sin episodios</strong> mezcladas, y el orden esperado calculado
     * en Java con el criterio documentado (géneros en común ↓, distancia en
     * años ↑, {@code created_at} ↓, id ↑; solo series con episodios). Debe
     * coincidir en H2 y en PostgreSQL, también al cortar con un límite pequeño.
     */
    @Test
    default void conEmpatesMultiplesElOrdenCoincideConElCriterioCalculadoEnJava() {
        List<Genre> genres = List.of(genre("Accion"), genre("Drama"), genre("Ciencia ficcion"), genre("Comedia"));
        Series origin = series("Origen", 2000, genres.get(0), genres.get(1), genres.get(2));
        Set<Long> originGenres = Set.of(genres.get(0).getId(), genres.get(1).getId(), genres.get(2).getId());
        int[] years = { 1998, 1999, 2000, 2001, 2002 };
        Random random = new Random(20261128L);

        record Candidate(long id, int shared, int distance, Instant createdAt) {
        }
        List<Candidate> expected = new ArrayList<>();
        int hiddenWithSharedGenres = 0;
        for (int i = 0; i < 30; i++) {
            List<Genre> chosen = new ArrayList<>();
            for (Genre genre : genres) {
                if (random.nextBoolean()) {
                    chosen.add(genre);
                }
            }
            int year = years[random.nextInt(years.length)];
            Instant createdAt = BASE.plusSeconds(random.nextInt(3));
            boolean visible = random.nextInt(4) != 0;
            Series candidate = visible
                    ? series("Candidata " + i, year, chosen.toArray(Genre[]::new))
                    : emptySeries("Oculta " + i, year, chosen.toArray(Genre[]::new));
            setCreatedAt(candidate, createdAt);
            int shared = (int) chosen.stream().filter(genre -> originGenres.contains(genre.getId())).count();
            if (shared > 0 && visible) {
                expected.add(new Candidate(candidate.getId(), shared, Math.abs(year - 2000), createdAt));
            } else if (shared > 0) {
                hiddenWithSharedGenres++;
            }
        }
        List<Long> expectedIds = expected.stream()
                .sorted(Comparator.comparingInt(Candidate::shared).reversed()
                        .thenComparingInt(Candidate::distance)
                        .thenComparing(Candidate::createdAt, Comparator.reverseOrder())
                        .thenComparingLong(Candidate::id))
                .map(Candidate::id)
                .toList();
        assertTrue(expectedIds.size() > 12, "la semilla debe dar bastantes candidatas para que haya empates");
        assertTrue(hiddenWithSharedGenres > 0, "la semilla debe incluir series ocultas que compartan géneros");

        assertEquals(expectedIds, seriesRepo().findSimilarSeriesIds(origin.getId(), 1000));
        assertEquals(expectedIds.subList(0, 5), seriesRepo().findSimilarSeriesIds(origin.getId(), 5));
    }

    /**
     * {@code created_at} se compara con su precisión completa (microsegundos)
     * en los dos motores: dos series creadas en el mismo milisegundo pero en
     * distinto microsegundo se ordenan por fecha, no caen al desempate por id.
     */
    @Test
    default void elDesempatePorFechaUsaLosMicrosegundosEnLosDosMotores() {
        Genre drama = genre("Drama");
        Series origin = series("Origen", 2000, drama);
        Series earlier = series("Un microsegundo antes", 2001, drama);
        Series later = series("Un microsegundo despues", 2001, drama);
        setCreatedAt(earlier, BASE.plusNanos(1_000));
        setCreatedAt(later, BASE.plusNanos(2_000));

        assertEquals(List.of(later.getId(), earlier.getId()),
                seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    // ------------------------------------------------------------------
    // Visibilidad (series sin episodios)
    // ------------------------------------------------------------------

    /**
     * Una serie sin episodios no es candidata aunque comparta
     * <strong>todos</strong> los géneros, sea del mismo año y la más reciente
     * (es decir, aunque fuera a quedar la primera). Para los usuarios no
     * existe.
     */
    @Test
    default void excluyeLasSeriesSinEpisodiosAunqueCompartanTodosLosGeneros() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Series origin = series("Origen", 2000, drama, comedy);
        Series visible = series("Visible, un genero", 1950, drama);
        Series hidden = emptySeries("Oculta, todos los generos", 2000, drama, comedy);
        setCreatedAt(visible, BASE);
        setCreatedAt(hidden, BASE.plusSeconds(60));

        assertEquals(List.of(visible.getId()), seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    /**
     * En cuanto una serie oculta recibe su primer episodio pasa a ser
     * candidata, en el puesto que le corresponde por el criterio (aquí, la
     * primera). Y con varios episodios sigue saliendo una sola vez: el
     * {@code EXISTS} no multiplica filas ni altera el {@code COUNT}.
     */
    @Test
    default void unaSerieConUnEpisodioPasaASerCandidata() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Series origin = series("Origen", 2000, drama, comedy);
        Series other = series("Otra, un genero", 2000, drama);
        Series hidden = emptySeries("Antes oculta", 2000, drama, comedy);
        assertEquals(List.of(other.getId()), seriesRepo().findSimilarSeriesIds(origin.getId(), 10));

        addEpisode(hidden, 1, 1);
        assertEquals(List.of(hidden.getId(), other.getId()), seriesRepo().findSimilarSeriesIds(origin.getId(), 10));

        addEpisode(hidden, 1, 2);
        addEpisode(hidden, 2, 1);
        addEpisode(other, 1, 2);
        assertEquals(List.of(hidden.getId(), other.getId()), seriesRepo().findSimilarSeriesIds(origin.getId(), 10),
                "varios episodios no duplican la serie ni cambian el número de géneros en común");
    }

    /**
     * Comportamiento documentado: la consulta <strong>no</strong> mira si la
     * serie de origen tiene episodios (eso lo comprueba el servicio, que
     * responde 404). Con géneros, devuelve sus candidatas visibles igual.
     */
    @Test
    default void unOrigenSinEpisodiosDevuelveIgualSusCandidatasVisibles() {
        Genre drama = genre("Drama");
        Series origin = emptySeries("Origen oculto", 2000, drama);
        Series visible = series("Visible", 2001, drama);
        emptySeries("Otra oculta", 2000, drama);

        assertEquals(List.of(visible.getId()), seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    // ------------------------------------------------------------------
    // Vacíos y límite
    // ------------------------------------------------------------------

    /**
     * Sin candidatas el resultado es una lista vacía, no un error: serie sin
     * géneros en común con ninguna, serie sin géneros, catálogo con una sola
     * serie y series de origen inexistentes (también ids imposibles).
     */
    @Test
    default void sinCandidatasDevuelveVacio() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Series lonely = series("Solitaria", 2000, drama);
        series("Otra", 2000, comedy);
        Series withoutGenres = series("Sin generos", 2000);

        assertEquals(List.of(), seriesRepo().findSimilarSeriesIds(lonely.getId(), 10));
        assertEquals(List.of(), seriesRepo().findSimilarSeriesIds(withoutGenres.getId(), 10));
        assertEquals(List.of(), seriesRepo().findSimilarSeriesIds(Long.MAX_VALUE, 10));
        assertEquals(List.of(), seriesRepo().findSimilarSeriesIds(0L, 10));
        assertEquals(List.of(), seriesRepo().findSimilarSeriesIds(-1L, 10));
    }

    /**
     * Si las únicas que comparten géneros están vacías, el resultado es vacío
     * (no se «rellena» con ocultas).
     */
    @Test
    default void siSoloCompartenGenerosSeriesOcultasDevuelveVacio() {
        Genre drama = genre("Drama");
        Series origin = series("Origen", 2000, drama);
        emptySeries("Oculta 1", 2000, drama);
        emptySeries("Oculta 2", 2001, drama);

        assertEquals(List.of(), seriesRepo().findSimilarSeriesIds(origin.getId(), 10));
    }

    /**
     * El límite se aplica en la base de datos <strong>después</strong> de
     * ordenar y de filtrar las ocultas: devuelve el principio exacto del orden
     * completo (una oculta intercalada no ocupa un hueco), y con un límite
     * mayor que el número de candidatas devuelve todas.
     */
    @Test
    default void elLimiteDevuelveLasPrimerasDelOrdenCompleto() {
        Genre drama = genre("Drama");
        Series origin = series("Origen", 2000, drama);
        for (int year = 2001; year <= 2007; year++) {
            series("Candidata " + year, year, drama);
        }
        emptySeries("Oculta del mismo anio", 2000, drama);

        List<Long> all = seriesRepo().findSimilarSeriesIds(origin.getId(), 24);
        List<Long> firstThree = seriesRepo().findSimilarSeriesIds(origin.getId(), 3);

        assertEquals(7, all.size());
        assertEquals(all.subList(0, 3), firstThree);
        assertEquals(all.subList(0, 1), seriesRepo().findSimilarSeriesIds(origin.getId(), 1));
    }

    // ------------------------------------------------------------------
    // Carga con géneros
    // ------------------------------------------------------------------

    /**
     * Carga sin N+1: ids ordenados + series con sus géneros son exactamente
     * <strong>dos</strong> sentencias, sean cuantas sean las series, sus
     * géneros y sus episodios. Cada serie aparece una sola vez aunque el
     * {@code JOIN FETCH} repita su fila por cada género, y sus géneros llegan
     * completos e inicializados.
     */
    @Test
    default void idsMasCargaConGenerosSonDosSentenciasSinDuplicados() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Genre scifi = genre("Ciencia ficcion");
        Series origin = series("Origen", 2000, drama, comedy);
        for (int i = 0; i < 12; i++) {
            Series similar = i % 2 == 0
                    ? series("Similar " + i, 1990 + i, drama, comedy, scifi)
                    : series("Similar " + i, 1990 + i, drama, comedy);
            addEpisode(similar, 1, 2);
        }

        List<List<String>> genreNames = new ArrayList<>();
        long statements = statementsFor(() -> tx().executeWithoutResult(status -> {
            List<Long> ids = seriesRepo().findSimilarSeriesIds(origin.getId(), 24);
            List<Series> loaded = seriesRepo().findAllWithGenresByIdIn(ids);
            Map<Long, Series> byId = loaded.stream().collect(Collectors.toMap(Series::getId, Function.identity()));
            assertEquals(12, loaded.size(), "sin filas duplicadas por el join con los géneros");
            for (Long id : ids) {
                Series series = byId.get(id);
                assertTrue(Hibernate.isInitialized(series.getGenres()), "géneros cargados en la misma consulta");
                genreNames.add(series.getGenres().stream().map(Genre::getName).sorted().toList());
            }
        }));

        assertEquals(2, statements, "una consulta de ids y otra de series con géneros");
        assertEquals(12, genreNames.size());
        assertTrue(genreNames.stream().allMatch(names -> names.containsAll(List.of("Comedia", "Drama"))));
        assertEquals(6, genreNames.stream().filter(names -> names.size() == 3).count());
    }
}
