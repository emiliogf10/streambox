/**
 * E2E del ciclo completo de una película desde el panel: alta (con portada
 * propia `/covers/...` y vista previa, y un tráiler de YouTube corto que el
 * servidor guarda normalizado), edición y borrado con confirmación. De
 * paso comprueba la caché de datos (TanStack Query): tras editar, `/peliculas`
 * enseña el título nuevo sin recargar la página, la página de la película
 * (`/peliculas/:id`) ofrece «Editar» al administrador y, tras borrarla, volver a
 * ella con «Atrás» dice «Película no encontrada».
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
import { deleteMoviesMatching, findMovieId, loginAdmin, movieByIdApi } from './support/api';
import { escapeRegExp, expect, formAlert, test, waitForAdminSearchResults, waitForMovieForm } from './support/fixtures';

/** Sufijo único de la película de este test (también sirve para buscarla y limpiarla). */
const SUFFIX = `e2e${randomUUID().slice(0, 8)}`;

/**
 * Uno detrás de otro, en el mismo proceso: el primer test comprueba que SU
 * película es la más reciente (la primera fila del panel y el banner de
 * `/peliculas`), y la del test de subtítulos le quitaría ese puesto si se
 * crearan a la vez. `default` (y no `serial`): si uno falla, el otro corre igual.
 */
test.describe.configure({ mode: 'default' });

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
  await page.getByLabel('URL del vídeo (opcional)').fill('https://videos.streambox.example/watch/e2e');
  // Tráiler: un enlace que no es de YouTube se rechaza en el cliente con el mensaje del contrato...
  const trailer = page.getByLabel('Tráiler de YouTube (opcional)');
  await trailer.fill('https://vimeo.com/123456');
  await page.getByRole('button', { name: 'Crear película' }).click();
  await expect(trailer).toHaveAccessibleDescription('El tráiler debe ser un enlace de un vídeo de YouTube');
  // ...y uno corto de YouTube vale: el servidor lo guarda normalizado (se comprueba en la edición).
  await trailer.fill('https://youtu.be/zSWdZVtXT7E?si=e2e');
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
  await expect(page.getByLabel('Tráiler de YouTube (opcional)')).toHaveValue('https://www.youtube.com/watch?v=zSWdZVtXT7E');
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

  // Su página (`/peliculas/:id`): para un administrador, «Editar» lleva al formulario del panel.
  await page
    .getByRole('region', { name: editedTitle, exact: true })
    .getByRole('link', { name: /^Más información/ })
    .click();
  await expect(page.getByRole('heading', { level: 1, name: editedTitle, exact: true })).toBeVisible();
  const moviePath = new URL(page.url()).pathname;
  expect(moviePath).toMatch(/^\/peliculas\/\d+$/);
  await expect(page.getByRole('link', { name: `Editar ${editedTitle}` })).toHaveAttribute(
    'href',
    `/admin${moviePath}/editar`,
  );
  await mainNav.getByRole('link', { name: 'Administrar' }).click();
  await expect(page).toHaveURL(/\/admin\/peliculas$/);

  // Buscarla por su sufijo y borrarla, con confirmación.
  await page.getByRole('searchbox', { name: 'Buscar por título' }).fill(SUFFIX);
  await expect(page).toHaveURL(new RegExp(`\\?q=${SUFFIX}$`));
  await waitForAdminSearchResults(page, SUFFIX);
  const editedRow = movieRow(page, editedTitle);
  await editedRow.getByRole('button', { name: `Borrar ${editedTitle}` }).click();
  const dialog = page.getByRole('alertdialog', { name: `¿Borrar «${editedTitle}»?` });
  await expect(dialog).toContainText('También se quitará de las listas de todos los usuarios.');
  await dialog.getByRole('button', { name: 'Sí, borrar película' }).click();

  await expect(page.getByText(`«${editedTitle}» se ha borrado del catálogo.`)).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: `Sin resultados para «${SUFFIX}»` })).toBeVisible();
  await expect(formAlert(page)).toHaveCount(0);

  // Volver con «Atrás» a la página de la película recién borrada: la caché la tenía, pero el borrado la
  // invalidó, así que se vuelve a pedir y la página dice la verdad (sin recargar: es la misma aplicación).
  for (let step = 0; step < 4 && new URL(page.url()).pathname !== moviePath; step += 1) await page.goBack();
  expect(new URL(page.url()).pathname).toBe(moviePath);
  await expect(page.getByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeVisible();
  await expect(page.getByRole('heading', { level: 1, name: editedTitle, exact: true })).toHaveCount(0);
  await expect(page.getByRole('link', { name: 'Ver todas las películas' })).toHaveAttribute('href', '/peliculas');
  expect(await page.evaluate(() => (window as { __sinRecargar?: boolean }).__sinRecargar)).toBe(true);
});

/** Texto de los errores y de la ayuda del campo de subtítulos (los del contrato con el servidor). */
const SUBTITLES = {
  label: 'URL de los subtítulos (opcional)',
  format: 'Los subtítulos deben ser un archivo .vtt (WebVTT) con una URL que empiece por https://',
  requireVideo: 'Los subtítulos necesitan un vídeo: indica también la URL del vídeo',
  hint: 'Archivo WebVTT (.vtt) en español por https://. Solo en el reproductor de StreamBox (vídeo .mp4, .webm, .ogg u .ogv). Ambos servidores deben permitir CORS; si no, el vídeo irá sin ellos.',
};

