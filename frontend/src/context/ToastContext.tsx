import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { ToastViewport } from '../components/ToastViewport';
import { getErrorMessage, isAbortError, ApiError } from '../lib/api';
import { isPageVisible } from '../hooks/usePageVisible';

/** Tipos de aviso. */
export type ToastType = 'success' | 'error' | 'info';

/** Un aviso en pantalla. */
export interface ToastItem {
  id: number;
  type: ToastType;
  message: string;
}

/** API que expone {@link useToast}. */
interface ToastApi {
  /** Aviso de operación correcta. */
  success: (message: string) => void;
  /** Aviso de fallo. */
  error: (message: string) => void;
  /** Aviso neutro. */
  info: (message: string) => void;
  /**
   * Muestra como error el mensaje de un error capturado en un `catch`.
   * Ignora las cancelaciones y el 401 que ya cerró la sesión (ese aviso lo
   * da `AuthProvider` una sola vez).
   */
  errorFrom: (error: unknown, fallback?: string) => void;
  /**
   * Traslada la zona de avisos a `element` (o la devuelve a la página con `null`).
   * Lo usa `Modal`: un `<dialog>` modal vuelve inerte —y tapa— todo lo que hay
   * fuera de él, así que mientras está abierto los avisos tienen que vivir dentro.
   *
   * Solo se trasladan los avisos que nacen **con el diálogo ya abierto**. Los
   * anteriores (que ya se vieron y se anunciaron) se quedan en la página, detrás
   * del fondo, hasta que caducan: si se movieran dentro, el aviso de la acción
   * anterior taparía los botones del diálogo recién abierto (abajo a la
   * derecha, donde están «Cancelar» y «Guardar») durante sus 5 segundos. Pasaba
   * al añadir episodios seguidos: «Añadido» tapaba el «Añadir episodio» del siguiente.
   */
  registerHost: (element: HTMLElement | null) => void;
}

/** Máximo de avisos simultáneos; al superarlo se descarta el más antiguo. */
const MAX_TOASTS = 4;

/**
 * Un aviso en la cola interna del proveedor. `revealed` dice si ya se ha
 * pintado con la pestaña visible (ver {@link ToastProvider}).
 */
interface QueuedToast extends ToastItem {
  revealed: boolean;
}

const ToastContext = createContext<ToastApi | null>(null);

/**
 * Proveedor del sistema de avisos (toasts), implementado sin librerías.
 * Debe envolver a cualquier componente que use {@link useToast}.
 *
 * **Avisos que nacen con la pestaña oculta.** Se guardan, pero no se pintan
 * hasta que la pestaña vuelve a verse (`visibilitychange`). Caso real: con dos
 * pestañas abiertas se cierra la sesión en una; la otra, en segundo plano, recibe
 * la señal `session-changed` y avisa «Se ha cerrado la sesión en otra pestaña…».
 * Antes ese aviso se pintaba y caducaba a los 5 s sin que nadie lo viera. Al
 * esperar a que se vea se consiguen dos cosas:
 * - Su cuenta atrás (que vive en `Toast`, y empieza al montarse) no empieza hasta
 *   que alguien puede leerlo, así que dura sus segundos completos.
 * - Los lectores de pantalla lo anuncian al volver, cuando se inserta en la región
 *   `aria-live`: los cambios en una pestaña de fondo no se anuncian, y uno que ya
 *   estaba al volver tampoco (las regiones vivas solo anuncian cambios). Se anuncia
 *   una sola vez, porque solo se inserta una vez.
 *
 * Los avisos que ya estaban a la vista cuando la pestaña se oculta siguen
 * montados (no se vuelven a anunciar al volver) y solo pausan su cuenta atrás:
 * eso lo hace `Toast`.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<QueuedToast[]>([]);
  const nextId = useRef(1);
  // Contenedor alternativo de los avisos (el interior de un modal abierto), si lo hay, y el id del primer
  // aviso que le corresponde: los de id menor ya existían al abrirse el diálogo y se quedan en la página.
  const [host, setHost] = useState<{ element: HTMLElement; firstId: number } | null>(null);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const push = useCallback((type: ToastType, message: string) => {
    const id = nextId.current++;
    // Se mira la visibilidad en el momento de crearlo: la de un render anterior podría estar desfasada.
    const revealed = isPageVisible();
    setToasts((current) => [...current, { id, type, message, revealed }].slice(-MAX_TOASTS));
  }, []);

  // Al volver a la pestaña se pintan los avisos que esperaban. Se marca cada uno como
  // «ya visto» (y no se filtra solo por la visibilidad actual) para que, si la pestaña
  // se vuelve a ocultar, los que ya se pintaron no se desmonten: al reaparecer se
  // anunciarían otra vez y su cuenta atrás empezaría de cero.
  useEffect(() => {
    const revealPending = () => {
      if (!isPageVisible()) return;
      setToasts((current) =>
        current.some((t) => !t.revealed) ? current.map((t) => (t.revealed ? t : { ...t, revealed: true })) : current,
      );
    };
    document.addEventListener('visibilitychange', revealPending);
    return () => document.removeEventListener('visibilitychange', revealPending);
  }, []);

  // El objeto de la API es estable (no cambia entre renders) para que usarlo
  // como dependencia de un efecto no lo dispare de nuevo.
  const api = useMemo<ToastApi>(
    () => ({
      success: (message) => push('success', message),
      error: (message) => push('error', message),
      info: (message) => push('info', message),
      errorFrom: (error, fallback) => {
        if (isAbortError(error)) return;
        if (error instanceof ApiError && error.sessionExpired) return;
        push('error', getErrorMessage(error, fallback));
      },
      registerHost: (element) => setHost(element ? { element, firstId: nextId.current } : null),
    }),
    [push],
  );

  const shown = toasts.filter((t) => t.revealed);
  const inHost = host ? shown.filter((t) => t.id >= host.firstId) : [];
  const onPage = host ? shown.filter((t) => t.id < host.firstId) : shown;

  return (
    <ToastContext.Provider value={api}>
      {children}
      <ToastViewport toasts={onPage} onDismiss={dismiss} />
      {host && createPortal(<ToastViewport toasts={inHost} onDismiss={dismiss} />, host.element)}
    </ToastContext.Provider>
  );
}

/**
 * Acceso al sistema de avisos: `const toast = useToast(); toast.success('Hecho')`.
 *
 * @throws si se usa fuera de {@link ToastProvider}
 */
// oxlint-disable-next-line react/only-export-components -- patrón habitual: proveedor y hook comparten archivo
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast debe usarse dentro de <ToastProvider>.');
  return ctx;
}
