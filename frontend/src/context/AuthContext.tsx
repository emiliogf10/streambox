import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { configureAuth } from '../lib/api';
import { useToast } from './ToastContext';

/** Clave de `localStorage` donde se guarda el JWT. */
const TOKEN_KEY = 'token';

/**
 * Lee el token guardado. `localStorage` puede lanzar excepciones (modo privado
 * de algunos navegadores, almacenamiento bloqueado): en ese caso se trata como
 * "sin sesión" en lugar de romper la aplicación.
 */
function readStoredToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Guarda o borra (con `null`) el token; ignora los fallos de almacenamiento. */
function writeStoredToken(token: string | null): void {
  try {
    if (token === null) localStorage.removeItem(TOKEN_KEY);
    else localStorage.setItem(TOKEN_KEY, token);
  } catch {
    // Sin almacenamiento: la sesión vivirá solo mientras no se recargue la página.
  }
}

/** Valor que expone {@link useAuth}. */
interface AuthContextValue {
  /** JWT actual, o `null` si no hay sesión. */
  token: string | null;
  /** `true` si hay una sesión iniciada. */
  isAuthenticated: boolean;
  /** Guarda el token recibido del login y abre la sesión. */
  login: (token: string) => void;
  /** Cierra la sesión (idempotente). */
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Proveedor de la sesión: ÚNICA fuente de verdad del token.
 *
 * Es el único sitio (junto con el puente de `api.ts`) que toca `localStorage`.
 * Se registra en `apiFetch` con `configureAuth`, de modo que:
 * - `apiFetch` lee el token de aquí al enviar cada petición.
 * - Ante un 401 en un endpoint autenticado, `apiFetch` avisa y aquí se cierra
 *   la sesión. No se navega desde aquí: al quedar `isAuthenticated = false`, la
 *   ruta protegida redirige a `/login` por sí sola, UNA vez, aunque fallen
 *   varias peticiones a la vez (el segundo aviso ya encuentra la sesión cerrada
 *   y no hace nada).
 *
 * Debe ir dentro de `ToastProvider` (muestra el aviso de sesión caducada).
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const toast = useToast();
  const [token, setToken] = useState<string | null>(readStoredToken);
  // Copia síncrona del token para `apiFetch`: el estado de React se actualiza
  // tras el render, pero una petición lanzada justo después de `login` ya debe
  // llevar el token nuevo.
  const tokenRef = useRef<string | null>(token);

  const login = useCallback((newToken: string) => {
    tokenRef.current = newToken;
    writeStoredToken(newToken);
    setToken(newToken);
  }, []);

  const logout = useCallback(() => {
    tokenRef.current = null;
    writeStoredToken(null);
    setToken(null);
  }, []);

  // `useLayoutEffect` (y no `useEffect`) porque los efectos de los componentes
  // hijos —que ya lanzan peticiones al montarse— se ejecutan ANTES que los del
  // padre; los layout effects, en cambio, corren todos antes que cualquier
  // `useEffect`, así que el puente está listo cuando llega la primera petición.
  useLayoutEffect(() => {
    configureAuth({
      getToken: () => tokenRef.current,
      onUnauthorized: (usedToken) => {
        // Ignora el 401 si ya no hay sesión o si pertenece a una petición hecha
        // con un token anterior (p. ej. respuesta tardía tras volver a entrar).
        if (tokenRef.current === null || usedToken !== tokenRef.current) return;
        logout();
        toast.info('Tu sesión ha caducado. Inicia sesión de nuevo.');
      },
    });
    return () => configureAuth(null);
  }, [logout, toast]);

  // Si se cierra o abre sesión en otra pestaña, esta se mantiene coherente.
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      // `key === null` es lo que emite `localStorage.clear()`: se vació todo el
      // almacenamiento, así que equivale a que la clave del token pase a `null`.
      // Cualquier otra clave no nos afecta.
      if (event.key !== null && event.key !== TOKEN_KEY) return;
      // Solo una cadena no vacía cuenta como token nuevo; cualquier otra cosa
      // (null, cadena vacía) se trata como cierre de sesión.
      const next = event.key === null || !event.newValue ? null : event.newValue;
      tokenRef.current = next;
      setToken(next);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ token, isAuthenticated: token !== null, login, logout }),
    [token, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * Acceso a la sesión: `const { isAuthenticated, login, logout } = useAuth()`.
 *
 * @throws si se usa fuera de {@link AuthProvider}
 */
// oxlint-disable-next-line react/only-export-components -- patrón habitual: proveedor y hook comparten archivo
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth debe usarse dentro de <AuthProvider>.');
  return ctx;
}
