import { useId, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Film, SearchX } from 'lucide-react';
import { Button } from '../components/Button';
import { buttonClasses } from '../components/buttonStyles';
import { CatalogSkeleton, GridSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { HeroBanner } from '../components/HeroBanner';
import { LoadMoreFooter } from '../components/LoadMoreFooter';
import { MovieCard } from '../components/MovieCard';
import { MovieDetailsModal } from '../components/MovieDetailsModal';
import { MovieFilterBar } from '../components/MovieFilterBar';
import { MovieRow } from '../components/MovieRow';
import { POSTER_GRID_CLASS } from '../components/posterGridStyles';
import { useAuth } from '../context/AuthContext';
import { useMovieResults } from '../hooks/useCatalog';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useGenres } from '../hooks/useGenres';
import { useMovieFilters } from '../hooks/useMovieFilters';
import { buildCatalogRows } from '../lib/catalog';
import { DEFAULT_MOVIE_SORT, hasActiveFilters } from '../lib/movieFilters';
import { pluralize } from '../lib/series';
import type { Movie } from '../lib/types';
import { ADMIN_MOVIES_PATH } from './admin/adminPaths';

/** Lo que devuelve `useMovieResults`: lo comparten las dos vistas de la página. */
type MovieResults = ReturnType<typeof useMovieResults>;

/** Propiedades comunes de las dos vistas. */
interface ViewProps {
  results: MovieResults;
  onSelectMovie: (movie: Movie) => void;
}

/**
 * Página `/peliculas`: el catálogo de películas con filtros por género, año y orden.
 *
 * Tiene DOS vistas, según haya filtros en la URL (ver `lib/movieFilters.ts`):
 * - **Sin filtros** ({@link CatalogView}): la misma estructura que `/series` y
 *   que la portada: banner con la película más reciente, «Novedades», filas por
 *   género (≥3 títulos sin contar el del banner) y «Cargar más películas».
 * - **Con algún filtro** ({@link FilteredResults}): una cuadrícula con los
 *   resultados de `GET /api/movies/search`, su recuento y «Cargar más». Unas
 *   filas por género no tienen sentido si ya se ha elegido un género, ni un
 *   banner de "lo más reciente" si se ordena por título.
 *
 * La barra de filtros ({@link MovieFilterBar}) está en las dos, junto al `<h1>`
 * «Películas» (visible, como el de `/series`, y presente en todos los estados).
 * Los filtros viven en la URL (`useMovieFilters`): se comparten con el enlace y
 * «Atrás» vuelve al filtro anterior. Pulsar una tarjeta abre el diálogo de
 * detalles de siempre (`MovieDetailsModal`), en las dos vistas.
 */
