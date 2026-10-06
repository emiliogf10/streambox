/**
 * E2E del panel de series: la pestaña «Series», el listado con las series
 * vacías marcadas, el alta (que aterriza en la edición de la serie nueva), la
 * edición, que una serie sin episodios NO la ven los usuarios, el borrado con
 * confirmación y, en el bloque «episodios», su gestión: añadirlos (la serie se
 * hace visible), el 409 al repetir temporada y número, editarlos (el cambio se
 * ve en la página pública), borrarlos (al quitar el último vuelve a ocultarse) y
 * borrar una serie que ya tiene episodios.
 *
 * **Un test por acción, con lo previo sembrado por la API.** Antes era un único
 * test que lo hacía todo por la interfaz y rozaba el límite de 30 s en una
 * ejecución completa. Ahora cada test crea por API (`createSeries` y
 * `createEpisode`) la serie y los episodios que necesita y solo usa la interfaz
 * para lo que comprueba. La creación por la interfaz sigue probada: la de series
 * en el primer test y la de episodios en «añadir episodios».
 *
 * **Se ejecuta aparte y al final** (proyecto `catalogo-mutable` de
 * `playwright.config.ts`): con episodios la serie nueva pasa a ser la más
 * reciente de `/series` (su banner) y lo cambiaría para el resto de specs.
 * Para ejecutarlo solo: `npx playwright test admin-series --no-deps`.
 *
 * **En serie** (`mode: 'serial'`): el primer test comprueba que el banner de
 * `/series` sigue siendo la serie sembrada y el de editar episodios, que lo es
 * SU serie; si corrieran a la vez, una serie visible de otro test les quitaría
 * el banner y fallarían al azar.
 *
 * Las series llevan un sufijo único y se borran por API al terminar cada test
 * aunque falle a mitad, para no dejar el catálogo compartido modificado.
 */
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, Locator, Page } from '@playwright/test';
import {
  createEpisode,
  createSeries,
  deleteSeriesMatching,
  listGenres,
  loginAdmin,
  publicSeriesSearchCount,
} from './support/api';
import type { NewEpisode } from './support/api';
import { EMPTY_SERIES, SERIES_HERO } from './support/catalog';
import { escapeRegExp, expect, formAlert, test, waitForMovieForm } from './support/fixtures';

/** Sufijo único de la serie de este test (también sirve para buscarla y limpiarla). */
const SUFFIX = `e2e${randomUUID().slice(0, 8)}`;

/** Marca de las series sin episodios en el listado del panel. */
const HIDDEN_MARK = 'Sin episodios · oculta para los usuarios';

/** Fila de la tabla del panel cuya serie se titula exactamente `title`. */
function seriesRow(page: Page, title: string) {
  return page.getByRole('row', { name: new RegExp(`^${escapeRegExp(title)}(\\s|$)`) });
}

test.describe.configure({ mode: 'serial' });

test.afterEach(async ({ request }) => {
  await deleteSeriesMatching(request, await loginAdmin(request), SUFFIX);
});

