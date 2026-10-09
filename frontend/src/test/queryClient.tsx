/**
 * Caché de TanStack Query para los tests.
 *
 * **Una caché nueva por test.** Si dos tests compartieran `QueryClient`, el
 * segundo vería los datos que cargó el primero (y no pediría nada): los
 * resultados dependerían del orden. `renderWithProviders` crea uno por llamada;
 * los tests de hooks usan {@link queryWrapper}.
 *
 * Diferencias con la de la aplicación (`createQueryClient` de `lib/queryClient.ts`),
 * todas para que el test sea determinista:
 * - `retry: false`: un fallo simulado se ve al momento, sin el segundo de espera
 *   del reintento (la política real se prueba aparte en `queryClient.test.ts`).
 * - `gcTime: Infinity`: no se programa el temporizador que libera los datos sin
 *   usar (con el reloj falso de `fakeTimers.ts` quedaría pendiente y podría
 *   dispararse en medio de otro paso del test).
 * - `refetchOnWindowFocus: false`: el foco de jsdom no debe lanzar peticiones
 *   que el test no ha previsto.
 * El resto (`staleTime`, `networkMode`...) es el de la aplicación, para probar
 * lo mismo que corre en el navegador (p. ej. que una segunda visita no pide nada).
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { createQueryClient } from '../lib/queryClient';
import { ToastProvider } from '../context/ToastContext';

/** Crea un `QueryClient` para un test (ver la documentación del archivo). */
export function createTestQueryClient(): QueryClient {
  const client = createQueryClient();
  client.setDefaultOptions({
    queries: { ...client.getDefaultOptions().queries, retry: false, gcTime: Infinity, refetchOnWindowFocus: false },
    mutations: { ...client.getDefaultOptions().mutations, gcTime: Infinity },
  });
  return client;
}

/**
 * `wrapper` para `renderHook` con caché y avisos (los hooks de datos avisan de
 * los fallos con `useToast`).
 *
 * @param client caché a usar; por defecto, una nueva. Pásala para inspeccionarla
 *   o para montar dos hooks sobre la misma caché.
 */
export function queryWrapper(client: QueryClient = createTestQueryClient()) {
  return function QueryWrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={client}>
        <ToastProvider>{children}</ToastProvider>
      </QueryClientProvider>
    );
  };
}