export function MoviesPage() {
  useDocumentTitle('Películas');
  const { genres, status: genresStatus, loaded: genresLoaded, reload: reloadGenres } = useGenres();
  // Hasta conocer los géneros no se puede saber si el de la URL existe: mientras tanto se confía en él.
  const knownGenreIds = useMemo(() => (genresLoaded ? genres.map((genre) => genre.id) : null), [genres, genresLoaded]);
  const filterState = useMovieFilters(knownGenreIds);
  const { filters, draft, clear } = filterState;
  const results = useMovieResults(filters);
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const genreSelectRef = useRef<HTMLSelectElement>(null);

  const draftActive = draft.genre !== '' || draft.year.trim() !== '' || draft.sort !== DEFAULT_MOVIE_SORT;

  /** Quita los filtros y deja el foco en el primer control (el botón pulsado desaparece). */
  const clearFilters = () => {
    clear();
    genreSelectRef.current?.focus();
  };

  return (
    <div className="pb-12">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4 px-4 pt-6 pb-2 sm:px-6">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Películas</h1>
        <MovieFilterBar
          draft={draft}
          genres={genres}
          genresStatus={genresStatus}
          onRetryGenres={reloadGenres}
          onGenreChange={filterState.setGenre}
          onYearChange={filterState.setYear}
          onYearBlur={filterState.touchYear}
          onSortChange={filterState.setSort}
          onSubmit={filterState.applyNow}
          yearError={filterState.yearError}
          showClear={hasActiveFilters(filters) || draftActive}
          onClear={clearFilters}
          genreSelectRef={genreSelectRef}
        />
      </div>

      {results.filtered ? (
        <FilteredResults results={results} onSelectMovie={setSelectedMovie} onClearFilters={clearFilters} />
      ) : (
        <CatalogView results={results} onSelectMovie={setSelectedMovie} />
      )}

      {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
    </div>
  );
}

/**
 * Vista sin filtros: banner + filas, como `/series` (y como la portada, sin la
 * fila de series). La película del banner no se repite en las filas.
 *
 * El estado vacío sigue el mismo criterio que el de `/series`: a un **usuario**
 * le dice que aparecerán cuando se publiquen y le devuelve al inicio (no a las
 * series: el texto habla de películas y esa sección también podría estar
 * vacía); a un **administrador**, que es quien puede llenarla, le lleva al
 * panel. Mientras carga el usuario, falla cerrado (mensaje de usuario).
 */
function CatalogView({ results, onSelectMovie }: ViewProps) {
  const { isAdmin } = useAuth();
  const { movies, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } = results;
  const hero = movies[0];
  // Las filas se calculan sin la película del banner para no repetirla justo debajo.
  const rows = useMemo(() => buildCatalogRows(movies.slice(1)), [movies]);

  if (status === 'loading') return <CatalogSkeleton label="Cargando películas..." />;
  if (status === 'error') {
    return <ErrorState title="No se pudieron cargar las películas" message={errorMessage} onRetry={reload} />;
  }
  if (!hero) {
    return isAdmin ? (
      <EmptyState
        icon={<Film className="size-8" />}
        title="Todavía no hay películas"
        description="Añádelas desde el panel de administración y aparecerán aquí al momento."
        action={
          <Link to={ADMIN_MOVIES_PATH} className={buttonClasses('light')}>
            Gestionar películas
          </Link>
        }
      />
    ) : (
      <EmptyState
        icon={<Film className="size-8" />}
        title="Todavía no hay películas"
        description="Cuando se publiquen películas aparecerán aquí."
        action={
          <Link to="/" className={buttonClasses('light')}>
            Ir al inicio
          </Link>
        }
      />
    );
  }

  return (
    <>
      <HeroBanner movie={hero} onDetails={onSelectMovie} />
      {rows.map((row) => (
        <MovieRow key={row.id} title={row.title} movies={row.items} onSelectMovie={onSelectMovie} />
      ))}
      <LoadMoreFooter
        shown={movies.length}
        total={total}
        singular="película"
        plural="películas"
        hasMore={hasMore}
        loadingMore={loadingMore}
        loadMoreFailed={loadMoreFailed}
        onLoadMore={() => void loadMore()}
      />
    </>
  );
}

/** Propiedades de {@link FilteredResults}. */
interface FilteredResultsProps extends ViewProps {
  onClearFilters: () => void;
}

/**
 * Vista con filtros: cuadrícula de resultados.
 *
 * La sección (con su `<h2>` «Resultados») existe en TODOS los estados, y con
 * ella la región `role="status"` del recuento: los lectores de pantalla solo
 * anuncian cambios en regiones que ya estaban, así que al cambiar un filtro se
 * oye «12 películas» sin que el foco se mueva de la barra. Mientras carga, el
 * recuento queda vacío y lo que se anuncia es el esqueleto («Buscando películas...»).
 */
function FilteredResults({ results, onSelectMovie, onClearFilters }: FilteredResultsProps) {
  const { movies, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } = results;
  const headingId = useId();

  let content: ReactNode;
  if (status === 'loading') {
    content = <GridSkeleton label="Buscando películas..." />;
  } else if (status === 'error') {
    content = <ErrorState title="No se pudieron cargar las películas" message={errorMessage} onRetry={reload} />;
  } else if (movies.length === 0) {
    content = (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title="No hay películas con estos filtros"
        description="Prueba con otro género u otro año, o quita los filtros para ver todas las películas."
        action={
          <Button variant="light" onClick={onClearFilters}>
            Quitar filtros
          </Button>
        }
      />
    );
  } else {
    content = (
      <>
        <ul role="list" className={POSTER_GRID_CLASS}>
          {movies.map((movie) => (
            <li key={movie.id}>
              <MovieCard movie={movie} onClick={onSelectMovie} className="w-full" />
            </li>
          ))}
        </ul>
        <LoadMoreFooter
          shown={movies.length}
          total={total}
          singular="película"
          plural="películas"
          hasMore={hasMore}
          loadingMore={loadingMore}
          loadMoreFailed={loadMoreFailed}
          onLoadMore={() => void loadMore()}
        />
      </>
    );
  }

  return (
    <section aria-labelledby={headingId} className="px-4 pt-4 sm:px-6">
      <div className="mb-4 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 id={headingId} className="text-lg font-bold tracking-tight text-white">
          Resultados
        </h2>
        <p role="status" className="text-sm text-muted tabular-nums">
          {status === 'ready' ? pluralize(total, 'película', 'películas') : ''}
        </p>
      </div>
      {content}
    </section>
  );
}
