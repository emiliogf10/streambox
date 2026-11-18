import { useEffect, useId, useRef } from 'react';
import type { RefObject } from 'react';
import { ExternalLink, X } from 'lucide-react';
import type { Trailer } from '../lib/youtube';
import { buttonClasses } from './buttonStyles';
import { Modal } from './Modal';

/**
 * Pista de teclado bajo el vídeo.
 *
 * **Por qué hace falta.** Escape cierra el diálogo solo mientras el foco está
 * en StreamBox. Cuando entra en el reproductor (con Tab o al hacer clic en el
 * vídeo), las teclas las recibe el documento de YouTube, que es de **otro
 * origen**: el navegador no deja que la página las vea ni las capture (es una
 * barrera de seguridad, no un fallo que se pueda arreglar aquí). Mayús+Tab sí
 * saca el foco del marco: vuelve a «Cerrar el tráiler», y entonces Esc cierra.
 * Lo comprueba `e2e/trailer-keyboard.spec.ts`. Dice «sal antes», no «pulsa una
 * vez», porque dentro del reproductor real puede haber varios controles detrás.
 *
 * **«Pulsa Esc para cerrar» es verdad nada más abrir** gracias a
 * {@link useFocusStealGuard}: el reproductor de YouTube a veces se queda con el
 * foco él solo al cargar (visto con un vídeo que no existe o no se puede ver: el
 * foco pasaba al `<iframe>` y Escape dejaba de cerrar).
 */
const TRAILER_KEYBOARD_HINT = 'Pulsa Esc para cerrar. Si estás dentro del vídeo, sal antes con Mayús+Tab.';

/** Propiedades de {@link TrailerDialog}. */
interface TrailerDialogProps {
  /** Título de la película o de la serie («Tráiler de <título>»). */
  title: string;
  /** Direcciones ya validadas y construidas por `getTrailer` (nunca el texto de la API tal cual). */
  trailer: Trailer;
  /** Cerrar (botón, Escape o clic en el fondo). */
  onClose: () => void;
}

/**
 * Tiempo, tras cargar el reproductor, durante el que se sigue vigilando que no
 * se quede el foco él solo (por si en una conexión lenta se enfoca después de su
 * evento `load`). Pasado ese rato, si el foco entra en el vídeo es porque el
 * usuario ha hecho clic en él.
 */
const STEAL_GUARD_AFTER_LOAD_MS = 1000;

/**
 * Margen para reconocer que el foco entra en el vídeo porque el usuario ha
 * pulsado Tab (o Mayús+Tab) en StreamBox: ese cambio de foco es suyo y se respeta.
 */
const TAB_INTENT_MS = 500;

/**
 * Devuelve a `fallbackRef` el foco que el reproductor de YouTube se queda él
 * solo al cargar, y el `onLoad` que hay que poner en el `<iframe>`.
 *
 * **Por qué.** El documento de YouTube es de otro origen y puede enfocarse
 * solo: con un vídeo que no existe o no se puede incrustar lo hace siempre
 * (medido en Chromium 155: el foco entra en el marco unos 100 ms ANTES de su
 * evento `load`). Desde Chromium 158, el `sandbox` del `<iframe>` ya impide que
 * un marco de otro origen se enfoque sin activación de usuario
 * (`focus-without-user-activation`), pero el guardia sigue haciendo falta para
 * otros navegadores, versiones anteriores y un reproductor que sí tenga
 * activación (p. ej. tras un clic dentro). Desde ese momento las teclas las recibe YouTube, Escape ya no llega
 * a StreamBox y el diálogo no se cierra, aunque el pie diga «Pulsa Esc para
 * cerrar». La página no puede ver ni impedir lo que pasa dentro del marco (es
 * una barrera de seguridad del navegador), pero sí darse cuenta de que el foco
 * ha entrado en él (la ventana recibe `blur` con el `<iframe>` como elemento
 * activo) y recuperarlo.
 *
 * **Cuándo NO lo recupera** (el cambio es del usuario):
 * - si justo antes se pulsó Tab en StreamBox (entra en el vídeo con el teclado),
 *   tampoco cuando termine de cargar mientras sigue dentro;
 * - pasado {@link STEAL_GUARD_AFTER_LOAD_MS} desde la carga (un clic en el vídeo:
 *   la página no ve los clics dentro de otro origen, pero antes de cargar no hay
 *   nada que pulsar y en el primer segundo es muy improbable; aun así, el clic
 *   llega a YouTube igual, solo vuelve el foco del teclado);
 * - si el foco ya está en otro control (p. ej. «Ver en YouTube»).
 * Para salir del vídeo después está la segunda frase de la pista (Mayús+Tab).
 *
 * Se descartó quitar la reproducción automática (probado: el reproductor se
 * enfoca igual sin ella) y la directiva `focus-without-user-activation 'none'`
 * del atributo `allow` (el Chromium actual no la aplica: el marco seguía
 * quedándose el foco).
 *
 * @param iframeRef el `<iframe>` del reproductor
 * @param fallbackRef adónde devolver el foco (el foco inicial del diálogo)
 * @returns el manejador `onLoad` del `<iframe>`
 */
