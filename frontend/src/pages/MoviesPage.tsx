import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Film } from 'lucide-react';
import { buttonClasses } from '../components/buttonStyles';
import { CatalogFilterBar } from '../components/CatalogFilterBar';
import { CatalogSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { FilteredCatalogResults } from '../components/FilteredCatalogResults';
import { HeroBanner } from '../components/HeroBanner';
import { LoadMoreFooter } from '../components/LoadMoreFooter';
import { MovieCard } from '../components/MovieCard';
import { MovieRow } from '../components/MovieRow';
import { useAuth } from '../context/AuthContext';
import { useMovieResults } from '../hooks/useCatalog';
import { useCatalogFilterBar } from '../hooks/useCatalogFilters';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { buildCatalogRows } from '../lib/catalog';
import { ADMIN_MOVIES_PATH } from './admin/adminPaths';

/** Propiedades de {@link CatalogView}: lo que devuelve `useMovieResults`. */
interface ViewProps {
  results: ReturnType<typeof useMovieResults>;
}

/**
 * Página `/peliculas`: el catálogo de películas con filtros por género, año y orden.
 *
 * Tiene DOS vistas, según haya filtros en la URL (ver `lib/catalogFilters.ts`):
 * - **Sin filtros** ({@link CatalogView}): la misma estructura que `/series` y
 *   que la portada: banner con la película más reciente, «Novedades», filas por
 *   género (≥3 títulos sin contar el del banner) y «Cargar más películas».
 * - **Con algún filtro** ({@link FilteredCatalogResults}): una cuadrícula con los
 *   resultados de `GET /api/movies/search`, su recuento y «Cargar más». Unas
 *   filas por género no tienen sentido si ya se ha elegido un género, ni un
 *   banner de "lo más reciente" si se ordena por título.
 *
 * La barra de filtros ({@link CatalogFilterBar}) está en las dos, junto al `<h1>`
 * «Películas» (visible, como el de `/series`, y presente en todos los estados).
 * Los filtros viven en la URL (`useCatalogFilterBar`, el mismo de `/series`): se comparten con el enlace y
 * «Atrás» vuelve al filtro anterior. En las dos vistas, cada tarjeta (y «Más
 * información» del banner) lleva a la página de su película (`/peliculas/:id`);
 * «Atrás» desde ella vuelve aquí con los mismos filtros, porque están en la URL.
 */
export function MoviesPage() {
  useDocumentTitle('Películas');
  const { filters, barProps, clearFilters } = useCatalogFilterBar('movies');
  const results = useMovieResults(filters);

  return (
    <div className="pb-12">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4 px-4 pt-6 pb-2 sm:px-6">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Películas</h1>
        <CatalogFilterBar {...barProps} />
      </div>

      {results.filtered ? (
        <FilteredCatalogResults
          results={{ ...results, items: results.movies }}
          renderCard={(movie) => <MovieCard movie={movie} className="w-full" />}
          singular="película"
          plural="películas"
          errorTitle="No se pudieron cargar las películas"
          emptyTitle="No hay películas con estos filtros"
          emptyDescription="Prueba con otro género u otro año, o quita los filtros."
          onClearFilters={clearFilters}
        />
      ) : (
        <CatalogView results={results} />
      )}
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
function CatalogView({ results }: ViewProps) {
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
      <HeroBanner movie={hero} />
      {rows.map((row) => (
        <MovieRow key={row.id} title={row.title} movies={row.items} />
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
