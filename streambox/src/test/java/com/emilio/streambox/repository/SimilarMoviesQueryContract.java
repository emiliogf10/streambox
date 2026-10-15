package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;

/**
 * Casos de prueba de la consulta de «películas similares»
 * ({@link MovieRepository#findSimilarMovieIds(Long, int)} y
 * {@link MovieRepository#findAllWithGenresByIdIn(java.util.Collection)}),
 * escritos <strong>una sola vez</strong> y ejecutados en los dos motores.
 *
 * <p>
 * Es una <em>interfaz de test</em> de JUnit 5: los métodos {@code default}
 * anotados con {@code @Test} se ejecutan en cada clase que la implementa. Así
 * {@link SimilarMoviesRepositoryIntegrationTest} (H2) y
 * {@code PostgresSimilarMoviesIntegrationTest} (PostgreSQL real con
 * Testcontainers) prueban exactamente lo mismo sin copiar el código. No se usa
 * una clase base abstracta porque los tests de PostgreSQL ya heredan de
 * {@code PostgresIntegrationTestSupport} y Java no admite herencia múltiple.
 * </p>
 *
 * <p>
 * Cada caso fija los datos para que <strong>solo</strong> el criterio que
 * prueba decida el orden: si se prueba el desempate por año, todas las
 * candidatas comparten el mismo número de géneros; si se prueba
 * {@code created_at}, también la misma distancia en años, etc. Las fechas de
 * creación se fijan con SQL porque {@code @CreationTimestamp} las asigna al
 * insertar y dos inserciones seguidas pueden recibir el mismo instante.
 * </p>
 */
public interface SimilarMoviesQueryContract {

    /** Instante base para fijar {@code created_at} a mano. */
    Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Lo que aporta cada motor
    // ------------------------------------------------------------------

    /** @return el repositorio bajo prueba */
    MovieRepository movies();

    /** Guarda un género. */
    Genre genre(String name);

    /** Guarda una película del año dado con esos géneros. */
    Movie movie(String title, int year, Genre... genres);

    /** Sobrescribe {@code created_at} con SQL directo (la entidad no lo deja cambiar). */
    void setCreatedAt(Movie movie, Instant createdAt);

    /** Número de sentencias SQL preparadas mientras se ejecuta la operación. */
    long statementsFor(Runnable operation);

    /** Plantilla de transacción, para medir varias consultas como lo haría un servicio. */
    TransactionTemplate tx();

    // ------------------------------------------------------------------
    // Casos
    // ------------------------------------------------------------------

    /**
     * Primer criterio: cuantos más géneros en común, antes. Se excluyen la
     * propia película y las que no comparten ningún género. Las candidatas se
     * crean de menos a más parecida (y la menos parecida tiene la distancia en
     * años más corta) para que ni el orden de inserción ni los criterios
     * siguientes puedan dar el resultado por casualidad.
     */
    @Test
    default void ordenaPorNumeroDeGenerosEnComunYExcluyeLaPropiaPelicula() {
        Genre action = genre("Accion");
        Genre drama = genre("Drama");
        Genre scifi = genre("Ciencia ficcion");
        Genre comedy = genre("Comedia");
        Movie origin = movie("Origen", 2000, action, drama, scifi);
        Movie one = movie("Uno en comun", 2000, action);
        Movie two = movie("Dos en comun", 1980, action, drama, comedy);
        Movie three = movie("Tres en comun", 1950, action, drama, scifi);
        movie("Sin nada en comun", 2000, comedy);

        List<Long> similar = movies().findSimilarMovieIds(origin.getId(), 10);

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
        Movie origin = movie("Origen", 2000, drama);
        Movie far = movie("Diez antes", 1990, drama);
        Movie after = movie("Tres despues", 2003, drama);
        Movie sameYear = movie("Mismo anio", 2000, drama);
        Movie before = movie("Uno antes", 1999, drama);
        setCreatedAt(far, BASE.plusSeconds(40));
        setCreatedAt(after, BASE.plusSeconds(30));
        setCreatedAt(sameYear, BASE.plusSeconds(10));
        setCreatedAt(before, BASE.plusSeconds(20));

        List<Long> similar = movies().findSimilarMovieIds(origin.getId(), 10);

        assertEquals(List.of(sameYear.getId(), before.getId(), after.getId(), far.getId()), similar);
    }

