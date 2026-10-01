import { useId, useRef } from 'react';
import { X } from 'lucide-react';
import type { Movie } from '../lib/types';
import { FavoriteButton } from './FavoriteButton';
import { Modal } from './Modal';
import { MovieMetaTags } from './MovieMetaTags';
import { MoviePoster } from './MoviePoster';
import { WatchButton } from './WatchButton';

/**
 * Propiedades para el componente MovieDetailsModal.
 */
interface Props {
  movie: Movie;
  onClose: () => void;
}

/**
 * Diálogo con la información detallada de una película.
 *
 * La accesibilidad (rol, foco atrapado, Escape, clic en el fondo, bloqueo del
 * scroll, devolución del foco a quien lo abrió) la aporta {@link Modal}. Aquí
 * se decide solo lo propio de este contenido:
 * - El título (`<h2>`) da nombre al diálogo y la sinopsis lo describe.
 * - El foco inicial va al botón de cerrar, que es el primer elemento del
 *   diálogo y la salida más segura.
 * - Estado de favorito y "Ver ahora" son componentes compartidos
 *   ({@link FavoriteButton}, {@link WatchButton}): el modal no hace peticiones propias.
 * - En móvil casi ocupa la pantalla y los botones se apilan.
 *
 * @param props La película a mostrar y la función para cerrar el modal.
 */
export function MovieDetailsModal({ movie, onClose }: Props) {
  const titleId = useId();
  const descriptionId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);

  return (
    <Modal
      onClose={onClose}
      labelledBy={titleId}
      describedBy={descriptionId}
      initialFocusRef={closeRef}
      className="max-w-2xl"
    >
      <button
        ref={closeRef}
        type="button"
        onClick={onClose}
        aria-label={`Cerrar detalles de ${movie.title}`}
        className="focus-ring absolute top-3 right-3 z-20 inline-flex size-11 items-center justify-center rounded-full border border-line bg-canvas/80 text-white transition-colors hover:bg-canvas"
      >
        <X aria-hidden="true" className="size-5" />
      </button>

      {/* El título ya está escrito debajo: la imagen es decorativa (alt vacío). */}
      <div className="relative flex min-h-56 items-end sm:min-h-72">
        <MoviePoster title={movie.title} src={movie.imageUrl} className="absolute inset-0" imgClassName="object-top" />
        <div aria-hidden="true" className="absolute inset-0 bg-linear-to-t from-surface via-surface/25 to-transparent" />

        <div className="relative w-full px-5 pt-16 pb-4 sm:px-6">
          <p className="mb-1 text-[11px] font-semibold tracking-widest text-accent uppercase">Película</p>
          <h2 id={titleId} className="mb-2 text-2xl leading-tight font-black text-balance sm:text-3xl">
            {movie.title}
          </h2>
          <MovieMetaTags movie={movie} />
        </div>
      </div>

      <div className="p-5 sm:p-6">
        <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:flex-wrap">
          <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
          <FavoriteButton movie={movie} />
        </div>
        <div className="rounded-xl bg-canvas/60 p-4">
          <h3 className="mb-2 text-[11px] font-semibold tracking-widest text-muted uppercase">Sinopsis</h3>
          <p id={descriptionId} className="text-sm leading-relaxed text-gray-300">
            {movie.description || 'Sin descripción disponible.'}
          </p>
        </div>
      </div>
    </Modal>
  );
}
