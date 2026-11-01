/**
 * E2E de los géneros favoritos («Tus gustos») y la pregunta de bienvenida.
 *
 * Por qué existen: una cuenta nueva no veía recomendaciones hasta marcar algún «Me gusta» (el «arranque
 * en frío»). Ahora, tras su primer inicio de sesión, se le pregunta qué géneros le gustan (de 3 a 5) y el
 * inicio le recomienda títulos de esos géneros desde el primer minuto.
 *
 * Protegen, de punta a punta y contra el backend real:
 * - que un USER nuevo llega a `/bienvenida` tras el login, elige 3 géneros y el inicio le recomienda títulos
 *   SIN ningún «Me gusta», diciendo por qué;
 * - que «Ahora no» vuelve a donde iba, cierra la pregunta para siempre y el inicio invita a elegirlos
 *   (la tarjeta lleva a «Tus gustos» del perfil);
 * - elegir, editar y quitar los gustos desde el perfil, y que el inicio cambia en consecuencia;
 * - que un ADMIN no ve la pregunta pero sí la sección, y que las páginas públicas nunca desvían.
 *
 * El catálogo sembrado tiene justo tres géneros con títulos (`GENRES` de `support/catalog.ts`): se eligen
 * esos tres. El máximo de 5 y el resto de reglas del selector los prueban los tests de Vitest.
 *
 * Los usuarios de aquí se crean con la pregunta PENDIENTE (`genresOnboarding: 'pending'` o registrándolos a
 * mano); el resto de specs usa usuarios con la pregunta ya cerrada (ver `createUserWithToken`).
 */
import type { Locator } from '@playwright/test';
import {
  createUserWithToken,
  favoriteGenresApi,
  genresOnboardingPendingApi,
  loginAdmin,
  newTestUser,
  registerVerifiedUser,
} from './support/api';
import { GENRES } from './support/catalog';
import { WELCOME_TITLE, expect, openHomeAndWaitForRecommendations, test } from './support/fixtures';

/** Los tres géneros del catálogo sembrado, en el orden en que los devuelve el servidor (por nombre). */
const SEED_GENRES = [...GENRES].sort((a, b) => a.localeCompare(b, 'es'));

/** Frase bajo las filas recomendadas cuando se basan solo en esos géneros. */
const REASON = `Porque te gustan ${new Intl.ListFormat('es', { type: 'conjunction' }).format(SEED_GENRES)}.`;

/** Título de la tarjeta del inicio que invita a elegir géneros. */
const INVITE_TITLE = 'Dinos qué géneros te gustan y te recomendaremos títulos';

/** Marca un género pulsando su etiqueta visible (la casilla real está oculta a la vista, no al teclado). */
async function pick(group: Locator, name: string): Promise<void> {
  await group.getByText(name, { exact: true }).click();
  await expect(group.getByRole('checkbox', { name })).toBeChecked();
}

