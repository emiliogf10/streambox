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
 * - **Cabecera con póster entero.** Las portadas son verticales (2:3): usadas
 *   como imagen de una cabecera horizontal se recortaban y su texto se mezclaba
 *   con el título. Ahora, igual que en {@link HeroBanner}, el póster se pinta
 *   ENTERO a la izquierda, como tarjeta, y detrás va la misma imagen muy
 *   desenfocada y oscurecida (misma URL: no hay segunda descarga). Va a la
 *   izquierda y no encima porque así el título y los datos quedan a la vista
 *   sin desplazar, incluso en 375 px (póster más pequeño). En móvil el
 *   contenido empieza por debajo del botón de cerrar (`pt-16`); desde `sm` se
 *   le reserva el hueco por la derecha (`pr-16`) para que el título no pase
 *   por debajo del botón.
 * - **Sin antetítulo "PELÍCULA" ni caja de sinopsis.** El antetítulo no
 *   informaba de nada (todo el catálogo son películas) y competía con el
 *   título. La sinopsis iba en una tarjeta dentro de otra tarjeta (el propio
 *   diálogo): ese doble marco solo añadía ruido. Ahora es texto con un
 *   encabezado normal, con el ancho de línea limitado (~65 caracteres) para
 *   que se lea cómodo.
 * - **Acciones**: en móvil "Ver ahora" y "Mi lista" comparten fila (dos
 *   columnas), como en el banner, en lugar de dos botones a todo el ancho.
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

      {/* Cabecera: fondo desenfocado (decorativo) + póster nítido junto al título. */}
      <div className="relative isolate overflow-hidden">
        <div aria-hidden="true" className="absolute inset-0 -z-10">
          <MoviePoster
            title={movie.title}
            src={movie.imageUrl}
            backdrop
            className="size-full"
            imgClassName="scale-125 blur-2xl brightness-75"
          />
          <div className="absolute inset-0 bg-linear-to-t from-surface via-surface/60 to-surface/20" />
        </div>

        <div className="flex items-end gap-4 px-5 pt-16 pb-5 sm:gap-6 sm:px-6 sm:pt-8 sm:pr-16">
          {/* El título ya está escrito al lado: el póster es decorativo (alt vacío). */}
          <MoviePoster
            title={movie.title}
            src={movie.imageUrl}
            className="aspect-2/3 w-24 shrink-0 rounded-lg shadow-xl shadow-black/50 ring-1 ring-white/15 sm:w-36"
          />
          <div className="min-w-0 flex-1">
            <h2
              id={titleId}
              className="mb-3 text-xl leading-tight font-extrabold tracking-tight text-balance break-words sm:text-3xl"
            >
              {movie.title}
            </h2>
            <MovieMetaTags movie={movie} />
          </div>
        </div>
      </div>

      <div className="px-5 pt-1 pb-6 sm:px-6">
        <div className="mb-6 grid grid-cols-2 gap-3 sm:flex sm:flex-wrap">
          <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
          <FavoriteButton movie={movie} />
        </div>
        <h3 className="mb-2 text-sm font-semibold text-white">Sinopsis</h3>
        <p id={descriptionId} className="max-w-[65ch] text-sm leading-relaxed text-pretty text-gray-300">
          {movie.description || 'Sin descripción disponible.'}
        </p>
      </div>
    </Modal>
  );
}
