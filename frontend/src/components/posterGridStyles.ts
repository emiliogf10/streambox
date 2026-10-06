/**
 * Rejilla de tarjetas de póster: dos columnas en móvil (a 375 px, dos tarjetas
 * de ~160 px) y, desde `sm`, tantas columnas de al menos 10.5rem como quepan.
 *
 * Está en un archivo aparte (como `buttonStyles.ts`) porque la usan varias
 * pantallas —«Mi lista», los resultados filtrados de `/peliculas` y su
 * esqueleto de carga— y deben verse idénticas: si cambia aquí, cambia en todas.
 */
export const POSTER_GRID_CLASS = 'grid grid-cols-2 gap-4 sm:grid-cols-[repeat(auto-fill,minmax(10.5rem,1fr))]';