test.describe('Pregunta de bienvenida', () => {
  test('usuario nuevo: tras el login va a /bienvenida, elige 3 géneros y el inicio le recomienda títulos de esos géneros sin ningún «Me gusta»', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill(user.password);
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();

    // Llega a la pregunta (sin la barra superior) y el foco está en su título.
    await expect(page).toHaveURL(/\/bienvenida$/);
    await expect(page.getByRole('heading', { level: 1, name: WELCOME_TITLE })).toBeFocused();
    await expect(page.getByRole('navigation', { name: 'Principal' })).toHaveCount(0);
    const save = page.getByRole('button', { name: 'Guardar' });
    await expect(save).toHaveAttribute('aria-disabled', 'true');
    await expect(page.getByRole('button', { name: 'Ahora no' })).toBeVisible();

    // Dos con el ratón y el tercero con el teclado (Espacio sobre la casilla).
    const group = page.getByRole('group', { name: 'Tus géneros favoritos' });
    await pick(group, SEED_GENRES[0]);
    await pick(group, SEED_GENRES[1]);
    await expect(group.getByRole('status')).toHaveText('Has elegido 2 de 5. Elige al menos 1 más.');
    await group.getByRole('checkbox', { name: SEED_GENRES[2] }).focus();
    await page.keyboard.press('Space');
    await expect(group.getByRole('checkbox', { name: SEED_GENRES[2] })).toBeChecked();
    await expect(group.getByRole('status')).toHaveText('Has elegido 3 de 5. Ya puedes guardar.');
    await expect(save).not.toHaveAttribute('aria-disabled');

    await save.click();
    await expect(page.getByText('Hemos guardado tus gustos. Puedes cambiarlos cuando quieras en tu perfil.')).toBeVisible();
    await expect(page).toHaveURL(/\/$/);

    // Sin ningún «Me gusta», el inicio ya recomienda y dice por qué.
    const recommended = page.getByRole('region', { name: 'Películas recomendadas para ti' });
    await expect(recommended).toBeVisible();
    await expect(recommended).toHaveAccessibleDescription(REASON);
    await expect(recommended.getByRole('link').first()).toBeVisible();
    await expect(page.getByRole('region', { name: INVITE_TITLE })).toHaveCount(0);

    // Una sola vez: al recargar ya no se pregunta y el servidor responde que se basan en los géneros.
    expect(await openHomeAndWaitForRecommendations(page)).toBe('GENRES');
    await expect(page).toHaveURL(/\/$/);
  });

  test('«Ahora no»: vuelve a donde iba, no vuelve a preguntar y el inicio invita a elegir géneros desde el perfil', async ({
    page,
    request,
    signIn,
  }) => {
    const user = await createUserWithToken(request, { genresOnboarding: 'pending' });
    expect(await genresOnboardingPendingApi(request, user.token)).toBe(true);
    await signIn(user);

    // Iba a /peliculas: la guarda le desvía a la pregunta y, al saltarla, vuelve allí.
    await page.goto('/peliculas');
    await expect(page.getByRole('heading', { level: 1, name: WELCOME_TITLE })).toBeVisible();
    await page.getByRole('button', { name: 'Ahora no' }).click();
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(
      page.getByText('De acuerdo. Puedes elegir tus géneros cuando quieras en tu perfil, en «Tus gustos».'),
    ).toBeVisible();
    expect(await genresOnboardingPendingApi(request, user.token)).toBe(false);
    expect(await favoriteGenresApi(request, user.token)).toEqual([]);

    // El inicio no tiene filas recomendadas, sino la tarjeta (y al recargar no se vuelve a preguntar).
    expect(await openHomeAndWaitForRecommendations(page)).toBe('NONE');
    await expect(page).toHaveURL(/\/$/);
    const invite = page.getByRole('region', { name: INVITE_TITLE });
    await expect(invite).toBeVisible();
    await expect(page.getByRole('region', { name: /recomendadas para ti$/ })).toHaveCount(0);

    // La tarjeta lleva a «Tus gustos» del perfil, con el foco en la sección.
    await invite.getByRole('link', { name: 'Elegir mis géneros' }).click();
    await expect(page).toHaveURL(/\/perfil#tus-gustos$/);
    await expect(page.getByRole('heading', { level: 2, name: 'Tus gustos' })).toBeFocused();
    await expect(page.getByRole('region', { name: 'Tus gustos' }).getByRole('button', { name: 'Elegir géneros' })).toBeVisible();
  });

  test('las páginas públicas nunca desvían a la bienvenida, aunque la sesión la tenga pendiente', async ({
    page,
    request,
    signIn,
  }) => {
    const user = await createUserWithToken(request, { genresOnboarding: 'pending' });
    await signIn(user);

    for (const path of ['/verificar-email', '/recuperar-contrasena', '/restablecer-contrasena']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(page).toHaveURL(new RegExp(`${path}$`));
      await expect(page.getByRole('heading', { level: 1, name: WELCOME_TITLE })).toHaveCount(0);
    }
    expect(await genresOnboardingPendingApi(request, user.token)).toBe(true);
  });

  test('un ADMIN nunca ve la pregunta, pero sí tiene la sección «Tus gustos» en su perfil', async ({ page, request, signIn }) => {
    const token = await loginAdmin(request);
    expect(await genresOnboardingPendingApi(request, token)).toBe(false);
    await signIn({ token });

    await page.goto('/');
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { level: 1, name: WELCOME_TITLE })).toHaveCount(0);

    // Solo se mira (el administrador es común a todos los tests: no se le cambian los gustos).
    await page.goto('/perfil');
    const tastes = page.getByRole('region', { name: 'Tus gustos' });
    await expect(tastes).toBeVisible();
    await expect(tastes.getByRole('button', { name: /^(Elegir géneros|Editar)$/ })).toBeVisible();
  });
});

