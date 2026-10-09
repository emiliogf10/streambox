/**
 * E2E del ciclo completo de una película desde el panel: alta (con portada
 * propia `/covers/...` y vista previa), edición y borrado con confirmación. De
 * paso comprueba la caché de datos (TanStack Query): tras editar, `/peliculas`
 * enseña el título nuevo sin recargar la página.
 *
 * **Se ejecuta aparte y al final** (proyecto `catalogo-mutable` de
 * `playwright.config.ts`, que depende del resto): mientras existe, la película
 * creada es la MÁS RECIENTE del catálogo, así que cambiaría el banner de la
 * portada y los totales ("25 películas") que comprueban otros tests en paralelo.
 * Para ejecutarlo solo: `npx playwright test admin-peliculas --no-deps`.
 *
 * La película lleva un sufijo único y se borra por API al terminar aunque el
 * test falle a mitad, para no dejar el catálogo compartido modificado.
 */
import { randomUUID } from 'node:crypto';
import type { Page } from '@playwright/test';
import { deleteMoviesMatching, loginAdmin } from './support/api';
import { escapeRegExp, expect, formAlert, test, waitForMovieForm } from './support/fixtures';

/** Sufijo único de la película de este test (también sirve para buscarla y limpiarla). */
const SUFFIX = `e2e${randomUUID().slice(0, 8)}`;

/** Fila de la tabla del panel cuya película se titula exactamente `title`. */
function movieRow(page: Page, title: string) {
  return page.getByRole('row', { name: new RegExp(`^${escapeRegExp(title)}(\\s|$)`) });
}

test.afterEach(async ({ request }) => {
  await deleteMoviesMatching(request, await loginAdmin(request), SUFFIX);
});

