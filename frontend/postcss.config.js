/**
 * Configuración de PostCSS: Vite la lee para procesar `src/index.css`.
 *
 * Tailwind v4 se ejecuta como plugin de PostCSS (`@tailwindcss/postcss`) y se
 * configura en el propio CSS (`@theme` en `src/index.css`), no en un
 * `tailwind.config.js`. No hace falta `autoprefixer`: Tailwind v4 ya añade los
 * prefijos de proveedor que necesita.
 */
export default {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};
