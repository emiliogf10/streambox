import { Link } from 'react-router-dom';
import { ChevronRight } from 'lucide-react';
import { catalogByGenrePath } from '../lib/catalogFilters';
import type { CatalogKind } from '../lib/catalogFilters';
import type { Genre } from '../lib/types';

/** Final del nombre accesible de cada enlace, según el catálogo al que lleva. */
const LABEL_SUFFIX: Record<CatalogKind, string> = {
  movies: 'ver todas las películas del género',
  series: 'ver todas las series del género',
};

/** Propiedades de {@link GenreLinks}. */
interface Props {
  /** Catálogo al que llevan los enlaces: `/peliculas?genero=…` o `/series?genero=…`. */
  kind: CatalogKind;
  genres: Genre[];
}

/**
 * Géneros de la ficha de una película o de una serie como enlaces a su
 * catálogo filtrado por ese género (`/peliculas?genero=4`, `/series?genero=4`):
 * la forma natural de seguir explorando «más como esta». Cada enlace lleva al
 * catálogo del MISMO tipo que la ficha (de una serie, a las series).
 *
 * Cada enlace se ve como una etiqueta con una flecha (la flecha dice que se
 * puede pulsar; no se confía solo en el color) y su nombre accesible empieza
 * por el texto visible («Drama, ver todas las series del género»), así que
 * también se puede activar por voz diciendo lo que se ve (WCAG 2.5.3). Altura
 * mínima de 36 px, por encima de los 24 px de WCAG 2.5.8.
 *
 * El nombre va en `aria-label` y no en un `<span class="sr-only">` tras el
 * texto visible: ese span lleva `position: absolute`, que lo convierte en
 * bloque, y Chromium mete un espacio al juntar el texto de un hijo que no es
 * en línea («Drama , ver todas…»). jsdom no aplica CSS y no lo ve; lo vigilan
 * los E2E de `movies.spec.ts` y `series.spec.ts`.
 */
export function GenreLinks({ kind, genres }: Props) {
  return (
    <ul role="list" className="flex flex-wrap gap-2">
      {genres.map((genre) => (
        <li key={genre.id}>
          <Link
            to={catalogByGenrePath(kind, genre.id)}
            aria-label={`${genre.name}, ${LABEL_SUFFIX[kind]}`}
            className="focus-ring inline-flex min-h-9 items-center gap-1 rounded-full border border-white/20 bg-white/5 pr-2.5 pl-3.5 text-sm font-medium text-white/90 transition-colors duration-150 hover:border-white/40 hover:bg-white/10 hover:text-white"
          >
            {genre.name}
            <ChevronRight aria-hidden="true" className="size-3.5 text-white/60" />
          </Link>
        </li>
      ))}
    </ul>
  );
}