function useFocusStealGuard(
  iframeRef: RefObject<HTMLIFrameElement | null>,
  fallbackRef: RefObject<HTMLElement | null>,
): () => void {
  /** Hasta cuándo se vigila (`performance.now()`); infinito mientras no ha cargado. */
  const guardUntil = useRef(Number.POSITIVE_INFINITY);
  /** Última vez que se pulsó Tab en StreamBox. */
  const tabbedAt = useRef(Number.NEGATIVE_INFINITY);
  /** El usuario ha entrado en el vídeo con Tab (y aún no ha vuelto): ni la carga le quita el foco. */
  const userInPlayer = useRef(false);

  useEffect(() => {
    const rememberTab = (event: KeyboardEvent) => {
      if (event.key === 'Tab') tabbedAt.current = performance.now();
    };
    let timer: ReturnType<typeof setTimeout> | undefined;
    const returnStolenFocus = () => {
      const now = performance.now();
      if (now - tabbedAt.current < TAB_INTENT_MS) {
        userInPlayer.current = true;
        return;
      }
      if (now > guardUntil.current) return;
      // En la tarea siguiente y no dentro del `blur`: enfocar en pleno cambio de foco deja a Chromium
      // con el botón como elemento activo pero con las teclas yendo aún al marco (probado: Escape no llegaba).
      clearTimeout(timer);
      timer = setTimeout(() => {
        if (document.activeElement === iframeRef.current) fallbackRef.current?.focus();
      }, 0);
    };
    const backInPage = () => {
      userInPlayer.current = false;
    };
    // En captura: el Tab se anota antes de que el foco cambie de sitio.
    document.addEventListener('keydown', rememberTab, true);
    window.addEventListener('blur', returnStolenFocus);
    window.addEventListener('focus', backInPage);
    return () => {
      clearTimeout(timer);
      document.removeEventListener('keydown', rememberTab, true);
      window.removeEventListener('blur', returnStolenFocus);
      window.removeEventListener('focus', backInPage);
    };
  }, [iframeRef, fallbackRef]);

  return () => {
    guardUntil.current = performance.now() + STEAL_GUARD_AFTER_LOAD_MS;
    // Por si la ventana no tenía el foco (y no hubo `blur`) cuando el reproductor se lo quedó.
    if (!userInPlayer.current && document.activeElement === iframeRef.current) fallbackRef.current?.focus();
  };
}

