import { useId, useState } from 'react';
import type { ReactNode } from 'react';
import { MEDIUM_SCREEN_QUERY, useMediaQuery } from '../hooks/useMediaQuery';
import {
  BANNER_BACKDROP_MIN_HEIGHT_CLASS,
  BANNER_GRID_CLASS,
  BANNER_POSTER_SIZE_CLASS,
  DETAIL_BACKDROP_MIN_HEIGHT_CLASS,
  DETAIL_BACK_ROW_CLASS,
  DETAIL_BANNER_GRID_CLASS,
  DETAIL_POSTER_SIZE_CLASS,
} from './bannerGridStyles';
import { MoviePoster } from './MoviePoster';

/** Propiedades de {@link FeaturedBanner}. */
interface Props {
  title: string;
  /**
   * Portada (2:3) del título. Sin imagen de fondo (y siempre en móvil) se usa
   * dos veces: fondo desenfocado y póster nítido.
   */
  imageUrl: string;
  /**
   * Imagen HORIZONTAL opcional del título (`backdropUrl` de la API). Desde
   * `md`, si existe y carga, es el fondo nítido del banner y no hay póster; ver
   * «Imagen de fondo nítida» en {@link FeaturedBanner}.
   */
  backdropUrl?: string | null;
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
   * Texto breve bajo las acciones (p. ej. `NothingToWatchNote` cuando no hay
   * vídeo ni tráiler). Va fuera de la rejilla de acciones para no contar como
   * una más (ver «Acciones» en {@link FeaturedBanner}).
   */
  actionsNote?: ReactNode;
  /**
   * `banner` (por defecto): pieza de la portada o de `/series`, alineada a la
   * izquierda con las filas de tarjetas que tiene debajo. `page`: cabecera de
   * la página de un título, con el contenido centrado en un ancho máximo
   * (`DETAIL_CONTAINER_CLASS`) y un póster algo mayor; ver la nota «Página de
   * un título» de {@link FeaturedBanner}.
   */
  layout?: 'banner' | 'page';
  /**
   * Solo con `layout="page"`: enlace de vuelta a la sección (un `BackLink`),
   * en su propia fila encima del póster y del título, dentro del banner para
   * que el fondo desenfocado siga llegando hasta la barra superior. Las dos
   * páginas de un título lo pasan: la rejilla de la página cuenta con esa fila
   * y lleva menos relleno arriba (ver `DETAIL_BANNER_GRID_CLASS`).
   */
  backLink?: ReactNode;
}

