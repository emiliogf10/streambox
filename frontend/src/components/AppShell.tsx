import { Outlet } from 'react-router-dom';
import { FavoritesProvider } from '../context/FavoritesContext';
import { MAIN_CONTENT_ID } from './SkipLink';
import { Navbar } from './Navbar';

/**
 * Estructura de la zona autenticada: barra de navegación + contenido de la ruta
 * hija (`<Outlet />`) dentro de `<main>`, el landmark principal de la página.
 * La barra es `sticky`, por lo que el contenido no necesita margen superior; lo
 * que sí la tiene en cuenta es el desplazamiento hasta el elemento enfocado
 * (`scroll-margin-top` de todo lo que hay en `<main>`, ver `index.css`).
 *
 * Se usa como "ruta de diseño" en `App.tsx`, así que se monta UNA vez al entrar
 * y no se destruye al navegar entre páginas. Por eso aquí vive también el
 * {@link FavoritesProvider}: la lista "Mi lista" se carga una sola vez y se
 * comparte entre la portada, el buscador y la página "Mi lista".
 */
export function AppShell() {
  return (
    <FavoritesProvider>
      <div className="min-h-screen bg-canvas text-white">
        <Navbar />
        {/* tabIndex -1: destino enfocable por código del enlace "Saltar al contenido"; no es una parada de tabulador. */}
        <main id={MAIN_CONTENT_ID} tabIndex={-1} className="outline-hidden">
          <Outlet />
        </main>
      </div>
    </FavoritesProvider>
  );
}