/**
 * Subtítulos de una película desde el panel (V15): los dos errores del cliente
 * con el foco en el campo (sin vídeo y `.srt`), el alta con un `.vtt` con
 * consulta y en mayúsculas (el servidor lo guarda TAL CUAL: no lo normaliza),
 * editar otra cosa sin perderlos y borrarlos vaciando el campo. Lo guardado se
 * comprueba en la API, que es lo que usará el reproductor.
 */
test('subtítulos: exige vídeo y .vtt con el foco en el campo, guarda uno con consulta y en mayúsculas, los conserva al editar y se borran vaciando el campo', async ({
  page,
  request,
  signIn,
}) => {
  const title = `Película con subtítulos ${SUFFIX}`;
  const subtitlesUrl = 'https://cdn.streambox.example/subs/PELICULA.ES.VTT?token=AbC&exp=1';
  const adminToken = await loginAdmin(request);
  await signIn({ token: adminToken });
  await page.goto('/admin/peliculas/nueva');
  await waitForMovieForm(page);

  await page.getByLabel('Título', { exact: true }).fill(title);
  await page.getByLabel('Sinopsis').fill('Una película creada por la suite E2E para probar los subtítulos.');
  await page.getByLabel('Duración (minutos)').fill('100');
  await page.getByLabel('Año de estreno').fill('2024');
  await page.getByLabel('URL de la portada').fill('/covers/interstellar.webp');
  await page.getByRole('group', { name: 'Géneros' }).getByRole('checkbox', { name: 'Drama' }).check();
  const create = page.getByRole('button', { name: 'Crear película' });
  const subtitles = page.getByLabel(SUBTITLES.label);
  const video = page.getByLabel('URL del vídeo (opcional)');

  // Sin error, el campo se describe con su ayuda.
  await expect(subtitles).toHaveAccessibleDescription(SUBTITLES.hint);

  // Subtítulos válidos sin vídeo: el error va en los subtítulos (el vídeo vacío está bien) y el foco, a ellos.
  await subtitles.fill(subtitlesUrl);
  await create.click();
  await expect(subtitles).toBeFocused();
  await expect(subtitles).toHaveAccessibleDescription(SUBTITLES.requireVideo);
  await expect(subtitles).toHaveAttribute('aria-invalid', 'true');
  await expect(video).not.toHaveAttribute('aria-invalid', 'true');
  // Escribir el vídeo retira ese aviso, que dependía de él.
  await video.fill('https://videos.streambox.example/watch/e2e-subtitulos.mp4');
  await expect(subtitles).toHaveAccessibleDescription(SUBTITLES.hint);

  // Un .srt no es WebVTT: error de formato y foco en el campo, sin llegar al servidor.
  await subtitles.fill('https://cdn.streambox.example/subs/pelicula.srt');
  let posts = 0;
  page.on('request', (sent) => {
    if (sent.method() === 'POST' && sent.url().endsWith('/api/movies')) posts += 1;
  });
  await create.click();
  await expect(subtitles).toBeFocused();
  await expect(subtitles).toHaveAccessibleDescription(SUBTITLES.format);
  expect(posts).toBe(0);

  // Con consulta y en mayúsculas sí vale: se crea y el servidor lo guarda tal cual.
  await subtitles.fill(subtitlesUrl);
  await create.click();
  await expect(page).toHaveURL(/\/admin\/peliculas$/);
  await expect(page.getByText(`«${title}» se ha añadido al catálogo.`)).toBeVisible();
  const movieId = await findMovieId(request, adminToken, title);
  expect((await movieByIdApi(request, adminToken, movieId)).subtitlesUrl).toBe(subtitlesUrl);

  // Editar otra cosa (la duración) los conserva: el formulario los carga y los vuelve a mandar.
  await page.goto(`/admin/peliculas/${movieId}/editar`);
  await expect(page.getByRole('heading', { level: 2, name: `Editar «${title}»` })).toBeVisible();
  await expect(subtitles).toHaveValue(subtitlesUrl);
  await expect(subtitles).toHaveAccessibleDescription(SUBTITLES.hint);
  await page.getByLabel('Duración (minutos)').fill('101');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.getByText(`Se han guardado los cambios de «${title}».`)).toBeVisible();
  const kept = await movieByIdApi(request, adminToken, movieId);
  expect(kept.subtitlesUrl).toBe(subtitlesUrl);

  // Quitar el vídeo dejando los subtítulos: el mismo error del cliente, ahora en la edición.
  await page.goto(`/admin/peliculas/${movieId}/editar`);
  await expect(subtitles).toHaveValue(subtitlesUrl);
  await video.fill('');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(subtitles).toBeFocused();
  await expect(subtitles).toHaveAccessibleDescription(SUBTITLES.requireVideo);

  // Vaciar el campo los borra (se manda `null`) y el vídeo puede seguir como estaba.
  await video.fill(kept.videoUrl ?? '');
  await subtitles.fill('');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.getByText(`Se han guardado los cambios de «${title}».`)).toBeVisible();
  const cleared = await movieByIdApi(request, adminToken, movieId);
  expect(cleared.subtitlesUrl).toBeNull();
  expect(cleared.videoUrl).toBe(kept.videoUrl);
  await page.goto(`/admin/peliculas/${movieId}/editar`);
  await expect(page.getByRole('heading', { level: 2, name: `Editar «${title}»` })).toBeVisible();
  await expect(subtitles).toHaveValue('');
});