/**
 * Banner destacado de un título del catálogo: fondo desenfocado, póster nítido,
 * título, datos, sinopsis y acciones. Lo usan el banner de la portada
 * (`HeroBanner`, la película más reciente), el de `/series` (`SeriesHeroBanner`)
 * y la cabecera de la página de una película y de una serie. Antes vivía dentro de `HeroBanner` y
 * solo servía para películas; se extrajo para que los tres lugares compartan
 * diseño sin duplicar marcado. Quien lo usa decide QUÉ datos y acciones van.
 *
 * **Por qué fondo desenfocado + póster nítido.** Muchos títulos solo tienen
 * `imageUrl`, que es un póster VERTICAL (2:3), y el banner es HORIZONTAL. Con
 * `object-cover` a todo el ancho solo se veía una franja ampliada del póster
 * (y a 1280 px o más, borrosa: la portada mide unos 600 px). Por eso:
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
 * desenfocado sigue de borde a borde. Encima del póster, en su propia fila,
 * va el enlace de vuelta a la sección (`backLink`).
 *
 * **Acciones.** Cuántas hay depende del título y del rol: «Ver ahora» y «Ver
 * tráiler» solo existen si hay vídeo o tráiler, y «Editar» solo para un
 * administrador, así que pueden ser de una a cinco (en la página de un título,
 * con «Mi lista» y «Me gusta»: a 375 px quedan de dos en dos y la última, sola a
 * todo el ancho). Dos reglas:
 * - **Ningún texto se parte en dos líneas** (`min-width: max-content` en cada
 *   acción). Antes, a 375 px, «Más información» no cabía en media fila y se
 *   partía (incidencia QA trailer-7), y su vecino se estiraba a la misma altura.
 * - **En móvil, de dos en dos o a todo el ancho, nunca con un hueco.** Es una
 *   fila flexible que se parte (`flex-wrap`): cada acción parte de media fila
 *   (`basis`) y crece para llenar la suya (`grow`). Si dos caben, quedan a
 *   partes iguales; si una no cabe en media fila (su texto es más largo) o se
 *   queda sola al final, baja a su propia fila y la ocupa entera. Así lo
 *   resuelve el CSS según lo que mide cada texto, y no cada página contando
 *   botones (antes era una rejilla de dos columnas con
 *   `:last-child:nth-child(odd)`, que no sabía de textos largos). A 375 px,
 *   con las cuatro de la portada: «Ver ahora | Ver tráiler», «Mi lista» y
 *   «Más información», cada una en su fila; desde ~400 px, de dos en dos.
 * La regla depende del ancho de la COLUMNA de acciones (consulta de contenedor,
 * por debajo de 36rem), no del de la ventana: a 768 px el póster va al lado y la
 * columna mide lo mismo que un móvil; con la regla atada a `sm`, las cuatro
 * acciones de una película dejaban «Me gusta» sola en una segunda fila (D-12).
 * Ahora quedan en 2 × 2 a partes iguales. Con la columna más ancha (de ~600 px
 * en adelante), todas en línea con su ancho natural. Quien pasa las acciones no
 * debe meter entre ellas elementos que no se ven (un `<dialog>`, un texto
 * oculto): ocuparían sitio en la fila (el diálogo del tráiler va en un
 * *portal*, ver `TrailerButton`).
 * Para un texto que acompañe a las acciones está `actionsNote`, debajo y aparte.
 *
 * **Imágenes.** Fondo y póster usan la MISMA URL: el navegador la descarga una
 * sola vez. Las dos llevan `priority` porque es la imagen principal de la
 * página (LCP). Ambas son decorativas (`alt` vacío): el título está escrito al
 * lado. Si la imagen no existe o falla, {@link MoviePoster} pinta su respaldo:
 * el fondo queda como el degradado propio del título y la tarjeta como el
 * hueco con su título.
 *
 * **Imagen de fondo nítida (`backdropUrl`, desde `md`).** Si el título tiene
 * imagen horizontal, en tableta y escritorio es el fondo, a sangre, SIN
 * desenfoque ni zoom, y el banner no lleva póster:
 * - **Sin póster**, en la portada y en la página del título: la imagen de
 *   fondo ya es la imagen del título, y un cartel delante repetiría el mismo
 *   título dos veces y taparía justo la parte nítida. Además, serían dos
 *   imágenes DISTINTAS compitiendo por la prioridad alta de la carga (LCP);
 *   así hay una sola. No basta con ocultarlo con CSS: una `<img>` con
 *   `display: none` se descarga igual, por eso decide {@link useMediaQuery}.
 * - **Mismo alto que con póster** (`*_BACKDROP_MIN_HEIGHT_CLASS`): el
 *   esqueleto de carga no sabe si habrá imagen de fondo, y así nada salta.
 *   El alto mínimo va en la COLUMNA de texto, que ocupa el sitio del póster,
 *   y no en la rejilla: la rejilla lleva el relleno vertical y, con
 *   `box-sizing: border-box`, su `min-height` lo incluiría; el banner quedaba
 *   ~6rem más bajo que con póster (lo vio la revisión en el navegador). El
 *   texto queda centrado en vertical (`justify-center`), donde estaba.
 * - **La columna de texto, como mucho `max-w-2xl`** también en la página de
 *   un título: el velo lateral (abajo) solo garantiza el contraste en la
 *   parte izquierda del banner.
 * - **En móvil (< `md`), la composición de siempre** (portada desenfocada y
 *   póster pequeño junto al título), y la imagen de fondo ni se descarga. A
 *   375 px el banner es más alto que ancho: una imagen 16:9 que lo cubra solo
 *   enseña una franja central de un 40 % de su ancho, ampliada unas 2,5 veces
 *   (volvería a verse borrosa en una pantalla de alta densidad) y casi toda
 *   bajo el velo del texto; el póster pequeño identifica mejor el título y
 *   ahorra una descarga de ~100 KB con datos móviles.
 * - **Si la imagen de fondo falla**, el banner vuelve a la composición de
 *   siempre (portada desenfocada + póster), nunca a un fondo liso sin imagen.
 */
