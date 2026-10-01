import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { ToastViewport } from '../components/ToastViewport';
import { getErrorMessage, isAbortError, ApiError } from '../lib/api';

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
   */
  registerHost: (element: HTMLElement | null) => void;
}

/** Máximo de avisos simultáneos; al superarlo se descarta el más antiguo. */
const MAX_TOASTS = 4;

const ToastContext = createContext<ToastApi | null>(null);

/**
 * Proveedor del sistema de avisos (toasts), implementado sin librerías.
 * Debe envolver a cualquier componente que use {@link useToast}.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const nextId = useRef(1);
  // Contenedor alternativo de los avisos (el interior de un modal abierto), si lo hay.
  const [host, setHost] = useState<HTMLElement | null>(null);

  const dismiss = useCallback((id: number) => {
    setToasts((current) => current.filter((t) => t.id !== id));
  }, []);

  const push = useCallback((type: ToastType, message: string) => {
    const id = nextId.current++;
    setToasts((current) => [...current, { id, type, message }].slice(-MAX_TOASTS));
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
      registerHost: setHost,
    }),
    [push],
  );

  const viewport = <ToastViewport toasts={toasts} onDismiss={dismiss} />;

  return (
    <ToastContext.Provider value={api}>
      {children}
      {host ? createPortal(viewport, host) : viewport}
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
