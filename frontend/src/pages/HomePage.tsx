import { Fragment, useMemo } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Film } from 'lucide-react';
import { buttonClasses } from '../components/buttonStyles';
import { CatalogSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { HeroBanner } from '../components/HeroBanner';
import { LatestSeriesRow } from '../components/LatestSeriesRow';
import { LoadMoreFooter } from '../components/LoadMoreFooter';
import { MovieRow } from '../components/MovieRow';
import { RecommendedRows } from '../components/RecommendedRows';
import { useAuth } from '../context/AuthContext';
import { useCatalog } from '../hooks/useCatalog';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { usePrefetchRecommendations } from '../hooks/useRecommendations';
import { buildCatalogRows } from '../lib/catalog';
import { ADMIN_MOVIES_PATH } from './admin/adminPaths';

/**
 * Estado vacío de la portada cuando no hay NI películas NI series visibles.
 *
 * - A un **usuario** le basta saber que no hay nada todavía. Sin botón: no hay
 *   ningún sitio útil al que mandarle (las demás secciones también están vacías).
 * - A un **administrador** «el catálogo está vacío» podría ser falso (puede
 *   tener series ocultas, sin episodios) y no le diría qué hacer: se le explica
 *   qué hace visible cada cosa y se le lleva al panel. Mientras carga el
 *   usuario, `isAdmin` es `false` (falla cerrado).
 */
function EmptyCatalog({ isAdmin }: { isAdmin: boolean }) {
  return isAdmin ? (
    <EmptyState
      icon={<Film className="size-8" />}
      title="Todavía no hay nada visible en el catálogo"
      description="Las películas aparecen en cuanto se añaden; las series, cuando tienen al menos un episodio. Gestiónalas desde el panel de administración."
      action={
        <Link to={ADMIN_MOVIES_PATH} className={buttonClasses('light')}>
          Gestionar catálogo
        </Link>
      }
    />
  ) : (
    <EmptyState
      icon={<Film className="size-8" />}
      title="El catálogo está vacío"
      description="Todavía no hay películas ni series. Vuelve más tarde."
    />
  );
}

/**
 * Hueco de las filas «… recomendadas para ti» ({@link RecommendedRows}).
 *
 * Es enfocable por código (`tabIndex={-1}`, no es una parada de tabulador): si
 * «Reintentar» de su aviso de error desaparece al pulsarlo, el foco se queda
 * aquí, en la misma zona (`useRetryFocus`), y no salta al `<main>`. `empty:hidden`:
 * sin recomendaciones, o mientras cargan sin motivo para esperarlas, no pinta
 * nada y no deja hueco.
 */
function RecommendationsSlot() {
  return (
    <div tabIndex={-1} className="focus-ring rounded-xl empty:hidden">
      <RecommendedRows />
    </div>
  );
}

/**
 * Portada de la aplicación.
 *
 * - El banner es la película más reciente del catálogo.
 * - Debajo, filas horizontales: "Novedades", las recomendadas para el usuario
 *   («Películas/Series recomendadas para ti», solo si le gusta algo y hay qué
 *   recomendar; ver `RecommendedRows`), la fila «Series» (las series más
 *   recientes, ver `LatestSeriesRow`; no aparece si no hay ninguna) y una por
 *   género (ver `buildCatalogRows`). Sin películas, las recomendaciones (de
 *   series, si las hay) van antes de la fila «Series».
 * - El catálogo está paginado en el servidor; el botón "Cargar más películas"
 *   pide la página siguiente y la añade sin recargar nada.
 *
 * Gestiona los tres estados: cargando, vacío y error (con "Reintentar"). El
 * fallo de "cargar más" se trata aparte para no perder lo ya mostrado.
 *
 * **Sin películas** la portada no da el catálogo por vacío sin más: puede haber
 * series. Se pinta la fila «Series» y solo si tampoco tiene ninguna aparece
 * {@link EmptyCatalog}. Antes decía «El catálogo está vacío» aunque hubiera series.
 *
 * Estructura de encabezados: un único `<h1>` (oculto visualmente, presente en
 * TODOS los estados) y, por debajo, el banner y cada fila como `<h2>` hermanos.
 *
 * Bajo el banner ya no hay paneles de "Sinopsis / Reparto / Ficha": el reparto
 * no existe en la API (el panel solo decía "no disponible") y la ficha repetía
 * año y duración del banner; además empujaban las filas fuera de la primera
 * pantalla. La sinopsis va recortada en el banner y entera en la página de la
 * película («Más información» o cualquier tarjeta llevan a `/peliculas/:id`).
 */
export function HomePage() {
  useDocumentTitle('Inicio');
  const { isAdmin } = useAuth();
  const { movies, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } =
    useCatalog();
  // Las recomendaciones se piden ya, en paralelo con el catálogo, y no cuando se montan sus filas
  // (después de él): así llegan a la vez y no empujan la portada al aparecer.
  usePrefetchRecommendations();

  const hero = movies[0];
  // Las filas se calculan sin la película del banner para no repetirla justo debajo.
  const rows = useMemo(() => buildCatalogRows(movies.slice(1)), [movies]);

  let content: ReactNode;
  if (status === 'loading') {
    // Silueta del banner y de una fila (no un spinner): el cambio al contenido real es mínimo.
    content = <CatalogSkeleton label="Cargando catálogo..." />;
  } else if (status === 'error') {
    content = <ErrorState title="No se pudo cargar el catálogo" message={errorMessage} onRetry={reload} />;
  } else if (!hero) {
    // Sin películas puede seguir habiendo series: la fila las enseña, y solo si
    // tampoco hay ninguna se dice que el catálogo está vacío. `pt-6`: sin banner
    // encima, la fila quedaría pegada a la barra superior.
    content = (
      <div className="pt-6">
        <RecommendationsSlot />
        <LatestSeriesRow whenEmpty={<EmptyCatalog isAdmin={isAdmin} />} />
      </div>
    );
  } else {
    content = (
      <>
        <HeroBanner movie={hero} />

        {rows.map((row, index) => (
          <Fragment key={row.id}>
            <MovieRow title={row.title} movies={row.items} />
            {/* Tras «Novedades», lo personal (las recomendaciones) y la fila «Series»: a la vista sin bajar mucho. */}
            {index === 0 && (
              <>
                <RecommendationsSlot />
                <LatestSeriesRow />
              </>
            )}
          </Fragment>
        ))}
        {rows.length === 0 && (
          <>
            <RecommendationsSlot />
            <LatestSeriesRow />
          </>
        )}

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
    <div className="pb-12">
      <h1 className="sr-only">Catálogo de películas</h1>
      {content}
    </div>
  );
}
