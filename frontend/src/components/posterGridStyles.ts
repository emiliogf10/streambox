/**
 * Rejilla de tarjetas de póster: dos columnas en móvil (a 375 px, dos tarjetas
 * de ~160 px) y, desde `sm`, tantas columnas de al menos 10.5rem como quepan.
 *
 * Está en un archivo aparte (como `buttonStyles.ts`) porque la usan varias
 * pantallas —«Mi lista», los resultados filtrados de `/peliculas` y su
 * esqueleto de carga— y deben verse idénticas: si cambia aquí, cambia en todas.
 */
export const POSTER_GRID_CLASS = 'grid grid-cols-2 gap-4 sm:grid-cols-[repeat(auto-fill,minmax(10.5rem,1fr))]';

/**
 * Rejilla compacta de los resúmenes del perfil («De tu lista» y «Te gusta»):
 * más densa que la de una pantalla de catálogo, porque ahí los pósters son un
 * resumen, y LA MISMA en las dos secciones para que se lean como una sola
 * familia (D-5). Tres columnas hasta `md` y seis desde ahí: con 6 o 12 títulos
 * (lo que enseñan las dos) las filas quedan siempre completas.
 */
export const COMPACT_POSTER_GRID_CLASS = 'grid grid-cols-3 gap-3 md:grid-cols-6';
