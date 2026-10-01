import { useId } from 'react';
import { Info } from 'lucide-react';
import type { Movie } from '../lib/types';
import { Button } from './Button';
import { FavoriteButton } from './FavoriteButton';
import { MovieMetaTags } from './MovieMetaTags';
import { MoviePoster } from './MoviePoster';
import { WatchButton } from './WatchButton';

/**
 * Propiedades del componente HeroBanner.
 */
interface Props {
  movie: Movie;
  /** Abre el modal de detalles de la película. */
  onDetails: (movie: Movie) => void;
}

/**
 * Banner principal destacado: la película más reciente del catálogo.
 *
 * Incluye póster, título, metadatos y tres acciones: "Ver ahora" (enlace al
 * vídeo, ver {@link WatchButton}), "Mi lista" ({@link FavoriteButton}) y
 * "Más información" (modal de detalles). El título es un `<h2>`: el `<h1>` de
 * la página lo pone `HomePage`.
 *
 * **Responsive.** No tiene una altura fija: tiene una altura mínima que crece
 * con el contenido (el texto manda, no una caja de 460 px que lo recorte en un
 * móvil), la tipografía escala por tamaño de pantalla y los botones se apilan
 * a todo el ancho en móvil.
 *
 * **Imagen.** Es la más grande de la página (LCP), así que se carga con
 * `priority` (inmediata y con prioridad alta). Es decorativa (`alt` vacío):
 * el título ya está escrito al lado.
 *
 * @param props la película a destacar y el manejador para abrir sus detalles
 */
export function HeroBanner({ movie, onDetails }: Props) {
  const titleId = useId();

  return (
    <section aria-labelledby={titleId} className="px-4 pt-4 pb-5 sm:px-6">
      <div className="relative flex min-h-96 items-end overflow-hidden rounded-2xl sm:min-h-[26rem] lg:min-h-[28.75rem]">
        <MoviePoster
          title={movie.title}
          src={movie.imageUrl}
          priority
          className="absolute inset-0"
          imgClassName="object-[50%_25%]"
        />

        {/* Degradados para que el texto sea legible sobre cualquier imagen */}
        <div aria-hidden="true" className="absolute inset-0 bg-linear-to-r from-canvas/90 via-canvas/60 to-transparent to-75%" />
        <div aria-hidden="true" className="absolute inset-0 bg-linear-to-t from-canvas/85 to-transparent to-60%" />

        <div className="relative flex max-w-xl flex-col px-5 pt-24 pb-6 sm:px-8 sm:pb-8">
          <p className="mb-2 text-[11px] font-bold tracking-[0.12em] text-accent uppercase">Estreno reciente</p>

          <h2 id={titleId} className="mb-3 text-3xl leading-[1.1] font-black text-balance text-white sm:text-4xl md:text-5xl">
            {movie.title}
          </h2>

          <div className="mb-5">
            <MovieMetaTags movie={movie} />
          </div>

          <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap">
            <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
            <FavoriteButton movie={movie} />
            <Button variant="outline" onClick={() => onDetails(movie)} aria-haspopup="dialog">
              <Info aria-hidden="true" className="size-4" />
              Más información
              <span className="sr-only"> sobre {movie.title}</span>
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
