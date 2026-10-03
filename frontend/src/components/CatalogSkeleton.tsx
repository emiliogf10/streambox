/** Propiedades de {@link CatalogSkeleton}. */
interface CatalogSkeletonProps {
  /** Texto que se anuncia a los lectores de pantalla (y que buscan los tests). */
  label?: string;
}

/** Tarjetas de la fila de muestra: las suficientes para llenar 1280 px. */
const CARD_COUNT = 7;

/**
 * Esqueleto de la portada mientras llega el catálogo: la silueta del banner
 * (póster + título, datos y botones) y de una fila de tarjetas.
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
export function CatalogSkeleton({ label = 'Cargando catálogo...' }: CatalogSkeletonProps) {
  const block = 'rounded-lg bg-white/6';
  return (
    <div role="status">
      <span className="sr-only">{label}</span>
      <div aria-hidden="true" className="animate-pulse motion-reduce:animate-none">
        <div className="grid grid-cols-[6rem_minmax(0,1fr)] items-center gap-x-4 gap-y-5 px-4 pt-6 pb-8 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:gap-x-6 sm:px-6 md:flex md:gap-10 md:pt-10 md:pb-12 lg:gap-12">
          <div className={`${block} aspect-2/3 w-full md:w-48 md:rounded-xl lg:w-56`} />
          <div className="contents md:flex md:w-full md:max-w-2xl md:flex-col md:gap-4">
            <div className={`${block} h-9 w-4/5 md:h-12`} />
            <div className={`${block} col-span-2 h-5 w-2/3`} />
            <div className="col-span-2 flex flex-col gap-2">
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

        <div className="px-4 sm:px-6">
          <div className={`${block} mb-3 h-6 w-32`} />
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
      </div>
    </div>
  );
}
