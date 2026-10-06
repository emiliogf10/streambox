import { pluralize } from '../lib/series';
import { Button } from './Button';

/** Propiedades de {@link LoadMoreFooter}. */
interface Props {
  /** Títulos ya cargados. */
  shown: number;
  /** Títulos que hay en total en el servidor (`totalElements`). */
  total: number;
  /** Nombre del título en singular y en plural («película»/«películas», «serie»/«series»). */
  singular: string;
  plural: string;
  hasMore: boolean;
  loadingMore: boolean;
  loadMoreFailed: boolean;
  onLoadMore: () => void;
}

/**
 * Pie de un catálogo paginado: «Mostrando N de M películas» y el botón
 * «Cargar más películas» mientras el servidor diga que hay más páginas.
 *
 * Lo comparten la portada, `/series` y `/peliculas` (vista por filas y
 * cuadrícula de resultados); antes cada página repetía el mismo marcado.
 *
 * - El botón cambia de texto según el estado: «Cargando...» mientras espera
 *   (desactivado, para no pedir dos veces la misma página) y «Reintentar carga»
 *   si falló (el estado lo lleva `usePagedCatalog`, que conserva lo ya cargado).
 * - Una región `role="status"` oculta a la vista anuncia a los lectores de
 *   pantalla el resultado de pulsar el botón («25 películas cargadas»), porque
 *   las tarjetas nuevas aparecen lejos del foco y sin aviso pasarían desapercibidas.
 */
export function LoadMoreFooter({ shown, total, singular, plural, hasMore, loadingMore, loadMoreFailed, onLoadMore }: Props) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 pt-4 sm:px-6">
      <p className="text-sm text-muted">
        Mostrando {shown} de {pluralize(total, singular, plural)}
      </p>
      {hasMore && (
        <Button variant="outline" onClick={onLoadMore} disabled={loadingMore}>
          {loadingMore ? 'Cargando...' : loadMoreFailed ? 'Reintentar carga' : `Cargar más ${plural}`}
        </Button>
      )}
      <p role="status" className="sr-only">
        {loadingMore ? `Cargando más ${plural}` : pluralize(shown, `${singular} cargada`, `${plural} cargadas`)}
      </p>
    </div>
  );
}
