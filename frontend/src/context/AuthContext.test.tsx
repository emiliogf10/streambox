/**
 * Tests de `AuthProvider` / `useAuth`: la única fuente de verdad de la sesión.
 *
 * Protegen que el token solo vive en `localStorage['token']`, que las pestañas
 * se mantienen coherentes entre sí (evento `storage`), que un 401 cierra la
 * sesión UNA sola vez y solo si es de la sesión actual, y que un almacenamiento
 * roto no tumba la aplicación. Se usa el `apiFetch` real con `fetch` simulado
 * para probar el puente `configureAuth` de punta a punta.
 */
import { act, renderHook, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../lib/api';
import { errorResponse, jsonResponse } from '../test/helpers';
import { AuthProvider, useAuth } from './AuthContext';
import { ToastProvider } from './ToastContext';

const fetchMock = vi.fn<typeof fetch>();

function wrapper({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <AuthProvider>{children}</AuthProvider>
    </ToastProvider>
  );
}

/** Simula el evento que el navegador emite en OTRA pestaña cuando cambia `localStorage`. */
function storageEvent(init: StorageEventInit) {
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', init));
  });
}

/** Cabecera Authorization de la última petición hecha con `fetch`. */
function lastAuthorization(): string | null {
  const init = fetchMock.mock.calls.at(-1)?.[1];
  return new Headers(init?.headers).get('Authorization');
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AuthProvider: login y logout', () => {
  it('sin token guardado no hay sesión', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current).toMatchObject({ token: null, isAuthenticated: false });
  });

  it('arranca con la sesión guardada en localStorage', () => {
    localStorage.setItem('token', 'guardado');

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current).toMatchObject({ token: 'guardado', isAuthenticated: true });
  });

  it('login guarda el token en localStorage["token"] y abre la sesión', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('abc'));

    expect(localStorage.getItem('token')).toBe('abc');
    expect(result.current).toMatchObject({ token: 'abc', isAuthenticated: true });
  });

  it('logout borra el token y cierra la sesión (y es idempotente)', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.logout());
    act(() => result.current.logout());

    expect(localStorage.getItem('token')).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('una petición lanzada justo después de login ya lleva el token nuevo', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({}));
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('recien-llegado'));
    await apiFetch('/movies');

    expect(lastAuthorization()).toBe('Bearer recien-llegado');
  });

  it('useAuth fuera de AuthProvider lanza un error claro', () => {
    // React registra con console.error el error de render; es lo esperado aquí.
    vi.spyOn(console, 'error').mockImplementation(() => {});

    expect(() => renderHook(() => useAuth())).toThrow('useAuth debe usarse dentro de <AuthProvider>.');
  });
});

describe('AuthProvider: sincronización entre pestañas (evento storage)', () => {
  it('un token nuevo escrito en otra pestaña abre la sesión aquí y lo usan las peticiones', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({}));
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'token', newValue: 'de-otra-pestana' });
    await apiFetch('/movies');

    expect(result.current).toMatchObject({ token: 'de-otra-pestana', isAuthenticated: true });
    expect(lastAuthorization()).toBe('Bearer de-otra-pestana');
  });

  it('el token borrado en otra pestaña (newValue null) cierra la sesión', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'token', newValue: null });

    expect(result.current.isAuthenticated).toBe(false);
  });

  it('localStorage.clear() en otra pestaña (key === null) cierra la sesión', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: null });

    expect(result.current).toMatchObject({ token: null, isAuthenticated: false });
  });

  it('un newValue vacío no cuenta como token: cierra la sesión', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'token', newValue: '' });

    expect(result.current.isAuthenticated).toBe(false);
  });

  it('los cambios de claves ajenas se ignoran', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'tema', newValue: 'oscuro' });
    storageEvent({ key: 'tema', newValue: null });

    expect(result.current).toMatchObject({ token: 'abc', isAuthenticated: true });
  });

  it('deja de escuchar al desmontarse', () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = renderHook(() => useAuth(), { wrapper });
    const registered = add.mock.calls.find(([type]) => type === 'storage')?.[1];
    expect(registered).toBeDefined();

    unmount();

    // Un listener que sobreviviera al componente sería una fuga de memoria y de estado.
    expect(remove).toHaveBeenCalledWith('storage', registered);
  });
});

describe('AuthProvider: 401 del servidor', () => {
  it('un 401 con el token actual cierra la sesión y avisa', async () => {
    localStorage.setItem('token', 'actual');
    const { result } = renderHook(() => useAuth(), { wrapper });
    fetchMock.mockImplementation(async () => errorResponse(401, 'UNAUTHORIZED', 'x'));

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(localStorage.getItem('token')).toBeNull();
    expect(screen.getByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toBeInTheDocument();
  });

  it('varios 401 simultáneos cierran la sesión y avisan UNA sola vez', async () => {
    localStorage.setItem('token', 'actual');
    renderHook(() => useAuth(), { wrapper });
    fetchMock.mockImplementation(async () => errorResponse(401, 'UNAUTHORIZED', 'x'));

    await act(async () => {
      await Promise.all([
        apiFetch('/movies').catch(() => undefined),
        apiFetch('/genres').catch(() => undefined),
        apiFetch('/users/me/favorites').catch(() => undefined),
      ]);
    });

    expect(screen.getAllByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toHaveLength(1);
  });

  it('un 401 tardío de una petición hecha con un token ANTERIOR se ignora', async () => {
    localStorage.setItem('token', 'viejo');
    const { result } = renderHook(() => useAuth(), { wrapper });
    let release!: (response: Response) => void;
    fetchMock.mockImplementationOnce(() => new Promise<Response>((resolve) => (release = resolve)));

    // La petición sale con el token viejo...
    const slow = apiFetch('/movies').catch(() => undefined);
    // ...el usuario vuelve a entrar y ahora la sesión es otra...
    act(() => result.current.login('nuevo'));
    // ...y entonces llega el 401 de la petición antigua.
    await act(async () => {
      release(errorResponse(401, 'UNAUTHORIZED', 'x'));
      await slow;
    });

    expect(result.current).toMatchObject({ token: 'nuevo', isAuthenticated: true });
    expect(localStorage.getItem('token')).toBe('nuevo');
    expect(screen.queryByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).not.toBeInTheDocument();
  });

  it('un 403 no cierra la sesión', async () => {
    localStorage.setItem('token', 'actual');
    const { result } = renderHook(() => useAuth(), { wrapper });
    fetchMock.mockImplementation(async () => errorResponse(403, 'ACCESS_DENIED', 'x'));

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(result.current.isAuthenticated).toBe(true);
  });

  it('al desmontar el proveedor se desconecta el puente: apiFetch ya no manda token', async () => {
    localStorage.setItem('token', 'actual');
    fetchMock.mockImplementation(async () => jsonResponse({}));
    const { unmount } = renderHook(() => useAuth(), { wrapper });
    unmount();

    await apiFetch('/movies');

    expect(lastAuthorization()).toBeNull();
  });
});

describe('AuthProvider: almacenamiento no disponible', () => {
  it('si leer localStorage lanza (modo privado), arranca sin sesión en lugar de romper', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Acceso denegado', 'SecurityError');
    });

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.isAuthenticated).toBe(false);
  });

  it('si escribir lanza, login y logout siguen funcionando en memoria', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Cuota excedida', 'QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('Acceso denegado', 'SecurityError');
    });
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('abc'));
    expect(result.current).toMatchObject({ token: 'abc', isAuthenticated: true });

    act(() => result.current.logout());
    expect(result.current.isAuthenticated).toBe(false);
  });
});
