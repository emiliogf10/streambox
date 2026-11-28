import type { ReactNode } from 'react';
import { useSimilarTitles } from '../hooks/useSimilarTitles';
import type { SimilarTitleOf } from '../hooks/useSimilarTitles';
import type { CatalogKind } from '../lib/catalogFilters';
import { RowSkeleton } from './CatalogSkeleton';
import { ErrorState } from './ErrorState';
import { MovieCard } from './MovieCard';
import { PosterRow } from './PosterRow';
import { SeriesCard } from './SeriesCard';

/** Textos y tarjeta de la fila de similares de un tipo de catálogo. */
interface SimilarRowConfig<K extends CatalogKind> {
  /** Título de la fila (su `<h2>` y el nombre de su región). */
  title: string;
  /** Texto para lectores de pantalla mientras carga. */
  loadingLabel: string;
  /** Título del aviso de error. */
  errorTitle: string;
  /** Tarjeta de cada título: la misma de las filas de la portada (enlace a su página). */
  renderCard: (item: SimilarTitleOf[K]) => ReactNode;
}

/**
 * Lo único que cambia entre «Películas similares» y «Series similares». Es un
 * tipo *mapeado* por `CatalogKind` a propósito: así `SIMILAR_ROWS[kind]` sabe,
 * sin conversiones de tipo, que la tarjeta de `movies` recibe una `Movie` y la
 * de `series`, una `Series`.
 */
type SimilarRowConfigs = { [K in CatalogKind]: SimilarRowConfig<K> };

/** Configuración de la fila de cada tipo. */
const SIMILAR_ROWS: SimilarRowConfigs = {
  movies: {
    title: 'Películas similares',
    loadingLabel: 'Cargando películas similares...',
    errorTitle: 'No se pudieron cargar las películas similares',
    renderCard: (movie) => <MovieCard movie={movie} />,
  },
  series: {
    title: 'Series similares',
    loadingLabel: 'Cargando series similares...',
    errorTitle: 'No se pudieron cargar las series similares',
    renderCard: (series) => <SeriesCard series={series} />,
  },
};

/** Propiedades de {@link SimilarTitlesRow}. */
interface Props<K extends CatalogKind> {
  /** Catálogo del título de la página: `movies` en `/peliculas/:id`, `series` en `/series/:id`. */
  kind: K;
  /** Id del título de la página. */
  id: number;
  /**
   * `true` cuando el servidor ha confirmado el título: `confirmed` de
   * `useMovieDetail` (no la copia provisional de un listado) o el estado
   * `ready` de `useSeriesDetail` (que nunca enseña copias provisionales).
   */
  enabled: boolean;
}

/**
 * Fila de similares al pie de la página de un título («Películas similares» en
 * `/peliculas/:id`, «Series similares» en `/series/:id`): para seguir
 * navegando por títulos parecidos sin volver al catálogo. Es la misma
 * {@link PosterRow} de la portada (tarjetas que enlazan a su página, flechas
 * solo si la fila desborda), con el orden que da el servidor.
 *
 * **Un componente para los dos tipos.** Las dos rutas de la API tienen el mismo
 * contrato y las dos filas, los mismos estados; cambian solo los textos y la
 * tarjeta ({@link SIMILAR_ROWS}). Dos componentes casi idénticos acabarían
 * divergiendo (un arreglo en uno y no en el otro).
 *
 * Estados, pensados para que la fila nunca estropee la página que la contiene:
 * - **Cargando**: la silueta de una fila ({@link RowSkeleton}), con su hueco ya
 *   reservado, anunciada como «Cargando películas/series similares...».
 * - **Sin similares** (lista vacía) o **404**: no se pinta nada, ni siquiera el
 *   título: un encabezado sin tarjetas prometería algo que no hay. El 404 solo
 *   llega si el título se borra (o la serie se queda sin episodios) mientras se
 *   ve, y entonces la página entera pasa a «no encontrada».
 * - **Error** (red, 5xx...): un aviso compacto (`ErrorState compact`) con
 *   «Reintentar» que solo vuelve a pedir la fila; el resto de la página sigue
 *   siendo útil. El texto es verdad para cualquier rol: no se pudo cargar.
 *   Al pulsarlo, el foco pasa al contenedor de la fila en la página (enfocable
 *   por código y presente en todos los estados; ver `useRetryFocus`).
 *
 * Al pulsar una tarjeta cambia el título de la página: la clave de la caché es
 * otra, así que la fila pasa a cargando (nunca enseña los similares del
 * anterior) y `RouteChangeFocus` sube el scroll y lleva el foco al contenido.
 */
export function SimilarTitlesRow<K extends CatalogKind>({ kind, id, enabled }: Props<K>) {
  const { status, items, errorMessage, reload } = useSimilarTitles(kind, id, enabled);
  const config: SimilarRowConfig<K> = SIMILAR_ROWS[kind];

  if (status === 'hidden' || (status === 'ready' && items.length === 0)) return null;
  if (status === 'ready') return <PosterRow title={config.title} items={items} renderItem={config.renderCard} />;
  if (status === 'error') {
    return (
      <div className="mb-9 px-4 sm:mb-10 sm:px-6">
        <ErrorState compact title={config.errorTitle} message={errorMessage} onRetry={reload} />
      </div>
    );
  }
  return <RowSkeleton label={config.loadingLabel} />;
}
