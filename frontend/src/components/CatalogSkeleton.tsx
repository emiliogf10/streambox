import {
  BANNER_GRID_CLASS,
  BANNER_POSTER_SIZE_CLASS,
  DETAIL_BACK_ROW_CLASS,
  DETAIL_BANNER_GRID_CLASS,
  DETAIL_CONTAINER_CLASS,
  DETAIL_POSTER_SIZE_CLASS,
  MOVIE_FACTS_CLASS,
  MOVIE_FACTS_LIST_CLASS,
  SERIES_BODY_GRID_CLASS,
  SERIES_FACTS_CLASS,
  SERIES_FACTS_LIST_CLASS,
} from './bannerGridStyles';
import { POSTER_GRID_CLASS } from './posterGridStyles';

/** Propiedades de {@link CatalogSkeleton}. */
interface CatalogSkeletonProps {
  /** Texto que se anuncia a los lectores de pantalla (y que buscan los tests). */
  label?: string;
  /** `true` (por defecto): banner y una fila de tarjetas, como la portada. `false`: solo el banner. */
  withRow?: boolean;
}

/** Tarjetas de la fila de muestra: las suficientes para llenar 1280 px. */
const CARD_COUNT = 7;

/**
 * Esqueleto de la portada mientras llega el catálogo: la silueta del banner
 * (póster + título, datos y botones) y de una fila de tarjetas. La página de
 * un título tiene el suyo, {@link DetailSkeleton}.
 *
 * **Por qué un esqueleto y no un spinner.** El spinner centrado decía "espera"
 * pero no "qué": al llegar los datos toda la pantalla cambiaba de golpe. Con
 * la silueta de lo que viene, el usuario ya ve dónde estará cada cosa y el
 * cambio al contenido real es mínimo (se percibe como más rápido aunque tarde
 * lo mismo). Las medidas son las del banner y las tarjetas reales.
 *
 * **Accesibilidad.** Las formas son decorativas (`aria-hidden`); lo que se
 * anuncia es el texto de `role="status"`, oculto visualmente. El pulso
 * (`animate-pulse`) se quita con `prefers-reduced-motion`.
 */
export function CatalogSkeleton({ label = 'Cargando catálogo...', withRow = true }: CatalogSkeletonProps) {
  return (
    <div role="status">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="animate-pulse motion-reduce:animate-none">
        <BannerShapes page={false} />
        {withRow && <RowShapes />}
      </div>
    </div>
  );
}

/**
 * Siluetas del banner destacado con las medidas de `FeaturedBanner`: las del
 * banner de la portada o, con `page`, las de la cabecera centrada de la página
 * de un título (`layout="page"`), con la fila de su enlace de vuelta encima.
 */
function BannerShapes({ page }: { page: boolean }) {
  const block = 'rounded-lg bg-white/6';
  return (
    <>
      {/* La fila del enlace de vuelta de la página real («Volver a series»), con su misma altura (44 px). */}
      {page && (
        <div className={DETAIL_BACK_ROW_CLASS}>
          <div className="flex min-h-11 items-center">
            <div className={`${block} h-4 w-32`} />
          </div>
        </div>
      )}
      <div className={page ? DETAIL_BANNER_GRID_CLASS : BANNER_GRID_CLASS}>
        <div
          className={`${block} aspect-2/3 w-full md:rounded-xl ${page ? DETAIL_POSTER_SIZE_CLASS : BANNER_POSTER_SIZE_CLASS}`}
        />
        <div className={`contents md:flex md:w-full md:flex-col md:gap-4 ${page ? 'md:flex-1' : 'md:max-w-2xl'}`}>
          <div className={`${block} h-9 w-4/5 md:h-12 ${page ? 'md:max-w-xl' : ''}`} />
          <div className={`${block} col-span-2 h-5 w-2/3 ${page ? 'md:max-w-sm' : ''}`} />
          <div className={`col-span-2 flex flex-col gap-2 ${page ? 'md:max-w-[65ch]' : ''}`}>
            <div className={`${block} h-4 w-full`} />
            <div className={`${block} h-4 w-11/12`} />
            <div className={`${block} h-4 w-3/5`} />
          </div>
          <div className="col-span-2 grid grid-cols-2 gap-3 sm:flex">
            <div className={`${block} h-11 sm:w-32`} />
            <div className={`${block} h-11 sm:w-32`} />
          </div>
        </div>
      </div>
    </>
  );
}

/** Propiedades de {@link DetailSkeleton}. */
interface DetailSkeletonProps {
  /** Texto que se anuncia a los lectores de pantalla (y que buscan los tests). */
  label: string;
  /** `movie`: banner + banda de la ficha. `series`: banner + episodios con la ficha al lado (desde `lg`). */
  kind: 'movie' | 'series';
}

