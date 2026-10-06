import { useId } from 'react';
import { Play } from 'lucide-react';
import { getSafeVideoUrl } from '../lib/utils';
import { buttonClasses } from './buttonStyles';
import type { ButtonVariant } from './buttonStyles';

/** Propiedades de {@link WatchButton}. */
interface WatchButtonProps {
  /** `videoUrl` de la película o del episodio tal como viene de la API (sin validar). */
  videoUrl: string | null | undefined;
  /** Título, para dar un nombre accesible único al enlace. */
  title: string;
  /** Texto visible. Por defecto «Ver ahora»; los episodios usan «Ver». */
  label?: string;
  /**
   * Nombre accesible del enlace, SIN el aviso de pestaña nueva (se añade solo).
   * Por defecto «Ver ahora <título>». Debe empezar por el texto visible (WCAG
   * 2.5.3): quien usa control por voz dice lo que ve.
   */
  accessibleName?: string;
  /** Qué no tiene vídeo, para la explicación del botón desactivado ("esta película", "este episodio"). */
  unavailableSubject?: string;
  /** Variante visual. `light` (por defecto) es la acción principal; las listas de episodios usan `outline`. */
  variant?: ButtonVariant;
  /** Clases extra de colocación (ancho dentro de una rejilla de acciones...). */
  className?: string;
}

/**
 * Botón "Ver ahora" de una película o "Ver" de un episodio.
 *
 * Es un ENLACE (`<a>`), no un botón con `window.open`: se puede abrir con clic
 * central, copiar la dirección o abrir con teclado como cualquier enlace.
 * Se abre en una pestaña nueva con `rel="noopener noreferrer"` para que la
 * página destino no pueda manipular esta (`window.opener`) ni reciba el origen.
 *
 * Antes de usar la URL se valida que sea http(s) ({@link getSafeVideoUrl}); un
 * `javascript:` guardado en la base de datos no debe ejecutarse al hacer clic.
 * Si no hay URL válida se muestra un botón con `aria-disabled` (sigue siendo
 * enfocable, a diferencia de `disabled`) y una explicación para lectores de pantalla.
 *
 * El icono usa `fill-current` (el color del texto) y no un color fijo, para que
 * se vea igual en la variante blanca (texto negro) que en la de borde (texto blanco).
 */
export function WatchButton({
  videoUrl,
  title,
  label = 'Ver ahora',
  accessibleName,
  unavailableSubject = 'esta película',
  variant = 'light',
  className = '',
}: WatchButtonProps) {
  const reasonId = useId();
  const safeUrl = getSafeVideoUrl(videoUrl);

  if (safeUrl) {
    return (
      <a
        href={safeUrl}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`${accessibleName ?? `${label} ${title}`} (se abre en una pestaña nueva)`}
        className={buttonClasses(variant, className)}
      >
        <Play aria-hidden="true" className="size-4 fill-current" />
        {label}
      </a>
    );
  }

  const subject = unavailableSubject.charAt(0).toUpperCase() + unavailableSubject.slice(1);
  return (
    <>
      <button
        type="button"
        aria-disabled="true"
        // Con nombre propio (episodios) el botón desactivado también dice de qué episodio es: en una lista
        // hay varios «Ver» y, sin él, todos sonarían igual. Sin nombre propio, el texto visible basta.
        aria-label={accessibleName}
        aria-describedby={reasonId}
        title={`${subject} no tiene un vídeo disponible`}
        className={buttonClasses(variant, className)}
        onClick={(event) => event.preventDefault()}
      >
        <Play aria-hidden="true" className="size-4 fill-current" />
        {label}
      </button>
      <span id={reasonId} className="sr-only">
        No disponible: {unavailableSubject} no tiene un vídeo al que se pueda acceder.
      </span>
    </>
  );
}
