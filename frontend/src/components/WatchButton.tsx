import { Link } from 'react-router-dom';
import { Play } from 'lucide-react';
import { getDirectVideoUrl } from '../lib/playback';
import { getSafeVideoUrl } from '../lib/utils';
import { buttonClasses } from './buttonStyles';
import type { ButtonVariant } from './buttonStyles';

/** Propiedades de {@link WatchButton}. */
interface WatchButtonProps {
  /** `videoUrl` de la película o del episodio tal como viene de la API (sin validar; `null` = no hay vídeo). */
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
  /** Variante visual. `light` (por defecto) es la acción principal; las listas de episodios usan `outline`. */
  variant?: ButtonVariant;
  /** Clases extra de colocación (ancho dentro de una rejilla de acciones...). */
  className?: string;
  /**
   * Página del reproductor integrado de este título (`moviePlayerPath`,
   * `episodePlayerPath`). Si el vídeo es un archivo directo (`getDirectVideoUrl`),
   * el botón lleva ahí, dentro de StreamBox; si no, o sin esta propiedad, abre
   * el vídeo en otra pestaña como siempre.
   */
  playerPath?: string;
  /**
   * `true` = ir al reproductor SUSTITUYENDO la entrada actual del historial. Lo
   * usa «Ver el siguiente» dentro del propio reproductor: así «Volver» (y
   * «Atrás») lleva a la página de la serie y no al episodio que se acaba de
   * terminar. Solo afecta al reproductor integrado.
   */
  replace?: boolean;
}

/**
 * Botón "Ver ahora" de una película o "Ver" de un episodio.
 *
 * Es un ENLACE, no un botón con `window.open`: se puede abrir con clic
 * central, copiar la dirección o abrir con teclado como cualquier enlace. Hay
 * dos destinos posibles:
 * - **Reproductor integrado** (`/ver/...`, un `<Link>` de la aplicación): si se
 *   pasa `playerPath` y el vídeo es un archivo directo (`.mp4`, `.webm`,
 *   `.ogg`/`.ogv`; ver `getDirectVideoUrl`). Ahí se guarda el progreso y se
 *   reanuda («Seguir viendo»). Su nombre no avisa de pestaña nueva: no la abre.
 * - **Otra pestaña** con el enlace tal cual, para cualquier otro vídeo (una
 *   página de YouTube, Vimeo, HLS...), que un `<video>` no sabría reproducir.
 *   Con `rel="noopener noreferrer"`, para que la página destino no pueda
 *   manipular esta (`window.opener`) ni reciba el origen.
 *
 * Antes de usar la URL se valida que sea http(s) ({@link getSafeVideoUrl}); un
 * `javascript:` guardado en la base de datos no debe ejecutarse al hacer clic.
 *
 * **Sin vídeo, no se pinta nada.** El vídeo del contenido es opcional
 * (StreamBox no aloja vídeos; el tráiler va aparte, en `trailerUrl`). Antes,
 * sin URL válida se mostraba un «Ver ahora» desactivado: era un botón que
 * prometía ver algo que no existe, y con todo el catálogo sin vídeo, toda la
 * aplicación estaría llena de botones que no hacen nada. Ahora quien lo usa no
 * tiene que comprobar nada: si no hay vídeo, el botón simplemente no está (y
 * una URL insegura cuenta como «no hay vídeo»). Para decidir la jerarquía del
 * resto de acciones (p. ej. si «Ver tráiler» pasa a ser la principal), usa
 * `getSafeVideoUrl(videoUrl) !== null`, la misma condición que aquí.
 *
 * El icono usa `fill-current` (el color del texto) y no un color fijo, para que
 * se vea igual en la variante blanca (texto negro) que en la de borde (texto blanco).
 */
export function WatchButton({
  videoUrl,
  title,
  label = 'Ver ahora',
  accessibleName,
  variant = 'light',
  className = '',
  playerPath,
  replace = false,
}: WatchButtonProps) {
  const safeUrl = getSafeVideoUrl(videoUrl);
  if (!safeUrl) return null;

  if (playerPath && getDirectVideoUrl(videoUrl)) {
    return (
      <Link to={playerPath} replace={replace} aria-label={accessibleName ?? `${label} ${title}`} className={buttonClasses(variant, className)}>
        <Play aria-hidden="true" className="size-4 fill-current" />
        {label}
      </Link>
    );
  }

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