    /**
     * El número de géneros manda sobre el año: una película con dos géneros en
     * común y 50 años de diferencia va antes que una del mismo año con uno.
     */
    @Test
    default void elNumeroDeGenerosPesaMasQueLaDistanciaEnAnios() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Movie origin = movie("Origen", 2000, drama, comedy);
        Movie sameYearOneGenre = movie("Mismo anio, un genero", 2000, drama);
        Movie farTwoGenres = movie("Lejana, dos generos", 1950, drama, comedy);

        assertEquals(List.of(farTwoGenres.getId(), sameYearOneGenre.getId()),
                movies().findSimilarMovieIds(origin.getId(), 10));
    }

    /**
     * Tercer criterio: mismos géneros y misma distancia (una 2 años antes y otra
     * 2 después, más una del mismo año pasado) → la creada más recientemente
     * primero. Las ids crecen en sentido contrario al resultado esperado para
     * que el cuarto criterio no pueda explicarlo.
     */
    @Test
    default void conLaMismaDistanciaDesempataPorFechaDeCreacionDescendente() {
        Genre drama = genre("Drama");
        Movie origin = movie("Origen", 2000, drama);
        Movie oldest = movie("Creada primero", 1998, drama);
        Movie middle = movie("Creada en medio", 2002, drama);
        Movie newest = movie("Creada la ultima", 1998, drama);
        setCreatedAt(oldest, BASE.plusSeconds(1));
        setCreatedAt(middle, BASE.plusSeconds(2));
        setCreatedAt(newest, BASE.plusSeconds(3));

        assertEquals(List.of(newest.getId(), middle.getId(), oldest.getId()),
                movies().findSimilarMovieIds(origin.getId(), 10));
    }

    /**
     * Cuarto criterio: empate en todo lo demás (mismos géneros, mismo año y el
     * mismo {@code created_at}) → por {@code id} ascendente. Garantiza que el
     * orden no cambie entre dos llamadas.
     */
    @Test
    default void conTodoIgualDesempataPorIdAscendente() {
        Genre drama = genre("Drama");
        Movie origin = movie("Origen", 2000, drama);
        List<Movie> twins = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Movie twin = movie("Gemela " + i, 2001, drama);
            setCreatedAt(twin, BASE);
            twins.add(twin);
        }

        List<Long> similar = movies().findSimilarMovieIds(origin.getId(), 10);

        assertEquals(twins.stream().map(Movie::getId).sorted().toList(), similar);
        assertEquals(similar, movies().findSimilarMovieIds(origin.getId(), 10), "misma respuesta en cada llamada");
    }

    /**
     * Sin candidatas el resultado es una lista vacía, no un error: película sin
     * géneros en común con ninguna, película sin géneros y película inexistente
     * (el 404 de esta última lo decide el servicio antes de llamar aquí).
     */
    @Test
    default void sinGenerosEnComunDevuelveVacio() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Movie lonely = movie("Solitaria", 2000, drama);
        movie("Otra", 2000, comedy);
        Movie withoutGenres = movie("Sin generos", 2000);

        assertEquals(List.of(), movies().findSimilarMovieIds(lonely.getId(), 10));
        assertEquals(List.of(), movies().findSimilarMovieIds(withoutGenres.getId(), 10));
        assertEquals(List.of(), movies().findSimilarMovieIds(Long.MAX_VALUE, 10));
    }

    /**
     * El límite se aplica en la base de datos <strong>después</strong> de
     * ordenar: devuelve el principio exacto del orden completo, y con un límite
     * mayor que el número de candidatas devuelve todas.
     */
    @Test
    default void elLimiteDevuelveLasPrimerasDelOrdenCompleto() {
        Genre drama = genre("Drama");
        Movie origin = movie("Origen", 2000, drama);
        for (int year = 2001; year <= 2007; year++) {
            movie("Candidata " + year, year, drama);
        }

        List<Long> all = movies().findSimilarMovieIds(origin.getId(), 24);
        List<Long> firstThree = movies().findSimilarMovieIds(origin.getId(), 3);

        assertEquals(7, all.size());
        assertEquals(all.subList(0, 3), firstThree);
        assertEquals(all.subList(0, 1), movies().findSimilarMovieIds(origin.getId(), 1));
    }

    /**
     * Carga sin N+1: ids ordenados + películas con sus géneros son exactamente
     * <strong>dos</strong> sentencias, sean cuantas sean las películas y sus
     * géneros (aquí 12 películas con 2 o 3 géneros cada una). Cada película
     * aparece una sola vez aunque el {@code JOIN FETCH} repita su fila por cada
     * género, y sus géneros llegan completos e inicializados.
     */
    @Test
    default void idsMasCargaConGenerosSonDosSentenciasSinDuplicados() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Genre scifi = genre("Ciencia ficcion");
        Movie origin = movie("Origen", 2000, drama, comedy);
        for (int i = 0; i < 12; i++) {
            if (i % 2 == 0) {
                movie("Similar " + i, 1990 + i, drama, comedy, scifi);
            } else {
                movie("Similar " + i, 1990 + i, drama, comedy);
            }
        }

        List<List<String>> genreNames = new ArrayList<>();
        long statements = statementsFor(() -> tx().executeWithoutResult(status -> {
            List<Long> ids = movies().findSimilarMovieIds(origin.getId(), 24);
            List<Movie> loaded = movies().findAllWithGenresByIdIn(ids);
            Map<Long, Movie> byId = loaded.stream().collect(Collectors.toMap(Movie::getId, Function.identity()));
            assertEquals(12, loaded.size(), "sin filas duplicadas por el join con los géneros");
            for (Long id : ids) {
                Movie movie = byId.get(id);
                assertTrue(Hibernate.isInitialized(movie.getGenres()), "géneros cargados en la misma consulta");
                genreNames.add(movie.getGenres().stream().map(Genre::getName).sorted().toList());
            }
        }));

        assertEquals(2, statements, "una consulta de ids y otra de películas con géneros");
        assertEquals(12, genreNames.size());
        assertTrue(genreNames.stream().allMatch(names -> names.containsAll(List.of("Comedia", "Drama"))));
        assertEquals(6, genreNames.stream().filter(names -> names.size() == 3).count());
    }

    // ------------------------------------------------------------------
    // Casos añadidos por QA (revisión independiente)
    // ------------------------------------------------------------------

    /**
     * Oráculo independiente: un catálogo pseudoaleatorio (semilla fija, así que
     * siempre el mismo) con <strong>empates múltiples a la vez</strong> en cada
     * nivel —pocos años y pocas fechas de creación posibles, géneros que se
     * solapan de muchas formas— y el orden esperado calculado en Java con el
     * criterio documentado (géneros en común ↓, distancia en años ↑,
     * {@code created_at} ↓, id ↑). Los casos de arriba prueban cada criterio
     * aislado; este, que combinados dan un orden total idéntico en H2 y en
     * PostgreSQL, también al cortar con un límite pequeño.
     */
    @Test
    default void conEmpatesMultiplesElOrdenCoincideConElCriterioCalculadoEnJava() {
        List<Genre> genres = List.of(genre("Accion"), genre("Drama"), genre("Ciencia ficcion"), genre("Comedia"));
        Movie origin = movie("Origen", 2000, genres.get(0), genres.get(1), genres.get(2));
        Set<Long> originGenres = Set.of(genres.get(0).getId(), genres.get(1).getId(), genres.get(2).getId());
        int[] years = { 1998, 1999, 2000, 2001, 2002 };
        java.util.Random random = new java.util.Random(20261015L);

        record Candidate(long id, int shared, int distance, Instant createdAt) {
        }
        List<Candidate> expected = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<Genre> chosen = new ArrayList<>();
            for (Genre genre : genres) {
                if (random.nextBoolean()) {
                    chosen.add(genre);
                }
            }
            int year = years[random.nextInt(years.length)];
            Instant createdAt = BASE.plusSeconds(random.nextInt(3));
            Movie candidate = movie("Candidata " + i, year, chosen.toArray(Genre[]::new));
            setCreatedAt(candidate, createdAt);
            int shared = (int) chosen.stream().filter(genre -> originGenres.contains(genre.getId())).count();
            if (shared > 0) {
                expected.add(new Candidate(candidate.getId(), shared, Math.abs(year - 2000), createdAt));
            }
        }
        List<Long> expectedIds = expected.stream()
                .sorted(java.util.Comparator.comparingInt(Candidate::shared).reversed()
                        .thenComparingInt(Candidate::distance)
                        .thenComparing(Candidate::createdAt, java.util.Comparator.reverseOrder())
                        .thenComparingLong(Candidate::id))
                .map(Candidate::id)
                .toList();
        assertTrue(expectedIds.size() > 15, "la semilla debe dar bastantes candidatas para que haya empates");

        assertEquals(expectedIds, movies().findSimilarMovieIds(origin.getId(), 1000));
        assertEquals(expectedIds.subList(0, 5), movies().findSimilarMovieIds(origin.getId(), 5));
    }

    /**
     * {@code created_at} se compara con su precisión completa (microsegundos)
     * en los dos motores: dos películas creadas en el mismo milisegundo pero en
     * distinto microsegundo se ordenan por fecha, no caen al desempate por id.
     * Las ids crecen al revés que el resultado esperado para que el id no pueda
     * explicarlo. Protege que H2 y PostgreSQL no difieran si un motor truncase
     * la marca de tiempo.
     */
    @Test
    default void elDesempatePorFechaUsaLosMicrosegundosEnLosDosMotores() {
        Genre drama = genre("Drama");
        Movie origin = movie("Origen", 2000, drama);
        Movie earlier = movie("Un microsegundo antes", 2001, drama);
        Movie later = movie("Un microsegundo despues", 2001, drama);
        setCreatedAt(earlier, BASE.plusNanos(1_000));
        setCreatedAt(later, BASE.plusNanos(2_000));

        assertEquals(List.of(later.getId(), earlier.getId()), movies().findSimilarMovieIds(origin.getId(), 10));
    }

    /**
     * La película de origen tiene TODOS los géneros del catálogo: cualquier
     * película con géneros es candidata y se ordenan por cuántos comparten; la
     * que no tiene géneros sigue sin aparecer.
     */
    @Test
    default void unaPeliculaConTodosLosGenerosOrdenaLasDemasPorCuantosComparten() {
        Genre action = genre("Accion");
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Movie origin = movie("Todos los generos", 2000, action, drama, comedy);
        Movie one = movie("Uno", 2000, comedy);
        Movie three = movie("Tres", 1900, action, drama, comedy);
        Movie two = movie("Dos", 1950, action, comedy);
        movie("Sin generos", 2000);

        assertEquals(List.of(three.getId(), two.getId(), one.getId()),
                movies().findSimilarMovieIds(origin.getId(), 24));
    }

    /**
     * Catálogo de una sola película (con géneros): no hay con quién compararla
     * y el resultado es vacío, no ella misma.
     */
    @Test
    default void conUnaSolaPeliculaEnElCatalogoDevuelveVacio() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Movie only = movie("Unica", 2000, drama, comedy);

        assertEquals(List.of(), movies().findSimilarMovieIds(only.getId(), 24));
    }

    /**
     * Ids que la ruta acepta aunque no puedan existir (negativo, cero, el
     * máximo de {@code long}): la consulta responde vacío sin error (el 404 lo
     * pone el servicio antes).
     */
    @Test
    default void idsImposiblesDevuelvenVacioSinError() {
        Genre drama = genre("Drama");
        movie("Una", 2000, drama);
        movie("Otra", 2000, drama);

        assertEquals(List.of(), movies().findSimilarMovieIds(-1L, 24));
        assertEquals(List.of(), movies().findSimilarMovieIds(0L, 24));
        assertEquals(List.of(), movies().findSimilarMovieIds(Long.MIN_VALUE, 24));
    }

    /**
     * Fuera de una transacción (como lo vería un servicio tras cerrarla, con
     * {@code open-in-view=false}) los géneros siguen accesibles: no hay
     * {@code LazyInitializationException}. Los ids inexistentes se ignoran y
     * una lista vacía no rompe el {@code IN}.
     */
    @Test
    default void laCargaPorIdsTraeLosGenerosEIgnoraIdsInexistentes() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Movie movie = movie("Con dos generos", 2000, drama, comedy);

        List<Movie> loaded = movies().findAllWithGenresByIdIn(List.of(movie.getId(), Long.MAX_VALUE));

        assertEquals(1, loaded.size());
        assertEquals(Set.of("Comedia", "Drama"),
                loaded.get(0).getGenres().stream().map(Genre::getName).collect(Collectors.toSet()));
        assertEquals(List.of(), movies().findAllWithGenresByIdIn(List.of()));
    }
}
