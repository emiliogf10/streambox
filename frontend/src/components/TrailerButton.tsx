import { useRef, useState } from 'react';
import { createPortal, flushSync } from 'react-dom';
import { Clapperboard } from 'lucide-react';
import { getTrailer } from '../lib/youtube';
import { Button } from './Button';
import type { ButtonVariant } from './buttonStyles';
import { TrailerDialog } from './TrailerDialog';

/** Propiedades de {@link TrailerButton}. */
interface TrailerButtonProps {
  /** `trailerUrl` de la película o de la serie, tal cual llega de la API (`null` = no tiene). */
  trailerUrl: string | null | undefined;
  /** Título, para el nombre accesible («Ver tráiler de Dune») y el del diálogo. */
  title: string;
  /**
   * Variante visual. `outline` (por defecto) cuando hay también «Ver ahora»
   * (el contenido manda sobre su tráiler); `light` cuando el tráiler es lo
   * único que se puede ver y pasa a ser la acción principal.
   */
  variant?: ButtonVariant;
  /** Clases extra de colocación. */
  className?: string;
}

/**
 * Botón «Ver tráiler»: abre el tráiler de YouTube en un diálogo ({@link TrailerDialog}).
 *
 * - **Solo aparece si hay un tráiler válido** (`getTrailer`): sin él no se pinta
 *   nada, ni desactivado, para no prometer algo que no existe.
 * - **Es un botón, no un enlace**: no navega a ningún sitio, abre un diálogo en
 *   la misma página (`aria-haspopup="dialog"` lo anuncia). Para abrirlo en
 *   YouTube, el diálogo tiene su enlace «Ver en YouTube».
 * - **Icono distinto del de «Ver ahora»** (claqueta y no triángulo de
 *   reproducir): con los dos botones juntos, se distinguen de un vistazo
 *   también sin leer, y nunca se confunde el tráiler con la película.
 * - **El diálogo se monta al abrir y se desmonta al cerrar**: así el vídeo se
 *   detiene al cerrarlo y cada apertura empieza desde el principio. Se pinta en
 *   un *portal* a `<body>` y no junto al botón: las acciones del banner son una
 *   rejilla que coloca a sus hijos según cuántos hay (`FeaturedBanner`), y un
 *   hijo de más mientras el diálogo está abierto movería los botones de detrás.
 * - Nombre accesible «Ver tráiler de <título>»: empieza por el texto visible
 *   (WCAG 2.5.3) y es único aunque haya varios en la página.
 */
export function TrailerButton({ trailerUrl, title, variant = 'outline', className = '' }: TrailerButtonProps) {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const trailer = getTrailer(trailerUrl);
  if (!trailer) return null;

  /**
   * Cierra el diálogo y devuelve el foco a este botón. `Modal` ya devuelve el
   * foco al elemento que estaba activo al abrirse, pero Safari no enfoca un
   * botón al pulsarlo con el ratón (el activo sería `<body>`). `flushSync`
   * desmonta el diálogo en el acto: con él aún abierto, el botón estaría inerte
   * detrás del fondo y `focus()` no haría nada.
   */
  const close = () => {
    flushSync(() => setOpen(false));
    buttonRef.current?.focus();
  };

  return (
    <>
      <Button
        ref={buttonRef}
        variant={variant}
        aria-haspopup="dialog"
        aria-label={`Ver tráiler de ${title}`}
        onClick={() => setOpen(true)}
        className={className}
      >
        <Clapperboard aria-hidden="true" className="size-4" />
        Ver tráiler
      </Button>
      {open && createPortal(<TrailerDialog title={title} trailer={trailer} onClose={close} />, document.body)}
    </>
  );
}
