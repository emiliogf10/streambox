# 0012. Datos del servidor en el frontend con TanStack Query

**Estado:** Aceptada (2026-10-09). Complementa al [0002](0002-frontend-spa-react-vite.md).

## Contexto
Cada hook de datos (`useCatalog`, `usePagedCatalog`, `useSeriesDetail`, `useGenres`, `FavoritesContext`...) reimplementaba a mano la carga, el error, la cancelación de respuestas viejas (unos 50 sitios con *epochs* y `AbortController`) y el «cargar más». No había caché compartida (volver a una pantalla repetía todas las peticiones; los géneros los pedían cuatro pantallas por separado) ni invalidación (si un administrador editaba una película, los demás listados no se enteraban hasta recargar). Lo propuso también un informe externo (Gemini, 2026-10-08).

## Decisión
- **`@tanstack/react-query` v5** (unos 10 kB gzip; sin DevTools) para todo dato que venga del servidor y se muestre en más de un sitio. Un `QueryClient` en la raíz (`lib/queryClient.ts`): `staleTime` 60 s, `gcTime` 5 min, como mucho **un reintento y solo en red/5xx** (nunca 4xx: el 401 ya lo resuelve el *refresh* de `apiFetch`), `networkMode: 'always'` para que sin red salga el error con «Reintentar» y no un esqueleto eterno.
- **`apiFetch` sigue siendo el único cliente HTTP** (ADR 0011): las `queryFn` lo llaman pasando el `signal` de TanStack.
- **Claves centralizadas** (`lib/queryKeys.ts`) y **una función que dice qué invalidar tras cada escritura** (`keysAffectedBy`, `hooks/useInvalidateCatalog.ts`).
- «Cargar más» con `useInfiniteQuery`; favoritos con **mutaciones optimistas** (rollback de solo ese título; 409/404 = el estado ya es el correcto; se reafirma lo que confirma el servidor).
- **La caché se vacía en cada cambio de sesión** (`queryClient.clear()` en `AuthContext.applySession`), y el cambio se anuncia a las demás pestañas por `BroadcastChannel` (solo `{ type: 'session-changed' }`, sin datos), que vacían la suya y vuelven a preguntar quién es el usuario.
- Se quedan fuera, a propósito: el descubrimiento de la sesión (`AuthContext`), el buscador (efímero) y la carga inicial de los formularios de edición.

## Alternativas descartadas
- **Seguir con hooks propios:** cada pantalla nueva repetía el mismo código propenso a carreras, que había que probar a mano.
- **SWR:** cubre la caché, pero las mutaciones optimistas y las listas infinitas son más trabajo; TanStack Query es el estándar más extendido.
- **Redux Toolkit Query / Zustand:** traen un estado global que la app no necesita (los contextos bastan para la sesión y los avisos).

## Consecuencias
- (+) Volver a una pantalla visitada no repite peticiones; las escrituras del panel se ven al momento en los listados; menos código de carreras.
- (+) Lo que antes era una limitación conocida (otra pestaña con la identidad del usuario anterior) queda resuelto.
- (−) Una dependencia de producción más y un modelo mental nuevo (claves, `staleTime`, invalidación) que hay que conocer para tocar el frontend.
- (−) Tras editar, puede verse un instante el dato viejo mientras se refresca (comportamiento normal de la caché).
