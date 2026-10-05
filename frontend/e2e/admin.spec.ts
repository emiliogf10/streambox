/**
 * E2E del panel de administración: acceso (solo ADMIN), listado paginado con
 * búsqueda, edición de una película que no existe y pestaña de géneros
 * (crear, renombrar, borrar y los dos 409).
 *
 * Nada de este archivo cambia el CATÁLOGO de películas que comparten todos los
 * tests (banner, totales, filas): solo lo lee. Los géneros que crea son propios
 * (nombre único), no tienen películas y se borran al terminar aunque el test
 * falle. El alta, edición y borrado de películas está en `admin-peliculas.spec.ts`,
 * que se ejecuta aparte, cuando ya ha terminado el resto (ver `playwright.config.ts`).
 */
import { randomUUID } from 'node:crypto';
import type { Page } from '@playwright/test';
import { deleteGenresNamed, loginAdmin } from './support/api';
import { HERO_TITLE, MOVIES } from './support/catalog';
import { escapeRegExp, expect, test } from './support/fixtures';

/** Fila de la tabla del panel cuya película se titula exactamente `title` (el nombre de la fila empieza por él). */
function movieRow(page: Page, title: string) {
  return page.getByRole('row', { name: new RegExp(`^${escapeRegExp(title)}(\\s|$)`) });
}

test.describe('Acceso al panel', () => {
  test('el administrador ve «Administrar» y llega al listado paginado del catálogo', async ({ page, request, signIn }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/');

    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Administrar' }).click();

    await expect(page).toHaveURL(/\/admin\/peliculas$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Administración' })).toBeVisible();
    await expect(page).toHaveTitle('Películas · Administración — StreamBox');
    const sections = page.getByRole('navigation', { name: 'Secciones de administración' });
    await expect(sections.getByRole('link', { name: 'Películas' })).toHaveAttribute('aria-current', 'page');

    // 25 películas en páginas de 10; la primera fila es la más reciente (la del banner de la portada).
    const pages = Math.ceil(MOVIES.length / 10);
    await expect(page.getByRole('table', { name: `Películas del catálogo, página 1 de ${pages}` })).toBeVisible();
    await expect(page.getByRole('table').getByRole('row').nth(1)).toHaveAccessibleName(new RegExp(`^${escapeRegExp(HERO_TITLE)}`));
    const pagination = page.getByRole('navigation', { name: 'Paginación de películas' });
    await expect(pagination).toContainText(`Página 1 de ${pages}`);
    await expect(pagination).toContainText(`${MOVIES.length} películas`);

    // Las portadas salen de imageUrl (portadas propias /covers/...).
    await expect(movieRow(page, HERO_TITLE).locator('img')).toHaveAttribute('src', /^\/covers\/[A-Za-z0-9][\w.-]*$/);

    // Paginación: la página va en la URL y «Anterior» deja de estar disponible en la primera.
    await expect(pagination.getByRole('button', { name: 'Anterior' })).toHaveAttribute('aria-disabled', 'true');
    await pagination.getByRole('button', { name: 'Siguiente' }).click();
    await expect(page).toHaveURL(/\/admin\/peliculas\?page=2$/);
    await expect(pagination).toContainText(`Página 2 de ${pages}`);
    await expect(page.getByRole('table')).toHaveAccessibleName(`Películas del catálogo, página 2 de ${pages}`);
  });

  test('el buscador filtra por título (sin una petición por letra) y deja la búsqueda en la URL', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/admin/peliculas');
    await expect(page.getByRole('table')).toBeVisible();

    const searches: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/movies/search')) searches.push(req.url());
    });
    await page.getByRole('searchbox', { name: 'Buscar por título' }).pressSequentially('Blade', { delay: 30 });

    await expect(page).toHaveURL(/\?q=Blade$/);
    await expect(page.getByRole('table')).toHaveAccessibleName('Resultados para «Blade», página 1 de 1');
    // Orden alfabético: «Blade Runner» antes que «Blade Runner 2049».
    const rows = page.getByRole('table').getByRole('row');
    await expect(rows).toHaveCount(3); // cabecera + 2
    await expect(rows.nth(1)).toHaveAccessibleName(/^Blade Runner(\s|$)/);
    await expect(rows.nth(2)).toHaveAccessibleName(/^Blade Runner 2049/);
    // El debounce agrupa las cinco letras en una sola búsqueda.
    expect(searches).toHaveLength(1);

    // Recargar conserva la búsqueda (vive en la URL).
    await page.reload();
    await expect(page.getByRole('searchbox', { name: 'Buscar por título' })).toHaveValue('Blade');
    await expect(page.getByRole('table')).toHaveAccessibleName('Resultados para «Blade», página 1 de 1');
  });

  test('un usuario normal que navega a /admin acaba en la portada y no ve «Administrar»', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/admin/peliculas');

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Catálogo de películas' })).toBeAttached();
    // Primero se espera a que el usuario esté cargado (su nombre en el menú): así la ausencia del enlace significa algo.
    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await expect(page.getByText(user.username, { exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Administrar' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Administración' })).toHaveCount(0);
  });

  test('editar una película que no existe explica que no se encontró y enlaza al listado', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/admin/peliculas/999999/editar');

    await expect(page.getByRole('heading', { level: 2, name: 'No se encontró la película' })).toBeVisible();
    await page.getByRole('main').getByRole('link', { name: 'Volver al listado' }).last().click();
    await expect(page).toHaveURL(/\/admin\/peliculas$/);
  });
});

