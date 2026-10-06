import { useId } from 'react';
import type { ReactNode } from 'react';
import { MoviePoster } from './MoviePoster';

/** Propiedades de {@link FeaturedBanner}. */
interface Props {
  title: string;
  /** Portada (2:3) del título; se usa dos veces: fondo desenfocado y póster nítido. */
  imageUrl: string;
  /**
   * Nivel del título. `2` (por defecto) cuando el banner es una pieza más de una
   * página que ya tiene su `<h1>` (portada, `/series`); `1` cuando el banner ES
   * la cabecera de la página (detalle de una serie).
   */
  headingLevel?: 1 | 2;
  /** Etiqueta de acento junto a los datos ("Estreno reciente", "Novedad"); opcional. */
  badge?: string;
  /** Datos del título (`MovieMetaTags`, `SeriesMetaTags`). */
  meta: ReactNode;
  description: string;
  /**
   * `true` (por defecto): sinopsis recortada a 3 líneas, porque la completa está
   * en otro sitio ("Más información", la página de la serie). `false` en la
   * página de detalle, donde ya no hay otro sitio al que mandarla.
   */
  clampDescription?: boolean;
  /** Botones y enlaces de acción, de más a menos importante. */
  actions: ReactNode;
}

/**
 * Banner destacado de un título del catálogo: fondo desenfocado, póster nítido,
 * título, datos, sinopsis y acciones. Lo usan el banner de la portada
 * (`HeroBanner`, la película más reciente), el de `/series` (`SeriesHeroBanner`)
 * y la cabecera de la página de una serie. Antes vivía dentro de `HeroBanner` y
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
 * composición que el modal de detalles, de modo que la portada y el detalle se
 * reconocen como una sola pieza del sistema. El espacio libre que queda a la
 * derecha lo ocupa el fondo desenfocado: es atmósfera, no un hueco.
 *
 * **Móvil.** Póster pequeño entero a la izquierda del título y el resto (datos,
 * sinopsis y acciones) a todo el ancho debajo. Para que el póster quede junto
 * al título en móvil y junto a TODA la columna en escritorio sin duplicar
 * marcado, la columna de texto usa `display: contents` en móvil (sus hijos
 * pasan a ser celdas de la rejilla exterior) y `flex` desde `md`.
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
  badge,
  meta,
  description,
  clampDescription = true,
  actions,
}: Props) {
  const titleId = useId();
  const Heading = headingLevel === 1 ? 'h1' : 'h2';

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

      <div className="grid grid-cols-[6rem_minmax(0,1fr)] items-center gap-x-4 gap-y-5 px-4 pt-6 pb-8 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:gap-x-6 sm:px-6 md:flex md:gap-10 md:pt-10 md:pb-12 lg:gap-12">
        <MoviePoster
          title={title}
          src={imageUrl}
          priority
          className="aspect-2/3 w-full shrink-0 rounded-lg shadow-2xl shadow-black/60 ring-1 ring-white/15 md:w-48 md:rounded-xl lg:w-56"
        />

        {/* Móvil: `contents` (cada hijo es una celda de la rejilla). Desde md: columna junto al póster. */}
        <div className="contents md:flex md:max-w-2xl md:min-w-0 md:flex-col">
          <Heading
            id={titleId}
            className="text-[1.75rem] leading-[1.1] font-extrabold tracking-tight text-balance break-words text-white sm:text-4xl md:mb-4 lg:text-5xl"
          >
            {title}
          </Heading>

          {/* La etiqueta es un dato (por qué está destacado), así que va con los demás datos y no como antetítulo. */}
          <div className="col-span-2 flex flex-wrap items-center gap-x-3 gap-y-2">
            {badge && <span className="rounded-md bg-accent px-2 py-0.5 text-xs font-bold text-black">{badge}</span>}
            {meta}
          </div>

          {description && (
            <p
              className={`col-span-2 max-w-[60ch] text-sm leading-relaxed text-pretty text-gray-200 md:mt-4 md:text-base ${clampDescription ? 'line-clamp-3' : ''}`}
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
