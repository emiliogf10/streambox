import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Film } from 'lucide-react';
import { Button } from '../components/Button';
import { CatalogSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { HeroBanner } from '../components/HeroBanner';
import { MovieDetailsModal } from '../components/MovieDetailsModal';
import { MovieRow } from '../components/MovieRow';
import { useCatalog } from '../hooks/useCatalog';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { buildCatalogRows } from '../lib/catalog';
import type { Movie } from '../lib/types';

/**
 * Portada de la aplicación.
 *
 * - El banner es la película más reciente del catálogo.
 * - Debajo, filas horizontales: "Novedades" y una por género (ver `buildCatalogRows`).
 * - El catálogo está paginado en el servidor; el botón "Cargar más películas"
 *   pide la página siguiente y la añade sin recargar nada.
 *
 * Gestiona los tres estados: cargando, vacío y error (con "Reintentar"). El
 * fallo de "cargar más" se trata aparte para no perder lo ya mostrado.
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
    content = (
      <EmptyState
        icon={<Film className="size-8" />}
        title="El catálogo está vacío"
        description="Todavía no hay películas disponibles. Vuelve a probar más tarde."
      />
    );
  } else {
    content = (
      <>
        <HeroBanner movie={hero} onDetails={setSelectedMovie} />

        {rows.map((row) => (
          <MovieRow key={row.id} title={row.title} movies={row.movies} onSelectMovie={setSelectedMovie} />
        ))}

        <div className="flex flex-col items-center gap-3 px-4 pt-4 sm:px-6">
          <p className="text-sm text-muted">
            Mostrando {movies.length} de {total} películas
          </p>
          {hasMore && (
            <Button variant="outline" onClick={() => void loadMore()} disabled={loadingMore}>
              {loadingMore ? 'Cargando...' : loadMoreFailed ? 'Reintentar carga' : 'Cargar más películas'}
            </Button>
          )}
          {/* Anuncia a los lectores de pantalla el resultado de pulsar "Cargar más". */}
          <p role="status" className="sr-only">
            {loadingMore ? 'Cargando más películas' : `${movies.length} películas cargadas`}
          </p>
        </div>

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
