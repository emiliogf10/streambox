import { Heart } from 'lucide-react';
import { useLikes } from '../hooks/useLikes';
import type { Movie, Series } from '../lib/types';
import { Button } from './Button';

/**
 * Propiedades de {@link LikeButton}: una película O una serie (excluyentes, el
 * compilador no deja pasar las dos). `className` solo sirve para colocarlo.
 */
type Props = { className?: string } & ({ movie: Movie; series?: never } | { series: Series; movie?: never });

/**
 * Botón «Me gusta» / «Te gusta» de una película o de una serie, en la cabecera
 * de su página junto a «Mi lista».
 *
 * **Distinto de «Mi lista» a simple vista y al oído.** «Mi lista» es lo que
 * quieres ver (icono `+`/✓, «Mi lista»/«En mi lista»); «Me gusta» es lo que ya
 * viste y te gustó (corazón, «Me gusta»/«Te gusta»). Con iconos y textos
 * propios no se confunden aunque estén uno al lado del otro.
 *
 * **Accesibilidad.**
 * - Es un botón de alternar (`aria-pressed`): el lector de pantalla dice si
 *   está pulsado, y el estado no depende solo del color (cambian el texto y el
 *   relleno del corazón).
 * - Nombre accesible = texto visible + título («Me gusta — Interstellar»,
 *   «Te gusta — Interstellar»): empieza por lo que se ve (WCAG 2.5.3) y, fuera
 *   de contexto, dice de qué título es (como «Mi lista — …»).
 * - Mientras su petición está en curso se bloquea con `aria-disabled` y no con
 *   `disabled`: un botón con `disabled` pierde el foco en Chromium, y quien usa
 *   teclado tendría que volver a buscarlo. Los clics de ese rato se ignoran
 *   (`useLikes` no deja dos cambios a la vez para el mismo título).
 * - `min-h-11` (44 px) y `focus-ring` vienen de `Button`.
 *
 * El corazón relleno usa el token `like` (sus contrastes, en `index.css`). Es
 * un refuerzo visual: el estado lo dicen el texto y `aria-pressed`.
 */
export function LikeButton(props: Props) {
  const { isLiked, isPending, toggle } = useLikes();
  const kind = props.series ? 'series' : 'movie';
  const item = props.series ?? props.movie;
  const liked = isLiked(item.id, kind);
  const pending = isPending(item.id, kind);

  return (
    <Button
      variant="outline"
      aria-pressed={liked}
      aria-disabled={pending || undefined}
      onClick={() => {
        if (!pending) void toggle(kind, item);
      }}
      className={`${liked ? 'bg-white/15' : ''} ${props.className ?? ''}`}
    >
      <Heart aria-hidden="true" className={`size-4 ${liked ? 'fill-current text-like' : ''}`} />
      {liked ? 'Te gusta' : 'Me gusta'}
      <span className="sr-only"> — {item.title}</span>
    </Button>
  );
}
