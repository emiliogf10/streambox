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
 * Incluye título, la marca "Estreno reciente", metadatos, una sinopsis corta y
 * tres acciones: "Ver ahora" (enlace al vídeo, ver {@link WatchButton}), "Mi
 * lista" ({@link FavoriteButton}) y "Más información" (modal con la sinopsis
 * completa). El título es un `<h2>`: el `<h1>` de la página lo pone `HomePage`.
 *
 * **Por qué fondo desenfocado + póster nítido.** La API solo da `imageUrl`, que
 * es un póster VERTICAL (2:3), y el banner es HORIZONTAL. Con `object-cover` a
 * todo el ancho solo se veía una franja ampliada del póster. Por eso:
 * - **Fondo**: el mismo póster a sangre, muy desenfocado y oscurecido. Aporta
 *   el color y la atmósfera de la película sin pretender que se lea; un `scale`
 *   ligero esconde el borde claro que deja el desenfoque.
 * - **Póster nítido** entero (2:3, sin recortar), como tarjeta.
 *
 * **Por qué el póster va PEGADO al título (y no al otro extremo).** Antes, en
 * escritorio, el texto iba a la izquierda y el póster al borde derecho: a
 * 1280 px quedaban ~430 px vacíos entre los dos y parecían dos cosas sin
 * relación. Por la ley de proximidad, lo que está junto se lee como un mismo
 * objeto: póster y título son la misma película, así que van juntos (póster,
 * y a su lado título, datos, sinopsis y acciones). Es la misma composición que
 * el modal de detalles, de modo que la portada y el detalle se reconocen como
 * una sola pieza del sistema. El espacio libre que queda a la derecha lo ocupa
 * el fondo desenfocado: es atmósfera, no un hueco entre dos elementos.
 *
 * **Móvil.** Antes el póster era el fondo nítido detrás del texto y el
 * degradado que hacía legible el texto tapaba su mitad inferior: se veía medio
 * título del cartel. Ahora el móvil usa el mismo patrón que el modal: póster
 * pequeño entero a la izquierda del título y el resto (datos, sinopsis y
 * acciones) a todo el ancho debajo. Para que el póster quede junto al título
 * en móvil y junto a TODA la columna en escritorio sin duplicar marcado, la
 * columna de texto usa `display: contents` en móvil (sus hijos pasan a ser
 * celdas de la rejilla exterior) y `flex` desde `md`.
 *
 * **Acciones con jerarquía.** "Ver ahora" (principal, blanco), "Mi lista"
 * (secundaria, con borde) y "Más información" (terciaria, sin borde). En móvil
 * las dos primeras comparten fila y la tercera va debajo: antes eran tres
 * botones apilados a todo el ancho que ocupaban casi un tercio de la pantalla.
 *
 * **Imágenes.** Fondo y póster usan la MISMA URL: el navegador la descarga una
 * sola vez. Las dos llevan `priority` porque es la imagen principal de la
 * página (LCP). Ambas son decorativas (`alt` vacío): el título está escrito al
 * lado. Si la imagen no existe o falla, {@link MoviePoster} pinta su respaldo:
 * el fondo queda como el degradado propio de la película y la tarjeta como el
 * hueco con su título.
 *
 * @param props la película a destacar y el manejador para abrir sus detalles
 */
export function HeroBanner({ movie, onDetails }: Props) {
  const titleId = useId();

  return (
    <section aria-labelledby={titleId} className="relative isolate overflow-hidden">
      {/* Capa de fondo: decorativa entera (imagen y degradados). */}
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <MoviePoster
          title={movie.title}
          src={movie.imageUrl}
          priority
          backdrop
          className="size-full"
          imgClassName="scale-110 blur-2xl brightness-75"
        />
        {/*
          Velo uniforme: garantiza el contraste del texto sea cual sea el color del póster. Peor caso calculado
          (zona del fondo blanca pura: brightness-75 → gris 191, bajo canvas al 60 % → gris ≈85): título blanco 7.5:1,
          sinopsis gray-200 6.0:1, "Más información" (white/85) 6.0:1, género (white/90 sobre white/12) 4.8:1.
          Con el 55 % el género bajaba a 4.3:1 y no llegaba al 4.5:1 de WCAG AA.
        */}
        <div className="absolute inset-0 bg-canvas/60" />
        {/* El borde inferior se funde con la página: el banner no parece una caja pegada encima. */}
        <div className="absolute inset-0 bg-linear-to-t from-canvas via-canvas/40 via-30% to-transparent" />
      </div>

      <div className="grid grid-cols-[6rem_minmax(0,1fr)] items-center gap-x-4 gap-y-5 px-4 pt-6 pb-8 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:gap-x-6 sm:px-6 md:flex md:gap-10 md:pt-10 md:pb-12 lg:gap-12">
        <MoviePoster
          title={movie.title}
          src={movie.imageUrl}
          priority
          className="aspect-2/3 w-full shrink-0 rounded-lg shadow-2xl shadow-black/60 ring-1 ring-white/15 md:w-48 md:rounded-xl lg:w-56"
        />

        {/* Móvil: `contents` (cada hijo es una celda de la rejilla). Desde md: columna junto al póster. */}
        <div className="contents md:flex md:max-w-2xl md:min-w-0 md:flex-col">
          <h2
            id={titleId}
            className="text-[1.75rem] leading-[1.1] font-extrabold tracking-tight text-balance text-white sm:text-4xl md:mb-4 lg:text-5xl"
          >
            {movie.title}
          </h2>

          {/* "Estreno reciente" es un dato (por qué está destacada), así que va con los demás datos y no como antetítulo. */}
          <div className="col-span-2 flex flex-wrap items-center gap-x-3 gap-y-2">
            <span className="rounded-md bg-accent px-2 py-0.5 text-xs font-bold text-black">Estreno reciente</span>
            <MovieMetaTags movie={movie} />
          </div>

          {/* Sinopsis recortada a 3 líneas: la completa está en "Más información". */}
          {movie.description && (
            <p className="col-span-2 line-clamp-3 max-w-[60ch] text-sm leading-relaxed text-gray-200 md:mt-4 md:text-base">
              {movie.description}
            </p>
          )}

          <div className="col-span-2 grid grid-cols-2 gap-3 sm:flex sm:flex-wrap md:mt-6">
            <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
            <FavoriteButton movie={movie} />
            <Button
              variant="ghost"
              onClick={() => onDetails(movie)}
              aria-haspopup="dialog"
              className="col-span-2 sm:col-span-1"
            >
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
