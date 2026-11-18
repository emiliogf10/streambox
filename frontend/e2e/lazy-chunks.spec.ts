/**
 * E2E de las pantallas que se cargan bajo demanda (`React.lazy` en `App.tsx`) cuando su código NO llega
 * (revisión de `qa`, 2026-11): una pestaña abierta durante un despliegue pide el trozo con el hash viejo, que
 * el servidor ya no tiene, o se corta la red al navegar. Se simula abortando la petición de ese módulo
 * (`page.route`): con Vite en desarrollo, el del E2E, es `/src/pages/…/<Pantalla>.tsx`; en una compilación,
 * `/assets/<Pantalla>-<hash>.js`. El patrón cubre los dos.
 *
 * Sin `LazyRouteBoundary` la aplicación entera se desmontaba: `#root` vacío, página en blanco (y una
 * excepción no capturada, que `failOnPageErrors` también convierte en fallo).
 *
 * También comprueba el foco al llegar navegando a una pantalla diferida (con y sin fallo): la espera y la
 * pantalla (o el aviso) traen cada una su `<main>`, y el foco debe acabar en el nuevo, no en `<body>`.
 */
import { loginAdmin } from './support/api';
import { expect, test, waitForLoginPage } from './support/fixtures';

test.describe('Pantallas bajo demanda cuyo código no llega', () => {
  test('el panel: aviso con la barra a la vista y, al recargar, el panel ya funciona', async ({ page, request, signIn }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.route('**/adminPages*', (route) => route.abort());
    await page.goto('/');

    const nav = page.getByRole('navigation', { name: 'Principal' });
    await nav.getByRole('link', { name: 'Administrar' }).click();

    const main = page.getByRole('main');
    await expect(main.getByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' })).toBeVisible();
    await expect(main.getByRole('alert')).toContainText(
      'Puede que se haya cortado la conexión o que StreamBox se haya actualizado mientras tenías la página abierta.',
    );
    // No es una página en blanco: la barra sigue ahí y se puede ir a otra sección.
    await expect(nav.getByRole('link', { name: 'Inicio' })).toBeVisible();
    // Como en cualquier cambio de página, el foco está en el contenido (`RouteChangeFocus`), no perdido en <body>.
    await expect(main).toBeFocused();

    // Ya hay red (o versión nueva): «Recargar la página» lo arregla, que es lo que promete el botón.
    await page.unroute('**/adminPages*');
    await main.getByRole('button', { name: 'Recargar la página' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Administración' })).toBeVisible();
  });

  test('sin fallos: al llegar navegando por primera vez a una pantalla de cuenta, el foco queda en su <main>', async ({ page }) => {
    // Con la descarga a medias React pinta la espera (con su <main>, que recibe el foco) y luego la pantalla, que
    // trae el suyo. Antes el foco caía en <body> la primera vez (con el trozo ya descargado no pasaba).
    await page.goto('/login');
    await waitForLoginPage(page);

    await page.getByRole('link', { name: '¿Has olvidado tu contraseña?' }).click();

    await expect(page.getByRole('heading', { level: 1, name: '¿Has olvidado tu contraseña?' })).toBeVisible();
    await expect(page.getByRole('main')).toBeFocused();
  });

  test('una pantalla de cuenta (registro): aviso en lugar de una página en blanco', async ({ page }) => {
    await page.route('**/RegisterPage*', (route) => route.abort());
    await page.goto('/login');
    await waitForLoginPage(page);

    await page.getByRole('link', { name: 'Regístrate' }).click();

    const main = page.getByRole('main');
    await expect(main.getByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' })).toBeVisible();
    await expect(main.getByRole('button', { name: 'Recargar la página' })).toBeVisible();
    await expect(page).toHaveURL(/\/registro$/);
    // El aviso trae su propio <main> (el de la espera desaparece): el foco pasa a él, no se pierde en <body>.
    await expect(main).toBeFocused();

    await page.unroute('**/RegisterPage*');
    await main.getByRole('button', { name: 'Recargar la página' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeVisible();
  });
});
