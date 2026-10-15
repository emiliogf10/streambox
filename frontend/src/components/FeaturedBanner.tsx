import { useId } from 'react';
import type { ReactNode } from 'react';
import {
  BANNER_GRID_CLASS,
  BANNER_POSTER_SIZE_CLASS,
  DETAIL_BANNER_GRID_CLASS,
  DETAIL_POSTER_SIZE_CLASS,
} from './bannerGridStyles';
import { MoviePoster } from './MoviePoster';

/** Propiedades de {@link FeaturedBanner}. */
interface Props {
  title: string;
  /** Portada (2:3) del título; se usa dos veces: fondo desenfocado y póster nítido. */
  imageUrl: string;
  /**
   * Nivel del título. `2` (por defecto) cuando el banner es una pieza más de una
   * página que ya tiene su `<h1>` (portada, `/series`); `1` cuando el banner ES
   * la cabecera de la página (detalle de una película o de una serie).
   */
  headingLevel?: 1 | 2;
  /** Datos del título (`MovieMetaTags`, `SeriesMetaTags`); la etiqueta de acento ("Estreno reciente") va dentro, con su `badge`. */
  meta: ReactNode;
  description: string;
  /**
   * `true` (por defecto): sinopsis recortada a 3 líneas, porque la completa está
   * en otro sitio (la página de la película o de la serie). `false` en la
   * página de detalle, donde ya no hay otro sitio al que mandarla.
   */
  clampDescription?: boolean;
  /** Botones y enlaces de acción, de más a menos importante. */
  actions: ReactNode;
  /**
   * `banner` (por defecto): pieza de la portada o de `/series`, alineada a la
   * izquierda con las filas de tarjetas que tiene debajo. `page`: cabecera de
   * la página de un título, con el contenido centrado en un ancho máximo
   * (`DETAIL_CONTAINER_CLASS`) y un póster algo mayor; ver la nota «Página de
   * un título» de {@link FeaturedBanner}.
   */
  layout?: 'banner' | 'page';
}

/**
 * Banner destacado de un título del catálogo: fondo desenfocado, póster nítido,
 * título, datos, sinopsis y acciones. Lo usan el banner de la portada
 * (`HeroBanner`, la película más reciente), el de `/series` (`SeriesHeroBanner`)
 * y la cabecera de la página de una película y de una serie. Antes vivía dentro de `HeroBanner` y
 * solo servía para películas; se extrajo para que los tres lugares compartan
 * diseño sin duplicar marcado. Quien lo usa decide QUÉ datos y acciones van.
 *
 * **Por qué fondo desenfocado + póster nítido.** La API solo da `imageUrl`, que
 * es un póster VERTICAL (2:3), y el banner es HORIZONTAL. Con `object-cover` a
 * todo el ancho solo se veía una franja ampliada del póster. Por eso:
 * - **Fondo**: el mismo póster a sangre, muy desenfocado y oscurecido. Aporta
 *   el color y la atmósfera del título sin pretender que se lea; un `scale`
 *   ligero esconde el borde claro que deja el desenfoque.
 * - **Póster nítido** entero (2:3, sin recortar), como tarjeta.
 *
 * **Por qué el póster va PEGADO al título (y no al otro extremo).** Antes, en
 * escritorio, el texto iba a la izquierda y el póster al borde derecho: a
 * 1280 px quedaban ~430 px vacíos entre los dos y parecían dos cosas sin
 * relación. Por la ley de proximidad, lo que está junto se lee como un mismo
 * objeto: póster y título son el mismo título, así que van juntos. Es la misma
 * composición en la portada y en la página de cada título, de modo que se
 * reconocen como una sola pieza del sistema. El espacio libre que queda a la
 * derecha lo ocupa el fondo desenfocado: es atmósfera, no un hueco.
 *
 * **Móvil.** Póster pequeño entero a la izquierda del título y el resto (datos,
 * sinopsis y acciones) a todo el ancho debajo. Para que el póster quede junto
 * al título en móvil y junto a TODA la columna en escritorio sin duplicar
 * marcado, la columna de texto usa `display: contents` en móvil (sus hijos
 * pasan a ser celdas de la rejilla exterior) y `flex` desde `md`.
 *
 * **Página de un título (`layout="page"`).** En la portada el banner va
 * alineado a la izquierda con las filas que tiene debajo. En la página de una
 * película o de una serie no hay filas que lo acompañen, y a 1280 px el
 * contenido se quedaba en una franja a la izquierda con más de media pantalla
 * vacía. Ahí el contenido va centrado en el mismo ancho máximo que el resto de
 * la página (la ficha, los episodios) y la columna de texto ocupa todo lo que
 * deja el póster: la sinopsis sigue limitada a ~65 caracteres por línea para
 * leerse bien, pero el título largo ya no se parte antes de tiempo. El fondo
 * desenfocado sigue de borde a borde.
 *
 * **Acciones.** En móvil las dos primeras comparten fila y el resto va debajo
 * (rejilla de dos columnas); desde `sm`, en línea.
 *
 * **Imágenes.** Fondo y póster usan la MISMA URL: el navegador la descarga una
 * sola vez. Las dos llevan `priority` porque es la imagen principal de la
 * página (LCP). Ambas son decorativas (`alt` vacío): el título está escrito al
 * lado. Si la imagen no existe o falla, {@link MoviePoster} pinta su respaldo:
 * el fondo queda como el degradado propio del título y la tarjeta como el
 * hueco con su título.
 */