test.describe('«Tus gustos» en el perfil', () => {
  test('elegir, editar (cancelar) y quitar los gustos; el inicio pasa de recomendar por géneros a invitar a elegirlos', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    // Recorrido largo (elegir, editar, cancelar, quitar con confirmación y dos cargas de la portada). Con la
    // traza de Playwright (`retain-on-failure`, se graba siempre) el cierre del contexto guarda instantáneas de
    // páginas grandes y, en una máquina cargada, la suma pasaba de los 30 s («Tearing down "context" exceeded the
    // test timeout») con todas las comprobaciones ya en verde. Triple de tiempo, como el recorrido de
    // `likes.spec.ts`; no se quita ninguna comprobación.
    test.slow();
    await signIn(user);
    await page.goto('/perfil');
    const tastes = page.getByRole('region', { name: 'Tus gustos' });
    await expect(tastes.getByText('Aún no has elegido tus géneros favoritos')).toBeVisible();

    // Elegir: el mismo selector que la bienvenida.
    await tastes.getByRole('button', { name: 'Elegir géneros' }).click();
    const group = tastes.getByRole('group', { name: 'Elige entre 3 y 5 géneros' });
    await expect(group).toBeFocused();
    for (const name of SEED_GENRES) await pick(group, name);
    await tastes.getByRole('button', { name: 'Guardar' }).click();
    await expect(page.getByText('Hemos guardado tus gustos. Ya cuentan para tus recomendaciones.')).toBeVisible();
    await expect(tastes.getByRole('listitem')).toHaveText(SEED_GENRES);
    await expect(tastes.getByRole('button', { name: 'Editar' })).toBeFocused();
    expect((await favoriteGenresApi(request, user.token)).map((genre) => genre.name)).toEqual(SEED_GENRES);

    // Editar y cancelar: no cambia nada y el foco vuelve a «Editar».
    await tastes.getByRole('button', { name: 'Editar' }).click();
    await expect(tastes.getByRole('group', { name: 'Elige entre 3 y 5 géneros' })).toBeFocused();
    await tastes.getByText(SEED_GENRES[0], { exact: true }).click();
    await expect(tastes.getByRole('button', { name: 'Guardar' })).toHaveAttribute('aria-disabled', 'true');
    await tastes.getByRole('button', { name: 'Cancelar' }).click();
    await expect(tastes.getByRole('button', { name: 'Editar' })).toBeFocused();
    await expect(tastes.getByRole('listitem')).toHaveText(SEED_GENRES);

    // El inicio recomienda por esos géneros.
    expect(await openHomeAndWaitForRecommendations(page)).toBe('GENRES');
    await expect(page.getByRole('region', { name: 'Películas recomendadas para ti' })).toHaveAccessibleDescription(REASON);

    // Quitar, con confirmación.
    await page.goto('/perfil');
    await tastes.getByRole('button', { name: 'Quitar mis gustos' }).click();
    const dialog = page.getByRole('alertdialog', { name: '¿Quitar tus gustos?' });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Quitar mis gustos' }).click();
    await expect(page.getByText('Has quitado tus gustos. Puedes volver a elegirlos cuando quieras.')).toBeVisible();
    await expect(tastes.getByText('Aún no has elegido tus géneros favoritos')).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Tus gustos' })).toBeFocused();
    expect(await favoriteGenresApi(request, user.token)).toEqual([]);

    // Y el inicio vuelve a la invitación.
    expect(await openHomeAndWaitForRecommendations(page)).toBe('NONE');
    await expect(page.getByRole('region', { name: INVITE_TITLE })).toBeVisible();
  });
});
