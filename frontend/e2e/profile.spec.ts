/**
 * E2E de «Mi perfil» (`/perfil`): se llega desde el menú de usuario, enseña los
 * datos reales de la cuenta y un resumen de «Mi lista», distingue al
 * administrador y cabe en móvil sin scroll horizontal.
 *
 * No modifica el catálogo (solo añade favoritos a cuentas propias de cada test),
 * así que corre en el proyecto principal junto al resto.
 */
import type { Page } from '@playwright/test';
import { addFavoritesByTitle, addSeriesFavoritesByTitle, loginAdmin } from './support/api';
import { ADMIN_EMAIL, ADMIN_USERNAME } from './support/config';
import { VISIBLE_SERIES } from './support/catalog';
import { expect, sessionCookie, test } from './support/fixtures';

/** Abre el menú de usuario y entra en «Mi perfil», como haría una persona. */
async function openProfileFromMenu(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Menú de usuario' }).click();
  await page.getByRole('link', { name: 'Mi perfil' }).click();
}

test.describe('Mi perfil', () => {
  test('desde el menú de usuario llega al perfil, que enseña su nombre, rol y datos de la cuenta', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');

    await openProfileFromMenu(page);

    await expect(page).toHaveURL(/\/perfil$/);
    await expect(page).toHaveTitle('Mi perfil — StreamBox');
    await expect(page.getByRole('heading', { level: 1, name: user.username })).toBeVisible();
    await expect(page.getByText(/^Miembro desde [a-záéíóú]+ de \d{4}$/)).toBeVisible();
    // El menú se cierra al navegar.
    await expect(page.getByRole('button', { name: 'Menú de usuario' })).toHaveAttribute('aria-expanded', 'false');

    const account = page.getByRole('region', { name: 'Cuenta' });
    await expect(account.getByText(user.username)).toBeVisible();
    await expect(account.getByText(user.email)).toBeVisible();
    // La contraseña nunca se revela: puntos a la vista y «Oculta» para el lector de pantalla.
    await expect(account.getByText('••••••••')).toBeVisible();
    await expect(account.getByText('Oculta')).toBeAttached();

    // Un usuario normal no ve el acceso al panel ni se presenta como administrador.
    await expect(page.getByRole('link', { name: 'Panel de administración' })).toHaveCount(0);
    await expect(page.getByRole('main').getByText('Usuario', { exact: true })).toBeVisible();
    await expect(page.getByRole('main').getByText('Administrador')).toHaveCount(0);
  });

  test('con la lista vacía lo dice y ofrece explorar las películas del catálogo', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/perfil');

    await expect(page.getByRole('heading', { level: 1, name: user.username })).toBeVisible();
    await expect(page.getByText('Películas en mi lista')).toBeVisible();
    const genres = page.getByRole('region', { name: 'Tus géneros' });
    await expect(genres.getByText(/Tu lista está vacía/)).toBeVisible();
    await expect(page.getByRole('region', { name: 'De tu lista', exact: true })).toHaveCount(0);

    await genres.getByRole('link', { name: 'Explorar películas' }).click();
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Películas' })).toBeVisible();
  });

  test('resume la lista: recuentos, géneros más frecuentes y los títulos guardados, con enlace a «Mi lista»', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    // Dredd (Acción), Mad Max (Acción, Ciencia ficción), Interstellar (Ciencia ficción, Drama) y una serie.
    await addFavoritesByTitle(request, user.token, ['Dredd', 'Mad Max', 'Interstellar']);
    await addSeriesFavoritesByTitle(request, user.token, [VISIBLE_SERIES[0].title]);
    await signIn(user);
    await page.goto('/perfil');

    await expect(page.getByRole('heading', { level: 1, name: user.username })).toBeVisible();
    const stats = (label: string) => page.getByText(label, { exact: true }).locator('xpath=following-sibling::dd');
    await expect(stats('Películas en mi lista')).toHaveText('3');
    await expect(stats('Serie en mi lista')).toHaveText('1');
    await expect(stats('Géneros distintos')).toHaveText('3');

    // La serie sembrada es de Drama + Ciencia ficción: Acción 2, Ciencia ficción 3, Drama 2.
    const genres = page.getByRole('region', { name: 'Tus géneros' });
    await expect(genres.getByRole('listitem')).toHaveText([
      'Ciencia ficción3, 3 títulos',
      'Acción2, 2 títulos',
      'Drama2, 2 títulos',
    ]);

    const preview = page.getByRole('region', { name: 'De tu lista', exact: true });
    await expect(preview.getByRole('button', { name: /^Interstellar \d{4} ·/ })).toBeVisible();
    await expect(preview.getByRole('link', { name: new RegExp(`^${VISIBLE_SERIES[0].title} `) })).toBeVisible();

    // Una película abre su diálogo de detalles, sin salir del perfil.
    await preview.getByRole('button', { name: /^Interstellar \d{4} ·/ }).click();
    await expect(page.getByRole('dialog', { name: 'Interstellar' })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();

    await preview.getByRole('link', { name: 'Ver toda mi lista' }).click();
    await expect(page).toHaveURL(/\/favorites$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Mi lista' })).toBeVisible();
  });

  test('«Cerrar sesión» desde el perfil cierra la sesión y lleva al login', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/perfil');

    await page.getByRole('main').getByRole('button', { name: 'Cerrar sesión' }).click();

    await expect(page).toHaveURL(/\/login$/);
    // El cierre de sesión es optimista: la interfaz vuelve al login antes de que el servidor responda
    // al POST /api/auth/logout que borra la cookie. Se espera a que desaparezca en vez de mirar una vez.
    await expect.poll(() => sessionCookie(page)).toBeUndefined();
  });

  test('sin sesión, /perfil lleva al login', async ({ page }) => {
    await page.goto('/perfil');

    await expect(page).toHaveURL(/\/login$/);
  });

  test('el administrador ve su rol y el botón «Panel de administración», que lleva al panel', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/');

    await openProfileFromMenu(page);

    await expect(page.getByRole('heading', { level: 1, name: ADMIN_USERNAME })).toBeVisible();
    await expect(page.getByRole('main').getByText('Administrador')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Cuenta' }).getByText(ADMIN_EMAIL)).toBeVisible();

    await page.getByRole('link', { name: 'Panel de administración' }).click();
    await expect(page).toHaveURL(/\/admin\/peliculas$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Administración' })).toBeVisible();
  });
});

test.describe('Mi perfil en pantallas estrechas', () => {
  for (const width of [320, 375]) {
    test(`a ${width} px la página no se desplaza en horizontal y las tarjetas se apilan`, async ({
      page,
      request,
      user,
      signIn,
    }) => {
      await addFavoritesByTitle(request, user.token, ['Dredd', 'Mad Max', 'Interstellar']);
      await signIn(user);
      await page.setViewportSize({ width, height: 800 });
      await page.goto('/perfil');
      await expect(page.getByRole('region', { name: 'De tu lista', exact: true })).toBeVisible();

      const { scrollWidth, clientWidth } = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }));
      expect(scrollWidth, `scrollWidth (${scrollWidth}) debe ser ≤ clientWidth (${clientWidth})`).toBeLessThanOrEqual(clientWidth);

      // Apiladas: «Tus géneros» queda debajo de «Cuenta», no a su lado.
      const account = await page.getByRole('region', { name: 'Cuenta' }).boundingBox();
      const genres = await page.getByRole('region', { name: 'Tus géneros' }).boundingBox();
      expect(genres!.y).toBeGreaterThan(account!.y + account!.height - 1);
    });
  }
});
