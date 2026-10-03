import { useState } from 'react';
import { Film } from 'lucide-react';
import { posterFallbackGradient } from '../lib/posterFallback';

/** Propiedades de {@link MoviePoster}. */
interface MoviePosterProps {
  /** Título de la película: texto del hueco si la imagen no carga. */
  title: string;
  /** `imageUrl` de la API. Puede venir vacía o apuntar a algo que no existe. */
  src: string | null | undefined;
  /**
   * Texto alternativo. Pasa `''` (por defecto) cuando el título ya está escrito
   * junto a la imagen —tarjeta, banner, resultado de búsqueda—: así un lector de
   * pantalla no lo repite. Pasa un texto descriptivo si la imagen va sola.
   */
  alt?: string;
  /**
   * `true` solo para la imagen principal de la página (el banner): se descarga
   * ya y con prioridad alta, porque es lo más grande que se ve al entrar (LCP).
   * El resto se carga de forma diferida, cuando se acerca al área visible.
   */
  priority?: boolean;
  /** Reduce el hueco de reserva a un icono (miniaturas donde no cabe el título). */
  compact?: boolean;
  /**
   * Uso como FONDO decorativo (banner y cabecera del modal, desenfocado detrás
   * del póster nítido): si no hay imagen, el hueco es solo el degradado, sin
   * icono ni título (ya los muestra el póster de delante), y siempre oculto a
   * los lectores de pantalla.
   */
  backdrop?: boolean;
  /** Clases del contenedor: tamaño/proporción (`aspect-2/3 w-full`, `size-full`...) y bordes. */
  className?: string;
  /** Clases de la `<img>` (p. ej. el zoom al pasar el ratón por una tarjeta). */
  imgClassName?: string;
}

/**
 * Póster de una película: SIEMPRE la imagen que indica la API (`imageUrl`).
 *
 * - **Sin saltos de diseño**: el contenedor tiene tamaño propio (lo da quien lo
 *   usa) y la `<img>` lleva `width`/`height` (las portadas son 2:3), de modo
 *   que el navegador reserva el hueco antes de descargar nada.
 * - **Carga perezosa** (`loading="lazy"`, `decoding="async"`) salvo la imagen
 *   principal ({@link MoviePosterProps.priority}).
 * - **Respaldo sin bucles**: si `src` falta o la imagen da error, se muestra un
 *   hueco dibujado con HTML (icono + título), no otra imagen. Como no es una
 *   petición, no puede fallar ni volver a disparar `onError`. Se recuerda QUÉ
 *   `src` falló (y no un simple "ha fallado"), así que si la película cambia
 *   de imagen se vuelve a intentar cargar.
 * - **Huecos distinguibles**: el fondo del hueco es un degradado elegido por el
 *   título ({@link posterFallbackGradient}), no un color único: varias películas
 *   sin portada seguidas no parecen una página a medio cargar. El título va en
 *   blanco y negrita, y crece si el hueco es grande (consulta de contenedor
 *   `@container`, sin props extra).
 */
export function MoviePoster({
  title,
  src,
  alt = '',
  priority = false,
  compact = false,
  backdrop = false,
  className = '',
  imgClassName = '',
}: MoviePosterProps) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const showFallback = !src || failedSrc === src;
  // Un fondo nunca hace de imagen con nombre: lo que importa está delante.
  const labelled = Boolean(alt) && !backdrop;

  return (
    // Sin `relative` a propósito: quien lo usa como fondo pasa `absolute inset-0`, y si el contenedor
    // llevara también `relative`, Tailwind (que ordena `relative` DESPUÉS de `absolute` en la hoja de estilos)
    // lo dejaría en `position: relative` y la imagen saldría a su tamaño de 600 px en lugar de cubrir el banner.
    // Nada dentro necesita un contexto de posicionamiento propio (la imagen y el hueco son `size-full`).
    <div className={`overflow-hidden bg-surface-raised ${className}`}>
      {showFallback ? (
        <div
          // Si hay `alt` el hueco se comporta como imagen con ese nombre; si no, es decorativo.
          role={labelled ? 'img' : undefined}
          aria-label={labelled ? alt : undefined}
          aria-hidden={labelled ? undefined : true}
          className={`@container flex size-full flex-col items-center justify-center gap-2 p-3 text-center ${posterFallbackGradient(title)}`}
        >
          {!backdrop && <Film aria-hidden="true" className="size-6 shrink-0 text-white/60 @[12rem]:size-9" />}
          {!compact && !backdrop && (
            <span className="line-clamp-4 text-sm leading-snug font-bold text-balance text-white @[12rem]:text-xl">
              {title}
            </span>
          )}
        </div>
      ) : (
        <img
          src={src}
          alt={labelled ? alt : ''}
          width={600}
          height={900}
          loading={priority ? 'eager' : 'lazy'}
          decoding="async"
          // La imagen es de un tercero: sin esto su host recibiría el Referer (la URL de la app) de cada visitante.
          referrerPolicy="no-referrer"
          fetchPriority={priority ? 'high' : undefined}
          onError={() => setFailedSrc(src ?? null)}
          className={`size-full object-cover ${imgClassName}`}
        />
      )}
    </div>
  );
}