test('crea una serie, aterriza en su edición, sigue oculta para los usuarios y se borra con confirmación', async ({
  page,
  request,
  signIn,
}) => {
  const title = `Serie de prueba ${SUFFIX}`;
  const editedTitle = `Serie editada ${SUFFIX}`;
  const adminToken = await loginAdmin(request);
  await signIn({ token: adminToken });

  // Pestaña «Series»: listado con la serie sembrada sin episodios marcada como oculta.
  await page.goto('/admin/peliculas');
  const sections = page.getByRole('navigation', { name: 'Secciones de administración' });
  await sections.getByRole('link', { name: 'Series' }).click();
  await expect(page).toHaveURL(/\/admin\/series$/);
  await expect(page).toHaveTitle('Series · Administración — StreamBox');
  await expect(sections.getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('table', { name: /^Series del catálogo, página 1 de/ })).toBeVisible();
  await expect(seriesRow(page, EMPTY_SERIES.title)).toContainText(HIDDEN_MARK);
  await expect(seriesRow(page, SERIES_HERO.title)).not.toContainText(HIDDEN_MARK);

  // Alta: primero los errores de cliente (vacío y año de fin anterior al estreno).
  await page.getByRole('link', { name: 'Nueva serie' }).click();
  await expect(page).toHaveURL(/\/admin\/series\/nueva$/);
  await expect(page).toHaveTitle('Nueva serie · Administración — StreamBox');
  // Las casillas de géneros llegan aparte y desplazan el botón: se espera al formulario completo.
  await waitForMovieForm(page);
  const create = page.getByRole('button', { name: 'Crear serie' });
  await create.click();
  await expect(page.getByLabel('Título', { exact: true })).toBeFocused();
  await expect(page.getByLabel('Título', { exact: true })).toHaveAccessibleDescription('El título es obligatorio');

  await page.getByLabel('Título', { exact: true }).fill(title);
  await page.getByLabel('Sinopsis').fill('Una serie creada por la suite E2E para probar el panel de administración.');
  await page.getByLabel('Año de estreno').fill('2024');
  const endYear = page.getByLabel('Año de fin (opcional)');
  await endYear.fill('2023');
  await page.getByLabel('URL de la portada').fill('/covers/interstellar.webp');
  await page.getByRole('group', { name: 'Géneros' }).getByRole('checkbox', { name: 'Drama' }).check();
  await create.click();
  await expect(endYear).toBeFocused();
  await expect(endYear).toHaveAccessibleDescription('El año de finalización no puede ser anterior al año de estreno');

  // Sin año de fin (en emisión) se crea y aterriza en su edición, con el aviso de añadir episodios.
  await endYear.fill('');
  await create.click();
  await expect(page).toHaveURL(/\/admin\/series\/\d+\/editar$/);
  await expect(page.getByText('Serie creada. Añade episodios para que sea visible.')).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: `Editar «${title}»` })).toBeVisible();
  await expect(page.getByLabel('Año de fin (opcional)')).toHaveValue('');
  await expect(page.getByRole('region', { name: 'Episodios', exact: true })).toContainText('los usuarios no la ven');
  const id = Number(/\/admin\/series\/(\d+)\/editar$/.exec(page.url())![1]);

  // Edición: se queda en la página, con el título nuevo.
  await page.getByLabel('Título', { exact: true }).fill(editedTitle);
  await page.getByLabel('Año de fin (opcional)').fill('2025');
  await page.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.getByText(`Se han guardado los cambios de «${editedTitle}».`)).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: `Editar «${editedTitle}»` })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/admin/series/${id}/editar$`));

  // Sin episodios, los usuarios no la ven: ni en /series, ni en la búsqueda, ni su página.
  await page.goto('/series');
  await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
  await expect(page.getByText(editedTitle)).toHaveCount(0);
  expect(await publicSeriesSearchCount(request, adminToken, SUFFIX)).toBe(0);
  await page.goto(`/series/${id}`);
  await expect(page.getByRole('heading', { level: 1, name: 'Serie no encontrada' })).toBeVisible();
  // Al administrador se le dice el motivo posible (sin episodios) y se le lleva al panel, no a /series.
  await expect(page.getByText(/o que todavía no tenga episodios/)).toBeVisible();
  await expect(page.getByRole('link', { name: 'Gestionar series' })).toHaveAttribute('href', '/admin/series');

  // En el panel sí aparece, marcada como oculta; se busca por su sufijo y se borra con confirmación.
  await page.goto('/admin/series');
  await page.getByRole('searchbox', { name: 'Buscar por título' }).fill(SUFFIX);
  await expect(page).toHaveURL(new RegExp(`\\?q=${SUFFIX}$`));
  const row = seriesRow(page, editedTitle);
  await expect(row).toContainText(HIDDEN_MARK);
  await expect(row.locator('img')).toHaveAttribute('src', '/covers/interstellar.webp');
  await row.getByRole('button', { name: `Borrar ${editedTitle}` }).click();
  const dialog = page.getByRole('alertdialog', { name: `¿Borrar «${editedTitle}»?` });
  await expect(dialog).toContainText('Todavía no tiene episodios. También se quitará de las listas de todos los usuarios.');
  await dialog.getByRole('button', { name: 'Sí, borrar serie' }).click();

  await expect(page.getByText(`«${editedTitle}» se ha borrado del catálogo.`)).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: `Sin resultados para «${SUFFIX}»` })).toBeVisible();
  await expect(formAlert(page)).toHaveCount(0);
});

/** Datos de un episodio tal como se escriben en el diálogo (temporada y número solo si se cambian). */
interface EpisodeInput {
  season?: string;
  number?: string;
  title: string;
  duration: string;
  description?: string;
  videoUrl: string;
}

/** Rellena el diálogo de episodio abierto. Temporada antes que número: cambiarla recalcula el número propuesto. */
async function fillEpisode(dialog: Locator, episode: EpisodeInput): Promise<void> {
  if (episode.season !== undefined) await dialog.getByLabel('Temporada', { exact: true }).fill(episode.season);
  if (episode.number !== undefined) await dialog.getByLabel('Número', { exact: true }).fill(episode.number);
  await dialog.getByLabel('Título', { exact: true }).fill(episode.title);
  await dialog.getByLabel('Duración (minutos)').fill(episode.duration);
  if (episode.description !== undefined) await dialog.getByLabel('Sinopsis (opcional)').fill(episode.description);
  await dialog.getByLabel('URL del vídeo').fill(episode.videoUrl);
}

/** Episodios que se siembran por la API en los tests que no prueban el alta. */
const SEEDED_EPISODES = {
  t1e1: {
    seasonNumber: 1,
    episodeNumber: 1,
    title: 'Primer contacto',
    description: null,
    duration: 50,
    videoUrl: 'https://videos.streambox.example/e2e/t1e1',
  },
  t2e1: {
    seasonNumber: 2,
    episodeNumber: 1,
    title: 'Segunda temporada',
    description: 'Empieza la segunda temporada.',
    duration: 55,
    videoUrl: 'https://videos.streambox.example/e2e/t2e1',
  },
} satisfies Record<string, NewEpisode>;

/**
 * Crea por la API la serie de un test de episodios (título con el sufijo, para
 * que el `afterEach` la borre) con estos episodios y devuelve su id. Es la
 * preparación que antes se hacía por la interfaz solo para llegar al punto que
 * se comprueba; ese alta por la interfaz ya la prueba el primer test.
 */
async function seedSeries(
  request: APIRequestContext,
  adminToken: string,
  title: string,
  episodes: readonly NewEpisode[],
): Promise<number> {
  const drama = (await listGenres(request, adminToken)).find((genre) => genre.name === 'Drama');
  if (!drama) throw new Error('El género «Drama» no está en el catálogo sembrado');
  const id = await createSeries(request, adminToken, {
    title,
    description: 'Serie creada por la suite E2E para probar la gestión de episodios.',
    releaseYear: 2024,
    endYear: null,
    imageUrl: '/covers/ex-machina.webp',
    genreIds: [drama.id],
  });
  for (const episode of episodes) await createEpisode(request, adminToken, id, episode);
  return id;
}

/**
 * Abre la edición de la serie `id` con la sesión del administrador y devuelve la
 * sección «Episodios», ya cargada (se espera a su encabezado).
 */
async function openSeriesEditor(
  page: Page,
  signIn: (user: { token: string }) => Promise<void>,
  adminToken: string,
  id: number,
): Promise<Locator> {
  await signIn({ token: adminToken });
  await page.goto(`/admin/series/${id}/editar`);
  const episodes = page.getByRole('region', { name: 'Episodios', exact: true });
  await expect(episodes.getByRole('heading', { level: 3, name: 'Episodios' })).toBeVisible();
  return episodes;
}

test('episodios: añadirlos por la interfaz valida, propone el número, hace visible la serie y recalcula al cambiar de temporada', async ({
  page,
  request,
  signIn,
}) => {
  const adminToken = await loginAdmin(request);
  const id = await seedSeries(request, adminToken, `Serie con episodios ${SUFFIX}`, []);
  const episodes = await openSeriesEditor(page, signIn, adminToken, id);
  // Nace sin episodios: oculta para los usuarios. Que tampoco sale en /series ni tiene página ya lo
  // comprueba el primer test; aquí basta la API. Motivo: con la traza que guarda Playwright
  // (`retain-on-failure`) cada visita a /series suma varios segundos a la duración, que cuenta para el límite.
  await expect(episodes).toContainText('los usuarios no la ven');
  expect(await publicSeriesSearchCount(request, adminToken, SUFFIX)).toBe(0);

  // Primer episodio: propone T1:E1 y, al guardarlo, la serie pasa a ser visible.
  await episodes.getByRole('button', { name: 'Añadir episodio' }).click();
  let dialog = page.getByRole('dialog', { name: 'Añadir episodio' });
  await expect(dialog.getByLabel('Título', { exact: true })).toBeFocused();
  await expect(dialog.getByLabel('Temporada', { exact: true })).toHaveValue('1');
  await expect(dialog.getByLabel('Número', { exact: true })).toHaveValue('1');
  // Errores de cliente con los mensajes del servidor, sin enviar nada.
  await dialog.getByRole('button', { name: 'Añadir episodio' }).click();
  await expect(dialog.getByLabel('Título', { exact: true })).toBeFocused();
  await expect(dialog.getByLabel('Título', { exact: true })).toHaveAccessibleDescription('El título es obligatorio');
  await expect(dialog.getByLabel('Duración (minutos)')).toHaveAccessibleDescription('La duración es obligatoria');
  await fillEpisode(dialog, {
    title: 'Primer contacto',
    duration: '50',
    videoUrl: 'https://videos.streambox.example/e2e/t1e1',
  });
  await dialog.getByRole('button', { name: 'Añadir episodio' }).click();
  await expect(
    page.getByText('Se ha añadido el episodio T1:E1 «Primer contacto». La serie ya es visible para los usuarios.'),
  ).toBeVisible();
  await expect(dialog).toHaveCount(0);
  await expect(episodes.getByRole('button', { name: 'Añadir episodio' })).toBeFocused();
  await expect(episodes).toContainText('Visible para los usuarios');

  // Segundo episodio en otra temporada: al cambiar la temporada, el número propuesto se recalcula.
  await episodes.getByRole('button', { name: 'Añadir episodio' }).click();
  dialog = page.getByRole('dialog', { name: 'Añadir episodio' });
  await expect(dialog.getByLabel('Número', { exact: true })).toHaveValue('2');
  await dialog.getByLabel('Temporada', { exact: true }).fill('2');
  await expect(dialog.getByLabel('Número', { exact: true })).toHaveValue('1');
  await fillEpisode(dialog, {
    title: 'Segunda temporada',
    duration: '55',
    description: 'Empieza la segunda temporada.',
    videoUrl: 'https://videos.streambox.example/e2e/t2e1',
  });
  await dialog.getByRole('button', { name: 'Añadir episodio' }).click();
  await expect(page.getByText('Se ha añadido el episodio T2:E1 «Segunda temporada».')).toBeVisible();
  await expect(episodes.getByRole('heading', { level: 4 })).toHaveText(['Temporada 1 · 1 episodio', 'Temporada 2 · 1 episodio']);
  await expect(episodes).toContainText('2 episodios en 2 temporadas');
  expect(await publicSeriesSearchCount(request, adminToken, SUFFIX)).toBe(1);
});

test('episodios: repetir temporada y número da el 409 del servidor junto a los dos campos', async ({
  page,
  request,
  signIn,
}) => {
  const adminToken = await loginAdmin(request);
  const id = await seedSeries(request, adminToken, `Serie con episodios ${SUFFIX}`, [
    SEEDED_EPISODES.t1e1,
    SEEDED_EPISODES.t2e1,
  ]);
  const episodes = await openSeriesEditor(page, signIn, adminToken, id);
  await expect(episodes.getByRole('listitem')).toHaveCount(2);

  // El diálogo propone la última temporada (la 2): solo se cambia el número al de un episodio que ya existe.
  await episodes.getByRole('button', { name: 'Añadir episodio' }).click();
  const dialog = page.getByRole('dialog', { name: 'Añadir episodio' });
  await expect(dialog.getByLabel('Temporada', { exact: true })).toHaveValue('2');
  await fillEpisode(dialog, {
    number: '1',
    title: 'Duplicado',
    duration: '40',
    videoUrl: 'https://videos.streambox.example/e2e/duplicado',
  });
  await dialog.getByRole('button', { name: 'Añadir episodio' }).click();
  const number = dialog.getByLabel('Número', { exact: true });
  await expect(number).toBeFocused();
  await expect(number).toHaveAccessibleDescription('Ya existe el episodio 1 de la temporada 2');
  await expect(dialog.getByLabel('Temporada', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(episodes.getByRole('listitem')).toHaveCount(2);
});

test('episodios: editar uno cambia su página pública (la serie es el banner de /series)', async ({
  page,
  request,
  signIn,
}) => {
  const title = `Serie con episodios ${SUFFIX}`;
  const adminToken = await loginAdmin(request);
  const id = await seedSeries(request, adminToken, title, [SEEDED_EPISODES.t1e1, SEEDED_EPISODES.t2e1]);
  const episodes = await openSeriesEditor(page, signIn, adminToken, id);

  // Editar un episodio (sin salir de la página): el foco vuelve a su botón «Editar», ya con el título nuevo.
  await episodes.getByRole('button', { name: 'Editar episodio T1:E1 Primer contacto' }).click();
  const dialog = page.getByRole('dialog', { name: 'Editar episodio T1:E1' });
  await expect(dialog.getByLabel('Título', { exact: true })).toHaveValue('Primer contacto');
  await dialog.getByLabel('Título', { exact: true }).fill('Primer contacto (versión extendida)');
  await dialog.getByLabel('Duración (minutos)').fill('62');
  await dialog.getByRole('button', { name: 'Guardar cambios' }).click();
  await expect(page.getByText('Se han guardado los cambios del episodio T1:E1 «Primer contacto (versión extendida)».')).toBeVisible();
  await expect(episodes.getByRole('button', { name: 'Editar episodio T1:E1 Primer contacto (versión extendida)' })).toBeFocused();

  // Ya la ven los usuarios: es la más reciente (banner de /series), su página tiene selector de
  // temporadas y muestra el episodio con los datos editados (título y duración nuevos).
  // Se entra desde el banner (navegación dentro de la SPA, como un usuario).
  expect(await publicSeriesSearchCount(request, adminToken, SUFFIX)).toBe(1);
  await page.goto('/series');
  await page.getByRole('region', { name: title }).getByRole('link', { name: /^Ver episodios/ }).click();
  await expect(page).toHaveURL(new RegExp(`/series/${id}$`));
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Temporadas' }).getByRole('link')).toHaveText(['Temporada 1', 'Temporada 2']);
  const season1 = page.getByRole('list', { name: 'Episodios de la temporada 1' });
  await expect(season1.getByRole('heading', { level: 3 })).toHaveText(['1. Primer contacto (versión extendida)']);
  await expect(season1).toContainText('1h 2m');
});

test('episodios: borrarlos con confirmación lleva el foco a la sección y, sin el último, la serie vuelve a ocultarse', async ({
  page,
  request,
  signIn,
}) => {
  const adminToken = await loginAdmin(request);
  const id = await seedSeries(request, adminToken, `Serie con episodios ${SUFFIX}`, [
    SEEDED_EPISODES.t1e1,
    SEEDED_EPISODES.t2e1,
  ]);
  const episodes = await openSeriesEditor(page, signIn, adminToken, id);

  // Borrar un episodio con confirmación: desaparece su temporada y el foco va al encabezado de la sección.
  await episodes.getByRole('button', { name: 'Borrar episodio T2:E1 Segunda temporada' }).click();
  let confirm = page.getByRole('alertdialog', { name: '¿Borrar el episodio T2:E1 «Segunda temporada»?' });
  await expect(confirm).toContainText('Esta acción no se puede deshacer.');
  await confirm.getByRole('button', { name: 'Sí, borrar episodio' }).click();
  await expect(page.getByText('Se ha borrado el episodio T2:E1 «Segunda temporada».')).toBeVisible();
  await expect(episodes.getByRole('heading', { level: 3, name: 'Episodios' })).toBeFocused();
  await expect(episodes.getByRole('heading', { level: 4 })).toHaveText(['Temporada 1 · 1 episodio']);

  // El último: la confirmación y el aviso dicen que la serie vuelve a estar oculta, y los usuarios dejan de verla.
  await episodes.getByRole('button', { name: 'Borrar episodio T1:E1 Primer contacto' }).click();
  confirm = page.getByRole('alertdialog', { name: '¿Borrar el episodio T1:E1 «Primer contacto»?' });
  await expect(confirm).toContainText('Es el único episodio: la serie volverá a estar oculta para los usuarios.');
  await confirm.getByRole('button', { name: 'Sí, borrar episodio' }).click();
  await expect(
    page.getByText('Se ha borrado el episodio T1:E1 «Primer contacto». La serie ya no tiene episodios: vuelve a estar oculta.'),
  ).toBeVisible();
  await expect(episodes.getByRole('heading', { level: 3, name: 'Episodios' })).toBeFocused();
  await expect(episodes).toContainText('los usuarios no la ven');
  expect(await publicSeriesSearchCount(request, adminToken, SUFFIX)).toBe(0);
});

test('episodios: borrar desde el listado una serie con episodios avisa de que se los lleva', async ({
  page,
  request,
  signIn,
}) => {
  const title = `Serie con episodios ${SUFFIX}`;
  const adminToken = await loginAdmin(request);
  await seedSeries(request, adminToken, title, [SEEDED_EPISODES.t1e1]);
  await signIn({ token: adminToken });

  // Borrar la serie desde el listado, con confirmación (avisa de que se lleva su episodio).
  await page.goto(`/admin/series?q=${SUFFIX}`);
  const row = seriesRow(page, title);
  // Primero que la fila exista: un `not.toContainText` sobre una fila aún sin cargar no demostraría nada.
  await expect(row).toBeVisible();
  await expect(row).not.toContainText(HIDDEN_MARK);
  await row.getByRole('button', { name: `Borrar ${title}` }).click();
  const deleteSeries = page.getByRole('alertdialog', { name: `¿Borrar «${title}»?` });
  await expect(deleteSeries).toContainText('Se borrará también su episodio');
  await deleteSeries.getByRole('button', { name: 'Sí, borrar serie' }).click();
  await expect(page.getByText(`«${title}» se ha borrado del catálogo.`)).toBeVisible();
  expect(await publicSeriesSearchCount(request, adminToken, SUFFIX)).toBe(0);
});
