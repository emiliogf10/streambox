import { Check, Plus } from 'lucide-react';
import { useFavorites } from '../context/FavoritesContext';
import type { Movie } from '../lib/types';
import { Button } from './Button';

/**
 * Botón "Mi lista" / "En mi lista" de una película.
 *
 * Lee y modifica la lista compartida (`useFavorites`), así que el banner, el
 * modal de detalles y la página "Mi lista" siempre muestran lo mismo. Mientras
 * hay una petición en curso para esa película se bloquea para evitar dobles clics.
 * El estado se comunica con texto e icono (no solo con color).
 *
 * `className` solo sirve para colocarlo (p. ej. ancho en una rejilla); el
 * aspecto lo decide el propio botón.
 */
export function FavoriteButton({ movie, className = '' }: { movie: Movie; className?: string }) {
  const { isFavorite, isPending, toggle } = useFavorites();
  const active = isFavorite(movie.id);

  return (
    <Button
      variant="outline"
      onClick={() => void toggle(movie)}
      disabled={isPending(movie.id)}
      className={`${active ? 'bg-white/15' : ''} ${className}`}
    >
      {active ? (
        <Check aria-hidden="true" className="size-4 text-success" />
      ) : (
        <Plus aria-hidden="true" className="size-4" />
      )}
      {active ? 'En mi lista' : 'Mi lista'}
      <span className="sr-only"> — {movie.title}</span>
    </Button>
  );
}
