import react from '@vitejs/plugin-react'
// `defineConfig` de vitest/config es el de Vite más el tipo del bloque `test`.
import { defineConfig } from 'vitest/config'

/**
 * Destino del proxy de `/api`. Por defecto, el backend de desarrollo en el 8080.
 *
 * Solo lo sobrescribe la suite E2E (`playwright.config.ts` pone
 * `VITE_API_PROXY_TARGET=http://localhost:8099`): así el navegador de las
 * pruebas habla con un backend efímero y aislado y nunca con el del
 * desarrollador (puerto 8080, con su base de datos real). Sin la variable, el
 * comportamiento es exactamente el de siempre.
 */
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    // jsdom simula el DOM del navegador; sin él no se pueden renderizar componentes.
    environment: 'jsdom',
    // Se ejecuta antes de cada archivo de test (polyfills de jsdom, matchers, limpieza).
    setupFiles: ['./src/test/setup.ts'],
    // Los tests importan `describe/it/expect/vi` de 'vitest' de forma explícita:
    // no se activan los globales para no contaminar los tipos de la aplicación.
    include: ['src/**/*.test.{ts,tsx}'],
    // Restaura los espías y vuelve a limpiar los mocks entre tests: ninguno depende del anterior.
    restoreMocks: true,
    unstubGlobals: true,
  },
})
