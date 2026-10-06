import { Fragment, useMemo, useState } from 'react';
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
import { MovieDetailsModal } from '../components/MovieDetailsModal';
import { MovieRow } from '../components/MovieRow';
import { useAuth } from '../context/AuthContext';
import { useCatalog } from '../hooks/useCatalog';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { buildCatalogRows } from '../lib/catalog';
import type { Movie } from '../lib/types';
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
 * Portada de la aplicación.
 *
 * - El banner es la película más reciente del catálogo.
 * - Debajo, filas horizontales: "Novedades", la fila «Series» (las series más
 *   recientes, ver `LatestSeriesRow`; no aparece si no hay ninguna) y una por
 *   género (ver `buildCatalogRows`).
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
 * pantalla. La sinopsis va recortada en el banner y entera en "Más información".
 */
export function HomePage() {
  useDocumentTitle('Inicio');
  const { isAdmin } = useAuth();
  const { movies, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } =
    useCatalog();
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);

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
        <LatestSeriesRow whenEmpty={<EmptyCatalog isAdmin={isAdmin} />} />
      </div>
    );
  } else {
    content = (
      <>
        <HeroBanner movie={hero} onDetails={setSelectedMovie} />

        {rows.map((row, index) => (
          <Fragment key={row.id}>
            <MovieRow title={row.title} movies={row.items} onSelectMovie={setSelectedMovie} />
            {/* La fila «Series» va justo después de «Novedades»: a la vista sin bajar mucho, sin desplazar al banner. */}
            {index === 0 && <LatestSeriesRow />}
          </Fragment>
        ))}
        {rows.length === 0 && <LatestSeriesRow />}

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

        {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
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