test.describe('Géneros', () => {
  /** Géneros creados por el test en curso: se borran al terminar, pase lo que pase. */
  const created: string[] = [];

  test.afterEach(async ({ request }) => {
    if (created.length === 0) return;
    await deleteGenresNamed(request, await loginAdmin(request), created.splice(0));
  });

  test('crea (con el nombre normalizado), renombra en línea y borra un género con confirmación', async ({
    page,
    request,
    signIn,
  }) => {
    const suffix = randomUUID().slice(0, 8); // hexadecimal en minúsculas: no cambia al normalizar
    const typed = `PRUEBA ${suffix.toUpperCase()}`;
    const normalized = `Prueba ${suffix}`; // el servidor deja mayúscula inicial y el resto en minúsculas
    const renamed = `Renombrado ${suffix}`;
    created.push(normalized, renamed);

    await signIn({ token: await loginAdmin(request) });
    await page.goto('/admin/generos');
    await expect(page).toHaveTitle('Géneros · Administración — StreamBox');
    const list = page.getByRole('list', { name: 'Géneros' });
    await expect(list).toBeVisible();

    // Alta.
    await page.getByLabel('Nombre del nuevo género').fill(typed);
    await page.getByRole('button', { name: 'Añadir' }).click();
    await expect(page.getByText(`Género «${normalized}» creado.`)).toBeVisible();
    await expect(list.getByRole('listitem').filter({ hasText: normalized })).toBeVisible();
    await expect(page.getByLabel('Nombre del nuevo género')).toHaveValue('');

    // Renombrar en línea (Intro guarda) y el foco vuelve al botón de la fila renombrada.
    await page.getByRole('button', { name: `Renombrar ${normalized}` }).click();
    const input = page.getByRole('textbox', { name: `Nuevo nombre para ${normalized}` });
    await expect(input).toBeFocused();
    await input.fill(renamed);
    await input.press('Enter');
    await expect(page.getByText(`Género «${normalized}» renombrado a «${renamed}».`)).toBeVisible();
    await expect(page.getByRole('button', { name: `Renombrar ${renamed}` })).toBeFocused();
    await expect(list.getByRole('listitem').filter({ hasText: normalized })).toHaveCount(0);

    // Borrar, con confirmación.
    await page.getByRole('button', { name: `Borrar ${renamed}` }).click();
    const dialog = page.getByRole('alertdialog', { name: `¿Borrar el género «${renamed}»?` });
    await expect(dialog).toBeVisible();
    await dialog.getByRole('button', { name: 'Sí, borrar género' }).click();
    await expect(page.getByText(`Género «${renamed}» borrado.`)).toBeVisible();
    await expect(list.getByRole('listitem').filter({ hasText: renamed })).toHaveCount(0);
  });

  test('un nombre repetido (409) se avisa junto al campo y un género en uso (409) no se borra', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/admin/generos');
    await expect(page.getByRole('list', { name: 'Géneros' })).toBeVisible();

    // «drama» choca con «Drama»: el servidor compara el nombre ya normalizado.
    const createResponse = page.waitForResponse(
      (response) => response.url().endsWith('/api/genres') && response.request().method() === 'POST',
    );
    await page.getByLabel('Nombre del nuevo género').fill('drama');
    await page.getByRole('button', { name: 'Añadir' }).click();
    const duplicate = await createResponse;
    expect(duplicate.status()).toBe(409);
    const duplicateBody = (await duplicate.json()) as { code: string; message: string };
    expect(duplicateBody.code).toBe('GENRE_ALREADY_EXISTS');
    const nameInput = page.getByLabel('Nombre del nuevo género');
    await expect(nameInput).toHaveAttribute('aria-invalid', 'true');
    await expect(nameInput).toHaveAccessibleDescription(duplicateBody.message);

    // «Drama» lo usan varias películas del catálogo sembrado: el servidor no deja borrarlo.
    const deleteResponse = page.waitForResponse(
      (response) => /\/api\/genres\/\d+$/.test(response.url()) && response.request().method() === 'DELETE',
    );
    await page.getByRole('button', { name: 'Borrar Drama' }).click();
    await page.getByRole('alertdialog').getByRole('button', { name: 'Sí, borrar género' }).click();
    const inUse = await deleteResponse;
    expect(inUse.status()).toBe(409);
    const inUseBody = (await inUse.json()) as { code: string; message: string };
    expect(inUseBody.code).toBe('GENRE_IN_USE');

    // El mensaje del servidor (cuántas películas lo usan y qué hacer) se queda en la fila de «Drama», que sigue ahí.
    const dramaRow = page.getByRole('listitem').filter({ has: page.getByRole('button', { name: 'Borrar Drama' }) });
    await expect(dramaRow.getByRole('alert')).toHaveText(inUseBody.message);
    await expect(page.getByRole('button', { name: 'Borrar Drama' })).toBeFocused();
  });
});
