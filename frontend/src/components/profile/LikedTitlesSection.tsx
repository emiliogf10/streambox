import { useId, useState } from 'react';
import type { ReactNode } from 'react';
import { Heart } from 'lucide-react';
import { useLikes } from '../../hooks/useLikes';
import { pluralize } from '../../lib/series';
import type { CatalogItem } from '../../lib/types';
import { Button } from '../Button';
import { ErrorState } from '../ErrorState';
import { MovieCard } from '../MovieCard';
import { SeriesCard } from '../SeriesCard';

/**
 * Cuántos títulos de cada tipo se enseñan antes de «Mostrar todas». Doce son
 * filas completas en todos los anchos de la rejilla (cuatro filas de 3 a 375 px,
 * tres de 4 desde `sm`, dos de 6 desde `md`) y no convierten el perfil en un catálogo. El resto se
 * despliega aquí mismo: no hay (ni se quiere, decisión del autor) una página
 * aparte de «Me gusta».
 */
export const LIKED_PREVIEW_LIMIT = 12;

/**
 * Rejilla de tarjetas pequeñas: más densa que la de «Mi lista» porque aquí son
 * un resumen dentro del perfil, no la pantalla principal.
 */
const SMALL_GRID_CLASS = 'grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-6';

/** Propiedades de {@link LikedGroup}. */
interface LikedGroupProps<T extends CatalogItem> {
  /** «Películas» o «Series» (su `<h3>`). */
  title: string;
  /** Plural del tipo para el botón («Mostrar las 15 películas»). */
  noun: string;
  items: T[];
  renderCard: (item: T) => ReactNode;
}

/**
 * Un grupo de la sección (películas o series): `<h3>` con el número, la
 * rejilla y, si hay más de {@link LIKED_PREVIEW_LIMIT}, el botón que despliega
 * el resto (`aria-expanded` + `aria-controls`). Al plegar, el foco se queda en
 * el botón, que sigue en su sitio.
 */
function LikedGroup<T extends CatalogItem>({ title, noun, items, renderCard }: LikedGroupProps<T>) {
  const [expanded, setExpanded] = useState(false);
  const listId = useId();
  const shown = expanded ? items : items.slice(0, LIKED_PREVIEW_LIMIT);
  return (
    <div>
      <h3 className="mb-3 text-base font-semibold text-white">
        {title} <span className="font-normal text-muted tabular-nums">({items.length})</span>
      </h3>
      <ul id={listId} role="list" className={SMALL_GRID_CLASS}>
        {shown.map((item) => (
          <li key={item.id} className="min-w-0">
            {renderCard(item)}
          </li>
        ))}
      </ul>
      {items.length > LIKED_PREVIEW_LIMIT && (
        <Button
          variant="ghost"
          aria-expanded={expanded}
          aria-controls={listId}
          onClick={() => setExpanded((value) => !value)}
          className="mt-3"
        >
          {expanded ? 'Mostrar menos' : `Mostrar las ${items.length} ${noun}`}
        </Button>
      )}
    </div>
  );
}

/**
 * Estado vacío de la sección. El mismo texto vale para los dos roles (un
 * administrador también tiene sus «Me gusta») y dice cómo llenarla y para qué
 * sirve. Sin botón: el camino es la página de un título (las tarjetas de «De tu
 * lista», justo encima si las hay, o las secciones de la barra), y repetir aquí el
 * «Explorar películas» de «Tus géneros» serían dos botones iguales en la misma
 * pantalla cuando las dos están vacías.
 */
function LikesEmpty() {
  return (
    <div className="flex flex-col items-start gap-4 rounded-xl border border-dashed border-white/15 px-5 py-6 sm:flex-row sm:items-center">
      <div
        aria-hidden="true"
        className="flex size-12 shrink-0 items-center justify-center rounded-full border border-line bg-surface text-muted"
      >
        <Heart className="size-6" />
      </div>
      <div className="min-w-0 max-w-prose">
        <p className="font-semibold text-white">Todavía no te gusta ningún título</p>
        <p className="mt-1 text-sm leading-relaxed text-muted">
          Cuando veas una película o una serie que te guste, pulsa «Me gusta» en su página. Aquí la verás, y en el
          inicio te recomendaremos títulos parecidos.
        </p>
      </div>
    </div>
  );
}

/**
 * Sección «Te gusta» de `/perfil`: las películas y series que te gustan, cada
 * una enlazada a su página, lo más reciente primero (el orden del servidor).
 *
 * **Dos grupos y no una lista mezclada.** El servidor da cada lista ordenada por
 * cuándo le diste «Me gusta», pero no esa fecha: mezclarlas por recencia sería
 * inventar un orden. Un grupo sin títulos no se pinta.
 *
 * Estados (los datos son de `useLikes`, independientes de «Mi lista»):
 * - **Cargando**: una línea anunciada («Cargando lo que te gusta...»).
 * - **Error**: aviso compacto con «Reintentar» (solo vuelve a pedir esta
 *   sección). Al pulsarlo, el foco se queda en el contenedor de la sección
 *   (enfocable por código), no salta al principio de la página.
 * - **Vacía**: {@link LikesEmpty}.
 * - **Con títulos**: el total junto al título y un grupo por tipo
 *   ({@link LikedGroup}), con tarjetas pequeñas.
 */
export function LikedTitlesSection() {
  const { movies, series, status, errorMessage, reload } = useLikes();
  const headingId = useId();
  const total = movies.length + series.length;

  let body: ReactNode;
  if (status === 'error') {
    body = (
      <ErrorState compact title="No se pudo cargar lo que te gusta" message={errorMessage} onRetry={reload} />
    );
  } else if (status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando lo que te gusta...
      </p>
    );
  } else if (total === 0) {
    body = <LikesEmpty />;
  } else {
    body = (
      <div className="flex flex-col gap-8">
        {movies.length > 0 && (
          <LikedGroup
            title="Películas"
            noun="películas"
            items={movies}
            renderCard={(movie) => <MovieCard movie={movie} className="w-full" />}
          />
        )}
        {series.length > 0 && (
          <LikedGroup
            title="Series"
            noun="series"
            items={series}
            renderCard={(item) => <SeriesCard series={item} className="w-full" />}
          />
        )}
      </div>
    );
  }

  return (
    <section aria-labelledby={headingId} className="mt-10">
      <div className="mb-4 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 id={headingId} className="text-lg font-bold tracking-tight text-white">
          Te gusta
        </h2>
        {status === 'ready' && total > 0 && (
          <p className="text-sm text-muted">{pluralize(total, 'título', 'títulos')} · lo más reciente primero</p>
        )}
      </div>
      {/* tabIndex -1: si «Reintentar» desaparece al pulsarlo, el foco se queda aquí (`useRetryFocus`). */}
      <div tabIndex={-1} className="focus-ring rounded-xl">
        {body}
      </div>
    </section>
  );
}
