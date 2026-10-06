import { Check, Plus } from 'lucide-react';
import { useFavorites } from '../context/FavoritesContext';
import type { Movie, Series } from '../lib/types';
import { Button } from './Button';

/**
 * Propiedades de {@link FavoriteButton}: una película O una serie (excluyentes,
 * el compilador no deja pasar las dos). `className` solo sirve para colocarlo
 * (p. ej. ancho en una rejilla); el aspecto lo decide el propio botón.
 */
type Props = { className?: string } & ({ movie: Movie; series?: never } | { series: Series; movie?: never });

/**
 * Botón "Mi lista" / "En mi lista" de una película o de una serie.
 *
 * Lee y modifica la lista compartida (`useFavorites`), así que el banner, el
 * modal de detalles, la página de una serie y "Mi lista" siempre muestran lo
 * mismo. Películas y series son listas distintas en el servidor (una película y
 * una serie pueden tener el mismo id), por eso el botón le dice al contexto de
 * qué tipo es. Mientras hay una petición en curso para ese título se bloquea
 * para evitar dobles clics. El estado se comunica con texto e icono (no solo con color).
 */
export function FavoriteButton(props: Props) {
  const { isFavorite, isPending, toggle, toggleSeries } = useFavorites();
  const kind = props.series ? 'series' : 'movie';
  const item = props.series ?? props.movie;
  const active = isFavorite(item.id, kind);

  return (
    <Button
      variant="outline"
      onClick={() => void (props.series ? toggleSeries(props.series) : toggle(props.movie))}
      disabled={isPending(item.id, kind)}
      className={`${active ? 'bg-white/15' : ''} ${props.className ?? ''}`}
    >
      {active ? (
        <Check aria-hidden="true" className="size-4 text-success" />
      ) : (
        <Plus aria-hidden="true" className="size-4" />
      )}
      {active ? 'En mi lista' : 'Mi lista'}
      <span className="sr-only"> — {item.title}</span>
    </Button>
  );
}
