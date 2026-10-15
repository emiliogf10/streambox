import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { Tv } from 'lucide-react';
import { buttonClasses } from '../components/buttonStyles';
import { CatalogFilterBar } from '../components/CatalogFilterBar';
import { CatalogSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { FilteredCatalogResults } from '../components/FilteredCatalogResults';
import { LoadMoreFooter } from '../components/LoadMoreFooter';
import { SeriesCard } from '../components/SeriesCard';
import { SeriesHeroBanner } from '../components/SeriesHeroBanner';
import { SeriesRow } from '../components/SeriesRow';
import { useAuth } from '../context/AuthContext';
import { useCatalogFilterBar } from '../hooks/useCatalogFilters';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useSeriesResults } from '../hooks/useSeriesCatalog';
import { buildCatalogRows } from '../lib/catalog';
import { ADMIN_SERIES_PATH } from './admin/adminPaths';

/** Propiedades de {@link SeriesCatalogView}: lo que devuelve `useSeriesResults`. */
interface ViewProps {
  results: ReturnType<typeof useSeriesResults>;
}

/**
 * Página `/series`: el catálogo de series con filtros por género, año de
 * estreno y orden, igual que `/peliculas`.
 *
 * Tiene DOS vistas, según haya filtros en la URL (ver `lib/catalogFilters.ts`):
 * - **Sin filtros** ({@link SeriesCatalogView}): la misma estructura que la
 *   portada: banner con la serie más reciente, «Novedades», filas por género
 *   (≥3 series sin contar la del banner) y «Cargar más series».
 * - **Con algún filtro** ({@link FilteredCatalogResults}): una cuadrícula con
 *   los resultados de `GET /api/series/search`, su recuento y «Cargar más».
 *
 * La barra de filtros ({@link CatalogFilterBar}) es la de `/peliculas`, con los
 * mismos parámetros en la URL (`?genero=4&anio=2014&orden=titulo-asc`), salvo
 * los órdenes por duración: una serie no tiene duración y el servidor no los
 * admite. Está en las dos vistas, junto al `<h1>` «Series», visible y presente
 * en todos los estados (el banner se parece mucho al de inicio y el título deja
 * claro en qué sección se está). Los géneros de la ficha de una serie llevan
 * aquí, filtrada por ese género.
 *
 * **Estados vacíos según el rol.** Las series sin episodios no llegan nunca:
 * el servidor las excluye del listado y de la búsqueda. Por eso:
 * - Sin filtros y sin series, un **usuario** ve «Todavía no hay series» y
 *   vuelve al inicio (no a las películas: el texto habla de series y esa
 *   sección quizá también esté vacía). Un **administrador** puede tener series
 *   creadas pero ocultas: decirle «no hay series» sería falso; ve «Todavía no
 *   hay series visibles», el motivo y «Gestionar series».
 * - Con filtros y sin resultados, los dos ven «Quitar filtros». Al usuario se
 *   le dice que no hay series con esos filtros; al administrador, que no hay
 *   series VISIBLES con ellos y que las que no tienen episodios no aparecen
 *   aunque coincidan (es la duda que tendría al no ver una serie que sabe que creó).
 *
 * Mientras se carga su usuario, a un administrador se le trata como a un
 * usuario normal (falla cerrado, igual que `isAdmin`): un usuario nunca ve el
 * enlace al panel ni textos sobre series ocultas.
 */
export function SeriesPage() {
  useDocumentTitle('Series');
  const { isAdmin } = useAuth();
  const { filters, barProps, clearFilters } = useCatalogFilterBar('series');
  const results = useSeriesResults(filters);

  return (
    <div className="pb-12">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4 px-4 pt-6 pb-2 sm:px-6">
        <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Series</h1>
        <CatalogFilterBar {...barProps} />
      </div>

      {results.filtered ? (
        <FilteredCatalogResults
          results={{ ...results, items: results.series }}
          renderCard={(series) => <SeriesCard series={series} className="w-full" />}
          singular="serie"
          plural="series"
          errorTitle="No se pudieron cargar las series"
          emptyTitle={isAdmin ? 'No hay series visibles con estos filtros' : 'No hay series con estos filtros'}
          emptyDescription={
            isAdmin
              ? 'Prueba con otro género u otro año, o quita los filtros. Las series sin episodios no aparecen aunque coincidan.'
              : 'Prueba con otro género u otro año, o quita los filtros.'
          }
          onClearFilters={clearFilters}
        />
      ) : (
        <SeriesCatalogView results={results} />
      )}
    </div>
  );
}

/**
 * Vista sin filtros: banner + filas, como la portada. La serie del banner no
 * se repite en las filas, y «Cargar más series» sigue mientras el servidor diga
 * que hay más páginas (un fallo no destruye lo cargado, ver `usePagedCatalog`).
 * Estados: cargando (esqueleto con la forma del banner y una fila), vacío según
 * el rol (ver {@link SeriesPage}) y error con «Reintentar».
 */
function SeriesCatalogView({ results }: ViewProps) {
  const { isAdmin } = useAuth();
  const { series, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } = results;
  const hero = series[0];
  // Las filas se calculan sin la serie del banner para no repetirla justo debajo.
  const rows = useMemo(() => buildCatalogRows(series.slice(1)), [series]);

  if (status === 'loading') return <CatalogSkeleton label="Cargando series..." />;
  if (status === 'error') {
    return <ErrorState title="No se pudieron cargar las series" message={errorMessage} onRetry={reload} />;
  }
  if (!hero) {
    return isAdmin ? (
      <EmptyState
        icon={<Tv className="size-8" />}
        title="Todavía no hay series visibles"
        description="Las series solo aparecen aquí cuando tienen al menos un episodio. Créalas y añádeles episodios desde el panel de administración."
        action={
          <Link to={ADMIN_SERIES_PATH} className={buttonClasses('light')}>
            Gestionar series
          </Link>
        }
      />
    ) : (
      <EmptyState
        icon={<Tv className="size-8" />}
        title="Todavía no hay series"
        description="Cuando se publiquen series aparecerán aquí."
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
      <SeriesHeroBanner series={hero} />
      {rows.map((row) => (
        <SeriesRow key={row.id} title={row.title} series={row.items} />
      ))}
      <LoadMoreFooter
        shown={series.length}
        total={total}
        singular="serie"
        plural="series"
        hasMore={hasMore}
        loadingMore={loadingMore}
        loadMoreFailed={loadMoreFailed}
        onLoadMore={() => void loadMore()}
      />
    </>
  );
}
