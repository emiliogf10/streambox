/**
 * Todas las pantallas del panel de administración, reunidas en un solo módulo
 * para cargarlas BAJO DEMANDA y de una vez (revisión de 2026-11, M3).
 *
 * `App.tsx` no las importa directamente: las pide con `import()` a este módulo
 * (con `React.lazy`), así que Vite las saca del paquete principal a un trozo
 * aparte junto con lo que solo usa el panel (formularios, validaciones de
 * película, serie y episodio, tablas). Un USER, que nunca puede entrar en el
 * panel, ya no descarga, analiza ni compila ese código al abrir la aplicación.
 *
 * Por qué un solo módulo y no un `import()` por pantalla: el panel se recorre
 * entero (listado → alta → edición), las pantallas comparten casi todo su
 * código y un único trozo evita una cascada de descargas (primero la
 * estructura del panel y después la de cada sección).
 */
export { AdminGenresPage } from './AdminGenresPage';
export { AdminLayout } from './AdminLayout';
export { AdminMoviesPage } from './AdminMoviesPage';
export { AdminSeriesPage } from './AdminSeriesPage';
export { MovieFormPage } from './MovieFormPage';
export { SeriesFormPage } from './SeriesFormPage';
