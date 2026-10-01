import { useState } from 'react';
import { Film } from 'lucide-react';

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
 */
export function MoviePoster({
  title,
  src,
  alt = '',
  priority = false,
  compact = false,
  className = '',
  imgClassName = '',
}: MoviePosterProps) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const showFallback = !src || failedSrc === src;

  return (
    // Sin `relative` a propósito: quien lo usa como fondo pasa `absolute inset-0`, y si el contenedor
    // llevara también `relative`, Tailwind (que ordena `relative` DESPUÉS de `absolute` en la hoja de estilos)
    // lo dejaría en `position: relative` y la imagen saldría a su tamaño de 600 px en lugar de cubrir el banner.
    // Nada dentro necesita un contexto de posicionamiento propio (la imagen y el hueco son `size-full`).
    <div className={`overflow-hidden bg-surface-raised ${className}`}>
      {showFallback ? (
        <div
          // Si hay `alt` el hueco se comporta como imagen con ese nombre; si no, es decorativo.
          role={alt ? 'img' : undefined}
          aria-label={alt || undefined}
          aria-hidden={alt ? undefined : true}
          className="flex size-full flex-col items-center justify-center gap-2 bg-linear-to-br from-surface-raised to-surface p-2 text-center"
        >
          <Film aria-hidden="true" className="size-6 shrink-0 text-muted" />
          {!compact && (
            <span className="line-clamp-4 text-xs leading-snug font-semibold text-gray-300">{title}</span>
          )}
        </div>
      ) : (
        <img
          src={src}
          alt={alt}
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
