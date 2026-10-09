/**
 * E2E de «Mi perfil» (`/perfil`): se llega desde el menú de usuario, enseña los
 * datos reales de la cuenta y un resumen de «Mi lista», distingue al
 * administrador y cabe en móvil sin scroll horizontal. «Editar perfil»: cambiar
 * el nombre (se ve en la barra), cambiar la contraseña (esta sesión sigue, las
 * demás no se renuevan, solo vale la nueva) y cerrar sesión en todos los dispositivos.
 *
 * No modifica el catálogo (solo añade favoritos a cuentas propias de cada test),
 * así que corre en el proyecto principal junto al resto.
 */
import type { APIRequestContext, Page } from '@playwright/test';
import { addFavoritesByTitle, addSeriesFavoritesByTitle, loginAdmin } from './support/api';
import { ADMIN_EMAIL, ADMIN_USERNAME, BACKEND_URL } from './support/config';
import { VISIBLE_SERIES } from './support/catalog';
import { expect, formAlert, sessionCookie, test } from './support/fixtures';

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
    // La contraseña nunca se revela (ni con puntos, que parecían un campo); el correo no se puede cambiar.
    await expect(account.getByText('No se muestra por seguridad.')).toBeVisible();
    await expect(account.getByText('No se puede cambiar: es el dato con el que inicias sesión.')).toBeVisible();
    await expect(account.getByRole('button')).toHaveCount(2);

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

    await page.getByRole('main').getByRole('button', { name: 'Cerrar sesión', exact: true }).click();

    await expect(page).toHaveURL(/\/login$/);
    // El cierre NO es optimista: solo se llega al login tras la respuesta del servidor al
    // POST /api/auth/logout, cuyo Set-Cookie ya ha borrado la cookie.
    expect(await sessionCookie(page)).toBeUndefined();
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

/**
 * Pide una renovación con el refresh token que guardó el `request` de Playwright
 * al registrar al usuario (`createUserWithToken` inicia sesión por API): hace de
 * «otro dispositivo» con su propia sesión. Devuelve el estado HTTP.
 */
async function refreshFromOtherDevice(request: APIRequestContext): Promise<number> {
  const response = await request.post(`${BACKEND_URL}/api/auth/refresh`, {
    headers: { 'X-Requested-With': 'StreamBox' },
  });
  return response.status();
}

/** Inicia sesión con el formulario, como una persona. */
async function loginWithForm(page: Page, email: string, password: string): Promise<void> {
  await page.getByLabel('Correo electrónico').fill(email);
  await page.getByLabel('Contraseña').fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
}

test.describe('Editar perfil', () => {
  test('cambiar el nombre lo actualiza al instante en el perfil y en la barra', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/perfil');
    const newName = `nuevo_${user.username.slice(-10)}`;

    const account = page.getByRole('region', { name: 'Cuenta' });
    await account.getByRole('button', { name: 'Cambiar nombre' }).click();
    const input = account.getByRole('textbox', { name: 'Nuevo nombre de usuario' });
    await expect(input).toBeFocused();
    await input.fill(`  ${newName}  `);
    await account.getByRole('button', { name: 'Guardar' }).click();

    await expect(page.getByRole('heading', { level: 1, name: newName })).toBeVisible();
    await expect(page.getByText(`Nombre de usuario cambiado a «${newName}».`)).toBeVisible();
    await expect(account.getByRole('button', { name: 'Cambiar nombre' })).toBeFocused();
    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await expect(page.getByText('Sesión iniciada como').locator('xpath=following-sibling::p[1]')).toHaveText(newName);

    // Lo ha guardado el servidor: sigue ahí al recargar.
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name: newName })).toBeVisible();
  });

  test('cambiar la contraseña mantiene esta sesión, cierra las demás y solo vale la nueva', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    const newPassword = 'Bruma-Cometa-Atril-82';
    await signIn(user);
    await page.goto('/perfil');

    const account = page.getByRole('region', { name: 'Cuenta' });
    await account.getByRole('button', { name: 'Cambiar contraseña' }).click();
    // Primero con la actual mal: el error va junto a su campo y la sesión sigue.
    await page.getByLabel('Contraseña actual').fill('no-es-la-mia-123');
    await page.getByLabel('Nueva contraseña', { exact: true }).fill(newPassword);
    await page.getByLabel('Repite la nueva contraseña').fill(newPassword);
    const form = page.getByRole('form', { name: 'Cambiar contraseña' });
    await form.getByRole('button', { name: 'Cambiar contraseña' }).click();
    await expect(page.getByLabel('Contraseña actual')).toHaveAccessibleDescription('La contraseña actual no es correcta');
    await expect(page.getByLabel('Contraseña actual')).toBeFocused();

    await page.getByLabel('Contraseña actual').fill(user.password);
    await form.getByRole('button', { name: 'Cambiar contraseña' }).click();

    await expect(
      page.getByText(
        'Contraseña cambiada. Se han cerrado tus otras sesiones; en otros dispositivos puede tardar hasta 15 minutos.',
      ),
    ).toBeVisible();
    // Esta sesión sigue (con cookies nuevas) y la del «otro dispositivo» ya no se puede renovar.
    await page.reload();
    await expect(page.getByRole('heading', { level: 1, name: user.username })).toBeVisible();
    expect(await refreshFromOtherDevice(request)).toBe(401);

    // Se sale y se vuelve a entrar: la vieja ya no vale; la nueva sí.
    await page.getByRole('main').getByRole('button', { name: 'Cerrar sesión', exact: true }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginWithForm(page, user.email, user.password);
    await expect(formAlert(page)).toContainText('Correo o contraseña incorrectos.');
    await page.getByLabel('Contraseña').fill(newPassword);
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('«Cerrar sesión en todos los dispositivos» pide confirmación, cierra esta y las demás y lleva al login', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/perfil');

    await page.getByRole('button', { name: 'Cerrar sesión en todos los dispositivos' }).click();
    const dialog = page.getByRole('alertdialog', { name: '¿Cerrar sesión en todos los dispositivos?' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Cancelar' })).toBeFocused();
    // Cancelar no cierra nada.
    await dialog.getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByRole('heading', { level: 1, name: user.username })).toBeVisible();

    await page.getByRole('button', { name: 'Cerrar sesión en todos los dispositivos' }).click();
    await dialog.getByRole('button', { name: 'Cerrar todas las sesiones' }).click();

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByText(
        'Se ha cerrado la sesión en todos tus dispositivos. En los demás puede tardar hasta 15 minutos en cerrarse.',
      ),
    ).toBeVisible();
    expect(await sessionCookie(page)).toBeUndefined();
    expect(await refreshFromOtherDevice(request)).toBe(401);
  });

  test('a 375 px, con el formulario de contraseña abierto, la página no se desplaza en horizontal', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto('/perfil');

    const account = page.getByRole('region', { name: 'Cuenta' });
    await account.getByRole('button', { name: 'Cambiar contraseña' }).click();
    await expect(page.getByLabel('Repite la nueva contraseña')).toBeVisible();

    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }));
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
  });
});