test('crea una película con portada propia, la edita y la borra con confirmación', async ({ page, request, signIn }) => {
  const title = `Película de prueba ${SUFFIX}`;
  const editedTitle = `Película editada ${SUFFIX}`;

  await signIn({ token: await loginAdmin(request) });
  await page.goto('/admin/peliculas');
  await page.getByRole('link', { name: 'Nueva película' }).click();
  await expect(page).toHaveURL(/\/admin\/peliculas\/nueva$/);
  await expect(page).toHaveTitle('Nueva película · Administración — StreamBox');
  // Antes de pulsar, el formulario completo: la llegada de los géneros desplaza el botón (ver la función).
  await waitForMovieForm(page);

  // Enviar vacío: errores en cada campo y el foco en el primero; no sale ninguna petición.
  await page.getByRole('button', { name: 'Crear película' }).click();
  await expect(page.getByLabel('Título', { exact: true })).toBeFocused();
  await expect(page.getByLabel('Título', { exact: true })).toHaveAttribute('aria-invalid', 'true');

  await page.getByLabel('Título', { exact: true }).fill(title);
  await page.getByLabel('Sinopsis').fill('Una película creada por la suite E2E para probar el panel de administración.');
  await page.getByLabel('Duración (minutos)').fill('111');
  await page.getByLabel('Año de estreno').fill('2023');
  await page.getByLabel('URL del vídeo').fill('https://videos.streambox.example/watch/e2e');
  await page.getByRole('group', { name: 'Géneros' }).getByRole('checkbox', { name: 'Drama' }).check();

  // Una portada http:// no se acepta ni se carga en la vista previa (se ve el respaldo).
  const image = page.getByLabel('URL de la portada');
  const preview = page.getByRole('complementary', { name: 'Vista previa de la portada' });
  await image.fill('http://img.example.com/portada.jpg');
  await expect(preview).toContainText('La URL aún no es válida');
  await expect(preview.locator('img')).toHaveCount(0);
  await page.getByRole('button', { name: 'Crear película' }).click();
  await expect(image).toHaveAccessibleDescription(
    'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)',
  );
  await expect(image).toBeFocused();

  // Una portada propia sí: la vista previa la descarga de verdad.
  await image.fill('/covers/interstellar.webp');
  await expect(preview.locator('img')).toHaveAttribute('src', '/covers/interstellar.webp');
  await expect
    .poll(() => preview.locator('img').evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0))
    .toBe(true);

  await page.getByRole('button', { name: 'Crear película' }).click();

  // Vuelta al listado con aviso; es la más reciente, así que aparece arriba, con su portada.
  await expect(page).toHaveURL(/\/admin\/peliculas$/);
  await expect(page.getByText(`«${title}» se ha añadido al catálogo.`)).toBeVisible();
  const row = movieRow(page, title);
  await expect(page.getByRole('table').getByRole('row').nth(1)).toHaveAccessibleName(new RegExp(`^${escapeRegExp(title)}`));
  await expect(row.locator('img')).toHaveAttribute('src', '/covers/interstellar.webp');
  await expect(row).toContainText('2023');
  await expect(row).toContainText('1h 51m');

  // Caché de datos: /peliculas se carga AHORA (la nueva es la más reciente, así que es el banner) y se vuelve
  // al panel sin recargar la página. La marca en `window` desaparecería con una recarga.
  const mainNav = page.getByRole('navigation', { name: 'Principal' });
  await page.evaluate(() => Object.assign(window, { __sinRecargar: true }));
  await mainNav.getByRole('link', { name: 'Películas' }).click();
  await expect(page.getByRole('heading', { level: 2, name: title, exact: true })).toBeVisible();
  await page.goBack();
  await expect(page).toHaveURL(/\/admin\/peliculas$/);

  // Edición: carga los datos, se cambian título y duración y se vuelve al listado.
  await row.getByRole('link', { name: `Editar ${title}` }).click();
  await expect(page.getByRole('heading', { level: 2, name: `Editar «${title}»` })).toBeVisible();
  await expect(page.getByLabel('Título', { exact: true })).toHaveValue(title);
  await expect(page.getByLabel('URL de la portada')).toHaveValue('/covers/interstellar.webp');
  await expect(page.getByRole('checkbox', { name: 'Drama' })).toBeChecked();
  await page.getByLabel('Título', { exact: true }).fill(editedTitle);
  await page.getByLabel('Duración (minutos)').fill('112');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();

  await expect(page).toHaveURL(/\/admin\/peliculas$/);
  await expect(page.getByText(`Se han guardado los cambios de «${editedTitle}».`)).toBeVisible();
  await expect(movieRow(page, editedTitle)).toContainText('1h 52m');
  await expect(movieRow(page, title)).toHaveCount(0);

  // Sin recargar, /peliculas ya enseña el título nuevo: el dato de hace unos segundos seguía «fresco» en la
  // caché, pero la edición lo invalidó.
  await mainNav.getByRole('link', { name: 'Películas' }).click();
  await expect(page.getByRole('heading', { level: 2, name: editedTitle, exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: title, exact: true })).toHaveCount(0);
  expect(await page.evaluate(() => (window as { __sinRecargar?: boolean }).__sinRecargar)).toBe(true);
  await page.goBack();
  await expect(page).toHaveURL(/\/admin\/peliculas$/);

  // Buscarla por su sufijo y borrarla, con confirmación.
  await page.getByRole('searchbox', { name: 'Buscar por título' }).fill(SUFFIX);
  await expect(page).toHaveURL(new RegExp(`\\?q=${SUFFIX}$`));
  const editedRow = movieRow(page, editedTitle);
  await editedRow.getByRole('button', { name: `Borrar ${editedTitle}` }).click();
  const dialog = page.getByRole('alertdialog', { name: `¿Borrar «${editedTitle}»?` });
  await expect(dialog).toContainText('También se quitará de las listas de todos los usuarios.');
  await dialog.getByRole('button', { name: 'Sí, borrar película' }).click();

  await expect(page.getByText(`«${editedTitle}» se ha borrado del catálogo.`)).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: `Sin resultados para «${SUFFIX}»` })).toBeVisible();
  await expect(formAlert(page)).toHaveCount(0);
});
