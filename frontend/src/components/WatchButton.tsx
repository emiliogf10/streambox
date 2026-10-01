import { useId } from 'react';
import { Play } from 'lucide-react';
import { getSafeVideoUrl } from '../lib/utils';
import { buttonClasses } from './buttonStyles';

/** Propiedades de {@link WatchButton}. */
interface WatchButtonProps {
  /** `videoUrl` de la película tal como viene de la API (sin validar). */
  videoUrl: string | null | undefined;
  /** Título, para dar un nombre accesible único al enlace. */
  title: string;
}

/**
 * Botón "Ver ahora".
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
 */
export function WatchButton({ videoUrl, title }: WatchButtonProps) {
  const reasonId = useId();
  const safeUrl = getSafeVideoUrl(videoUrl);

  if (safeUrl) {
    return (
      <a
        href={safeUrl}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`Ver ahora ${title} (se abre en una pestaña nueva)`}
        className={buttonClasses('light')}
      >
        <Play aria-hidden="true" className="size-4 fill-black" />
        Ver ahora
      </a>
    );
  }

  return (
    <>
      <button
        type="button"
        aria-disabled="true"
        aria-describedby={reasonId}
        title="Esta película no tiene un vídeo disponible"
        className={buttonClasses('light')}
        onClick={(event) => event.preventDefault()}
      >
        <Play aria-hidden="true" className="size-4 fill-black" />
        Ver ahora
      </button>
      <span id={reasonId} className="sr-only">
        No disponible: esta película no tiene un vídeo al que se pueda acceder.
      </span>
    </>
  );
}