/**
 * Diálogo con el tráiler de YouTube incrustado.
 *
 * Se apoya en {@link Modal} (`<dialog>` nativo: fondo inerte, foco atrapado,
 * Escape, bloqueo del scroll y **devolución del foco** al botón «Ver tráiler»
 * que lo abrió). Quien lo usa lo **monta al abrir y lo desmonta al cerrar**: al
 * desmontarse desaparece el `<iframe>` y el vídeo se detiene de verdad (un
 * `<dialog>` cerrado pero montado seguiría sonando de fondo).
 *
 * **El `<iframe>`, atributo por atributo:**
 * - `src`: siempre `https://www.youtube-nocookie.com/embed/<id>?autoplay=1&rel=0`,
 *   compuesto por `getTrailer` con un id revalidado; el único origen que la CSP
 *   (`frame-src`) deja incrustar.
 * - `title`: nombre accesible del marco (un lector de pantalla anuncia «marco:
 *   Tráiler de ...» al entrar en él).
 * - `sandbox`: lo mínimo para que el reproductor funcione: `allow-scripts` y
 *   `allow-same-origin` (el reproductor es JavaScript y usa su propio
 *   almacenamiento), `allow-presentation` (enviar a una tele), `allow-popups`
 *   (su logotipo abre YouTube en otra pestaña) y `allow-popups-to-escape-sandbox`
 *   (esa pestaña de youtube.com no hereda el sandbox: si lo heredara, sin
 *   permisos de scripts propios podría no funcionar). Sin `allow-top-navigation`:
 *   el marco no puede sacar al usuario de StreamBox.
 * - `allow`: reproducción automática, contenido protegido, imagen en imagen y
 *   pantalla completa; nada de cámara, micrófono ni ubicación.
 * - `referrerPolicy="strict-origin-when-cross-origin"`: YouTube solo recibe el
 *   origen (`https://streambox...`), nunca la ruta de la página. Es además lo
 *   que pide YouTube para identificar al reproductor incrustado.
 * - `loading="lazy"`: por coherencia con el resto de recursos externos; dentro
 *   de un diálogo visible el navegador lo carga en el acto.
 * - `onLoad`: si el reproductor se ha quedado con el foco al cargar, se lo
 *   devuelve a «Cerrar el tráiler» (ver {@link useFocusStealGuard}).
 *
 * **Maquetación.** Relación 16:9 con `aspect-video` (sin estilos en línea). En
 * un móvil apaisado el ancho 16:9 no cabría en el alto de la pantalla, así que
 * el vídeo se limita también por la altura disponible (`max-w-[calc(...)]`) y
 * queda centrado: siempre se ve entero sin desplazarse.
 *
 * Bajo el vídeo, **«Ver en YouTube»** (pestaña nueva, `noopener noreferrer`) al
 * enlace normalizado: para quien prefiere verlo allí o si el reproductor
 * incrustado falla (vídeo que su autor no deja incrustar, extensiones...).
 *
 * A su izquierda, la pista de teclado {@link TRAILER_KEYBOARD_HINT}: ver su
 * comentario. Se oculta en pantallas táctiles (`pointer-coarse:hidden`), donde
 * nadie pulsa Esc y solo sería ruido; se cierra con el botón o tocando el fondo.
 */
export function TrailerDialog({ title, trailer, onClose }: TrailerDialogProps) {
  const titleId = useId();
  const closeRef = useRef<HTMLButtonElement>(null);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const handleIframeLoad = useFocusStealGuard(iframeRef, closeRef);

  return (
    <Modal onClose={onClose} labelledBy={titleId} initialFocusRef={closeRef} className="max-w-5xl">
      <div className="flex items-center justify-between gap-3 py-2 pr-2 pl-4 sm:py-3 sm:pr-3 sm:pl-6">
        <h2 id={titleId} className="min-w-0 text-base font-bold tracking-tight text-pretty wrap-anywhere sm:text-lg">
          Tráiler de {title}
        </h2>
        <button
          ref={closeRef}
          type="button"
          onClick={onClose}
          aria-label="Cerrar el tráiler"
          className="focus-ring inline-flex size-11 shrink-0 items-center justify-center rounded-lg text-white/85 transition-colors hover:bg-white/10 hover:text-white"
        >
          <X aria-hidden="true" className="size-5" />
        </button>
      </div>

      <div className="bg-black">
        <div className="mx-auto aspect-video w-full max-w-[calc((100dvh-10rem)*16/9)]">
          <iframe
            ref={iframeRef}
            src={trailer.embedUrl}
            title={`Tráiler de ${title} (YouTube)`}
            loading="lazy"
            referrerPolicy="strict-origin-when-cross-origin"
            allow="autoplay; encrypted-media; picture-in-picture; fullscreen"
            allowFullScreen
            sandbox="allow-scripts allow-same-origin allow-presentation allow-popups allow-popups-to-escape-sandbox"
            onLoad={handleIframeLoad}
            className="size-full border-0"
          />
        </div>
      </div>

      <div className="flex items-center justify-end gap-3 px-4 py-2 sm:px-6 sm:py-3">
        <p className="mr-auto min-w-0 text-xs leading-snug text-pretty text-muted pointer-coarse:hidden">
          {TRAILER_KEYBOARD_HINT}
        </p>
        <a
          href={trailer.watchUrl}
          target="_blank"
          rel="noopener noreferrer"
          aria-label="Ver en YouTube (se abre en una pestaña nueva)"
          className={buttonClasses('ghost', 'shrink-0 px-3')}
        >
          <ExternalLink aria-hidden="true" className="size-4" />
          Ver en YouTube
        </a>
      </div>
    </Modal>
  );
}
