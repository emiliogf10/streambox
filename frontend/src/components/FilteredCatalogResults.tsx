import { useId, useRef } from 'react';
import type { ReactNode } from 'react';
import { SearchX } from 'lucide-react';
import type { PagedCatalog } from '../hooks/usePagedCatalog';
import { pluralize } from '../lib/series';
import { Button } from './Button';
import { GridSkeleton } from './CatalogSkeleton';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';
import { LoadMoreFooter } from './LoadMoreFooter';
import { POSTER_GRID_CLASS } from './posterGridStyles';

/** Propiedades de {@link FilteredCatalogResults}. */
interface Props<T extends { id: number }> {
  /** Resultados de la búsqueda filtrada (`usePagedCatalog` sobre `/movies/search` o `/series/search`). */
  results: PagedCatalog<T>;
  /** Pinta la tarjeta de un resultado (`MovieCard`, `SeriesCard`) a todo el ancho de su celda. */
  renderCard: (item: T) => ReactNode;
  /** Palabra para un resultado y para varios: «película»/«películas», «serie»/«series». */
  singular: string;
  plural: string;
  /** Título del estado de error («No se pudieron cargar las series»). */
  errorTitle: string;
  /** Título y texto del estado vacío: los decide la página, porque pueden depender del rol. */
  emptyTitle: string;
  emptyDescription: string;
  /** «Quitar filtros» del estado vacío. */
  onClearFilters: () => void;
  /**
   * Acción secundaria opcional del estado vacío, junto a «Quitar filtros» (p. ej.
   * «Gestionar series» para un administrador). La decide la página, que sabe
   * para quién es; debe tener aspecto de secundaria (`outline`).
   */
  emptySecondaryAction?: ReactNode;
}

/**
 * Cuadrícula de resultados de un catálogo filtrado (`/peliculas` o `/series`
 * con algún filtro), con su recuento, «Cargar más» y los tres estados.
 *
 * La sección (con su `<h2>` «Resultados») existe en TODOS los estados, y con
 * ella la región `role="status"` del recuento: los lectores de pantalla solo
 * anuncian cambios en regiones que ya estaban, así que al cambiar un filtro se
 * oye «12 películas» sin que el foco se mueva de la barra. Mientras carga, el
 * recuento queda vacío y lo que se anuncia es el esqueleto («Buscando series...»).
 *
 * Sin resultados, el estado vacío ofrece «Quitar filtros» (la salida que
 * siempre funciona) y, si la página la da, una acción secundaria a su lado;
 * su texto también lo da la página, que sabe para quién es.
 */
export function FilteredCatalogResults<T extends { id: number }>({
  results,
  renderCard,
  singular,
  plural,
  errorTitle,
  emptyTitle,
  emptyDescription,
  onClearFilters,
  emptySecondaryAction,
}: Props<T>) {
  const { items, total, status, errorMessage, loadingMore, loadMoreFailed, hasMore, loadMore, reload } = results;
  const headingId = useId();
  // Destino del foco cuando «Reintentar» desaparece al pulsarlo: el encabezado está en todos los estados.
  const headingRef = useRef<HTMLHeadingElement>(null);

  let content: ReactNode;
  if (status === 'loading') {
    content = <GridSkeleton label={`Buscando ${plural}...`} />;
  } else if (status === 'error') {
    content = (
      <ErrorState title={errorTitle} message={errorMessage} onRetry={reload} retryFocusRef={headingRef} />
    );
  } else if (items.length === 0) {
    content = (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title={emptyTitle}
        description={emptyDescription}
        action={
          // En móvil, una debajo de otra; desde `sm`, en fila. La principal va primero (orden de lectura y de tabulación).
          <div className="flex flex-col items-center gap-3 sm:flex-row">
            <Button variant="light" onClick={onClearFilters}>
              Quitar filtros
            </Button>
            {emptySecondaryAction}
          </div>
        }
      />
    );
  } else {
    content = (
      <>
        <ul role="list" className={POSTER_GRID_CLASS}>
          {items.map((item) => (
            <li key={item.id}>{renderCard(item)}</li>
          ))}
        </ul>
        <LoadMoreFooter
          shown={items.length}
          total={total}
          singular={singular}
          plural={plural}
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
        {/* tabIndex -1: recibe el foco por código tras «Reintentar» (ver `useRetryFocus`); no es una parada de tabulador. */}
        <h2 id={headingId} ref={headingRef} tabIndex={-1} className="focus-ring rounded-sm text-lg font-bold tracking-tight text-white">
          Resultados
        </h2>
        <p role="status" className="text-sm text-muted tabular-nums">
          {status === 'ready' ? pluralize(total, singular, plural) : ''}
        </p>
      </div>
      {content}
    </section>
  );
}