export function FeaturedBanner({
  title,
  imageUrl,
  headingLevel = 2,
  meta,
  description,
  clampDescription = true,
  actions,
  layout = 'banner',
}: Props) {
  const titleId = useId();
  const Heading = headingLevel === 1 ? 'h1' : 'h2';
  const isPage = layout === 'page';

  return (
    <section aria-labelledby={titleId} className="relative isolate overflow-hidden">
      {/* Capa de fondo: decorativa entera (imagen y degradados). */}
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        <MoviePoster
          title={title}
          src={imageUrl}
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

      <div className={isPage ? DETAIL_BANNER_GRID_CLASS : BANNER_GRID_CLASS}>
        <MoviePoster
          title={title}
          src={imageUrl}
          priority
          className={`aspect-2/3 w-full shrink-0 rounded-lg shadow-2xl shadow-black/60 ring-1 ring-white/15 md:rounded-xl ${isPage ? DETAIL_POSTER_SIZE_CLASS : BANNER_POSTER_SIZE_CLASS}`}
        />

        {/* Móvil: `contents` (cada hijo es una celda de la rejilla). Desde md: columna junto al póster. */}
        <div className={`contents md:flex md:min-w-0 md:flex-col ${isPage ? 'md:flex-1' : 'md:max-w-2xl'}`}>
          <Heading
            id={titleId}
            className="text-[1.75rem] leading-[1.1] font-extrabold tracking-tight text-balance break-words text-white sm:text-4xl md:mb-4 lg:text-5xl"
          >
            {title}
          </Heading>

          {/* La etiqueta de acento es un dato más de la lista `meta` (ver MetaTags): así comparte fila con el año en 375 px. */}
          <div className="col-span-2">{meta}</div>

          {description && (
            <p
              className={`col-span-2 ${isPage ? 'max-w-[65ch]' : 'max-w-[60ch]'} text-sm leading-relaxed text-pretty text-gray-200 md:mt-4 md:text-base ${clampDescription ? 'line-clamp-3' : ''}`}
            >
              {description}
            </p>
          )}

          <div className="col-span-2 grid grid-cols-2 gap-3 sm:flex sm:flex-wrap md:mt-6">{actions}</div>
        </div>
      </div>
    </section>
  );
}