/**
 * Esqueleto de la página de un título (`/peliculas/:id`, `/series/:id`), con
 * su maquetación: la cabecera centrada y, debajo, la ficha en banda (película)
 * o los episodios con la ficha en una columna lateral (serie). Mismas razones y
 * mismo tratamiento accesible que {@link CatalogSkeleton}: si el esqueleto no
 * midiera lo mismo que la página, al llegar los datos todo saltaría.
 */
export function DetailSkeleton({ label, kind }: DetailSkeletonProps) {
  const block = 'rounded-lg bg-white/6';
  const facts = (
    <div className={kind === 'movie' ? MOVIE_FACTS_CLASS : SERIES_FACTS_CLASS}>
      <div className={`${block} mb-4 h-6 w-20`} />
      <div className={kind === 'movie' ? MOVIE_FACTS_LIST_CLASS : SERIES_FACTS_LIST_CLASS}>
        {[0, 1, 2, 3].map((index) => (
          <div key={index} className="flex flex-col gap-2">
            <div className={`${block} h-3 w-16`} />
            <div className={`${block} h-5 w-24`} />
          </div>
        ))}
      </div>
    </div>
  );

  return (
    <div role="status">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="animate-pulse motion-reduce:animate-none">
        <BannerShapes page />
        <div className={`${DETAIL_CONTAINER_CLASS} px-4 sm:px-6`}>
          {kind === 'movie' ? (
            facts
          ) : (
            <div className={SERIES_BODY_GRID_CLASS}>
              <div>
                <div className={`${block} mb-4 h-6 w-28`} />
                <div className="mb-4 flex gap-2">
                  <div className="h-11 w-32 rounded-full bg-white/6" />
                  <div className="h-11 w-32 rounded-full bg-white/6" />
                </div>
                {[0, 1, 2].map((index) => (
                  <div key={index} className="flex items-start justify-between gap-6 border-b border-line py-5">
                    <div className="flex w-full flex-col gap-2">
                      <div className={`${block} h-5 w-1/2`} />
                      <div className={`${block} h-4 w-4/5`} />
                    </div>
                    <div className={`${block} h-11 w-20 shrink-0`} />
                  </div>
                ))}
              </div>
              {facts}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/** Siluetas de una fila (título + tarjetas), con las medidas de `PosterRow` y `PosterCard`. */
function RowShapes() {
  const block = 'rounded-lg bg-white/6';
  return (
    <div className="px-4 sm:px-6">
      {/* `md:min-h-10`: la cabecera de `PosterRow` reserva desde `md` la altura de sus flechas; el esqueleto también, para no saltar. */}
      <div className="mb-3 flex items-center md:min-h-10">
        <div className={`${block} h-6 w-32`} />
      </div>
      <div className="flex gap-3 overflow-hidden sm:gap-4">
        {Array.from({ length: CARD_COUNT }, (_, index) => (
          <div key={index} className="w-36 shrink-0 sm:w-44">
            <div className={`${block} mb-2 aspect-2/3 w-full rounded-xl`} />
            <div className={`${block} mb-1.5 h-3.5 w-4/5`} />
            <div className={`${block} h-3 w-1/2`} />
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * Esqueleto de UNA fila que llega por separado del resto de la página (la fila
 * «Series» de la portada). Reserva su hueco mientras carga para que, al llegar,
 * las filas de debajo no salten. Mismo tratamiento accesible que
 * {@link CatalogSkeleton}: formas ocultas y un texto anunciado.
 */
export function RowSkeleton({ label }: { label: string }) {
  return (
    <div role="status" className="mb-9 sm:mb-10">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="animate-pulse motion-reduce:animate-none">
        <RowShapes />
      </div>
    </div>
  );
}

/** Tarjetas del esqueleto de la cuadrícula: dos filas a 1280 px, cinco a 375 px. */
const GRID_CARD_COUNT = 10;

/**
 * Esqueleto de una cuadrícula de resultados (los de `/peliculas` con filtros):
 * siluetas de tarjetas con la MISMA rejilla que las reales
 * ({@link POSTER_GRID_CLASS}), para que al llegar los datos nada cambie de sitio.
 * Mismo tratamiento accesible que {@link CatalogSkeleton}.
 */
export function GridSkeleton({ label }: { label: string }) {
  const block = 'rounded-lg bg-white/6';
  return (
    <div role="status">
      <span className="sr-only">{label}</span>
      <ul aria-hidden="true" className={`${POSTER_GRID_CLASS} animate-pulse motion-reduce:animate-none`}>
        {Array.from({ length: GRID_CARD_COUNT }, (_, index) => (
          <li key={index}>
            <div className={`${block} mb-2 aspect-2/3 w-full rounded-xl`} />
            <div className={`${block} mb-1.5 h-3.5 w-4/5`} />
            <div className={`${block} h-3 w-1/2`} />
          </li>
        ))}
      </ul>
    </div>
  );
}