export function FeaturedBanner({
  title,
  imageUrl,
  headingLevel = 2,
  meta,
  description,
  clampDescription = true,
  actions,
  actionsNote,
  layout = 'banner',
  backLink,
  backdropUrl,
}: Props) {
  const titleId = useId();
  const Heading = headingLevel === 1 ? 'h1' : 'h2';
  const isPage = layout === 'page';
  const wideScreen = useMediaQuery(MEDIUM_SCREEN_QUERY);
  // Se recuerda QUÉ imagen de fondo falló (como en MoviePoster): si el título cambia de imagen, se vuelve a intentar.
  const [failedBackdrop, setFailedBackdrop] = useState<string | null>(null);
  const sharpBackdrop = wideScreen && backdropUrl && failedBackdrop !== backdropUrl ? backdropUrl : null;

  const gridClass = isPage ? DETAIL_BANNER_GRID_CLASS : BANNER_GRID_CLASS;
  const backdropMinHeight = isPage ? DETAIL_BACKDROP_MIN_HEIGHT_CLASS : BANNER_BACKDROP_MIN_HEIGHT_CLASS;
  // Con fondo nítido, la columna nunca pasa de `max-w-2xl` (ver «Imagen de fondo nítida»: el velo lateral) y ocupa
  // el alto del póster que sustituye, con su contenido centrado en vertical como estaba junto a él.
  const columnClass = sharpBackdrop
    ? `md:w-full md:max-w-2xl md:justify-center ${backdropMinHeight}`
    : isPage
      ? 'md:flex-1'
      : 'md:w-full md:max-w-2xl';

  return (
    <section aria-labelledby={titleId} className="relative isolate overflow-hidden">
      {/* Capa de fondo: decorativa entera (imagen y degradados). */}
      <div aria-hidden="true" className="absolute inset-0 -z-10">
        {sharpBackdrop ? (
          <>
            <MoviePoster
              key="nitido"
              title={title}
              src={sharpBackdrop}
              priority
              backdrop
              wide
              onLoadError={setFailedBackdrop}
              className="size-full"
            />
            {/*
              Velos del fondo nítido. Regla: detrás del texto, el fondo nunca es más claro que en el modo desenfocado
              en su peor caso, así que todos los contrastes calculados para él siguen valiendo o mejoran. Peor caso: la
              zona de la imagen bajo el texto es blanca pura (sin `brightness`, 255). Con un velo uniforme al 30 % y el
              lateral al 60 %, la imagen queda tapada al 1 − 0,7 × 0,4 = 72 % (gris ≈ 82, frente a ≈ 85 del desenfocado):
              título blanco 7.6:1, sinopsis gray-200 6.2:1, «Más información» (white/85) 6.1:1, white/75 (progreso)
              5.2:1 y género (white/90 sobre white/12) 4.9:1, todo ≥ 4.5:1 (WCAG AA). Calculado por canal sobre canvas
              #0e1117; con el mismo método, el modo desenfocado da 7.3 / 5.9 / 5.8 / 5.0 / 4.7.
              - `md` (768–1023 px): la columna de texto ocupa casi todo el ancho, así que el 72 % va de lado a lado
                (`bg-canvas/72`): la imagen se ve igual de oscura que la desenfocada de hoy, pero nítida.
              - Desde `lg`: velo uniforme al 30 % y otro lateral al 60 % que se mantiene hasta donde puede llegar el
                texto y se desvanece hacia la derecha, donde la imagen se ve casi limpia. Hasta dónde llega el texto
                (columna de 42rem con 1,5rem de margen): en la portada, como mucho el 68 % del ancho a 1024 px y el
                54 % desde 1280 px; en la página de un título (centrada en 72rem), el 68 % a 1024 px y, desde 1280 px,
                el 59 % (50 % + 120 px). Por eso el velo lateral llega entero al 70 % en `lg` y al 65 % desde `xl`.
              Si cambian el ancho de la columna, el contenedor o estos velos, hay que rehacer esta cuenta.
            */}
            <div className="absolute inset-0 bg-canvas/72 lg:bg-canvas/30" />
            <div className="absolute inset-0 hidden bg-linear-to-r from-canvas/60 via-canvas/60 via-70% to-transparent lg:block xl:via-65%" />
          </>
        ) : (
          <>
            <MoviePoster
              key="desenfocado"
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
          </>
        )}
        {/* El borde inferior se funde con la página: el banner no parece una caja pegada encima. */}
        <div className="absolute inset-0 bg-linear-to-t from-canvas via-canvas/40 via-30% to-transparent" />
      </div>

      {isPage && backLink && <div className={DETAIL_BACK_ROW_CLASS}>{backLink}</div>}

      <div className={gridClass}>
        {/* Con fondo nítido no hay póster (ver «Imagen de fondo nítida»): ni se monta, para no descargarlo. */}
        {!sharpBackdrop && (
          <MoviePoster
            title={title}
            src={imageUrl}
            priority
            className={`aspect-2/3 w-full shrink-0 rounded-lg shadow-2xl shadow-black/60 ring-1 ring-white/15 md:rounded-xl ${isPage ? DETAIL_POSTER_SIZE_CLASS : BANNER_POSTER_SIZE_CLASS}`}
          />
        )}

        {/* Móvil: `contents` (cada hijo es una celda de la rejilla). Desde md: columna junto al póster. */}
        <div className={`contents md:flex md:min-w-0 md:flex-col ${columnClass}`}>
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

          {/* Ver «Acciones»: nunca se parte un texto (`*:min-w-max`); en una columna estrecha (móvil, y la tableta con el
              póster al lado), de dos en dos o a todo el ancho (`grow` + `basis` de media fila), según el ancho de la
              propia columna (`@container`). */}
          <div className="@container col-span-2 flex flex-wrap gap-3 *:min-w-max @max-[36rem]:*:grow @max-[36rem]:*:basis-[calc(50%-0.375rem)] md:mt-6">
            {actions}
          </div>

          {/* En móvil la rejilla ya separa 1,25 rem: `-mt-2` lo acerca a los botones, de los que es una aclaración. */}
          {actionsNote && <div className="col-span-2 -mt-2 md:mt-3">{actionsNote}</div>}
        </div>
      </div>
    </section>
  );
}
