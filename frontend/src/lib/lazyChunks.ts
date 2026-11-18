/**
 * Carga de las pantallas que van en su propio trozo de JavaScript (`React.lazy`
 * + `import()` en `App.tsx`) y lo que pasa cuando esa descarga falla.
 *
 * **El problema (revisión de `qa`, 2026-11).** Con el *code-splitting*, abrir
 * `/registro` o `/admin` descarga un archivo `/assets/<nombre>-<hash>.js`. Si
 * esa descarga falla, `React.lazy` lanza el error al pintar y, sin un límite de
 * errores, React desmonta TODA la aplicación: la página se queda en blanco. Pasa
 * de verdad en dos casos:
 * - **Un despliegue con la pestaña abierta:** el JavaScript viejo pide el trozo
 *   con el hash viejo, que el servidor ya no tiene (404).
 * - **Un corte de red** justo al navegar.
 *
 * Lo recoge `components/LazyRouteBoundary`, que necesita distinguir «no se pudo
 * DESCARGAR la pantalla» de «la pantalla falló al pintarse» para decir la verdad
 * en cada caso. El mensaje del error no sirve para eso (cada navegador redacta
 * el suyo: «Failed to fetch dynamically imported module», «Importing a module
 * script failed»...), así que se marca en el origen: toda carga pasa por
 * {@link importChunk}, que envuelve cualquier rechazo del `import()` en un
 * {@link ChunkLoadError}. Se decide por el tipo, no por el texto.
 */

/**
 * Error de descarga (o de evaluación) del módulo de una pantalla diferida. El
 * error original va en `cause` (lo ve quien depura en la consola).
 */
export class ChunkLoadError extends Error {
  constructor(cause: unknown) {
    super('No se pudo descargar el código de esta pantalla.', { cause });
    this.name = 'ChunkLoadError';
  }
}

/**
 * Ejecuta un `import()` dinámico y, si falla, rechaza con un {@link ChunkLoadError}.
 *
 * Recibe una FUNCIÓN con el `import()` literal dentro (no el módulo ni la
 * ruta): así Vite sigue viendo `import('./pages/…')` y genera el trozo aparte.
 *
 * No reintenta: un `import()` que ha fallado queda así en la caché de módulos
 * del navegador (y `React.lazy` guarda la promesa rechazada), de modo que lo
 * único que lo arregla es recargar la página. Por eso el aviso ofrece
 * «Recargar la página» y no «Reintentar».
 */
export function importChunk<T>(load: () => Promise<T>): Promise<T> {
  return load().catch((cause: unknown) => {
    throw new ChunkLoadError(cause);
  });
}

/**
 * Recarga la página completa: descarga el `index.html` y los trozos de la
 * versión que haya ahora en el servidor. Aparte para que los tests puedan
 * sustituirla (jsdom no implementa `location.reload`).
 */
export function reloadPage(): void {
  window.location.reload();
}
