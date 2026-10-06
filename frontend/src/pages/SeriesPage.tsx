import { useMemo } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Tv } from 'lucide-react';
import { buttonClasses } from '../components/buttonStyles';
import { CatalogSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadMoreFooter } from '../components/LoadMoreFooter';
import { SeriesHeroBanner } from '../components/SeriesHeroBanner';
import { SeriesRow } from '../components/SeriesRow';
import { useAuth } from '../context/AuthContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useSeriesCatalog } from '../hooks/useSeriesCatalog';
import { buildCatalogRows } from '../lib/catalog';
import { ADMIN_SERIES_PATH } from './admin/adminPaths';

/**
 * Página `/series`: el catálogo de series, con la misma estructura que la portada.
 *
 * - **Banner**: la serie más reciente (`sort=createdAt&direction=desc`).
 * - **Filas**: "Novedades" y una por género con al menos 3 series, con la MISMA
 *   lógica que las películas (`buildCatalogRows` es genérica). La serie del
 *   banner no se repite debajo.
 * - **«Cargar más series»** mientras el servidor diga que hay más páginas; un
 *   fallo no destruye lo cargado (ver `usePagedCatalog`).
 * - Estados: cargando (esqueleto con la forma del banner y una fila), vacío
 *   y error con «Reintentar».
 *
 * Las series sin episodios no llegan nunca: el servidor las excluye del listado.
 * Por eso el estado vacío **depende del rol**:
 * - Un **usuario** ve «Todavía no hay series» y vuelve al inicio. No se le
 *   manda a las películas: el texto habla de series y el botón no debe llevar
 *   a otra sección que quizá también esté vacía.
 * - Un **administrador** puede tener series creadas pero ocultas (sin
 *   episodios): decirle «no hay series» sería falso y no le explicaría nada.
 *   Ve «Todavía no hay series visibles», el motivo y «Gestionar series».
 *   Mientras se carga su usuario se le trata como usuario normal (falla cerrado,
 *   igual que `isAdmin`): un usuario nunca ve el enlace al panel.
 *
 * El `<h1>` «Series» es visible (a diferencia del de la portada): el banner
 * se parece mucho al de inicio y el título deja claro en qué sección se está.
 * Está presente en todos los estados.
 */
export function SeriesPage() {
  useDocumentTitle('Series');
  const { isAdmin } = useAuth();
  const { series, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } =
    useSeriesCatalog();

  const hero = series[0];
  // Las filas se calculan sin la serie del banner para no repetirla justo debajo.
  const rows = useMemo(() => buildCatalogRows(series.slice(1)), [series]);

  let content: ReactNode;
  if (status === 'loading') {
    content = <CatalogSkeleton label="Cargando series..." />;
  } else if (status === 'error') {
    content = <ErrorState title="No se pudieron cargar las series" message={errorMessage} onRetry={reload} />;
  } else if (!hero) {
    content = isAdmin ? (
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
  } else {
    content = (
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

  return (
    <div className="pb-12">
      <h1 className="px-4 pt-6 text-2xl font-bold tracking-tight sm:px-6 sm:text-3xl">Series</h1>
      {content}
    </div>
  );
}
