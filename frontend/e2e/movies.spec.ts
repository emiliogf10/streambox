/**
 * E2E de la página `/peliculas` (lo que ve un usuario): la vista por filas sin
 * filtros, la barra de filtros (género, año y orden) con los filtros en la URL,
 * la cuadrícula de resultados con su recuento y «Cargar más», el estado vacío
 * con «Quitar filtros», los años inválidos, el enlace a la página de cada película y el teclado.
 *
 * Usa el catálogo sembrado de `support/catalog.ts` (25 películas con géneros y
 * años conocidos), así que los recuentos son EXACTOS y se calculan a partir de
 * él ({@link newestFirstWhere}) en lugar de escribirse a mano. Ningún test modifica
 * el catálogo: el spec va en el proyecto `chromium`.
 *
 * **La espera de los filtros.** Los cambios en la barra pasan a la URL tras
 * 300 ms sin tocar nada (`useCatalogFilters`). Los tests no esperan un tiempo
 * fijo: esperan a la condición visible (la URL nueva o el recuento), que es lo
 * que haría una persona y no depende de lo cargada que esté la máquina.
 */
import type { Locator, Page } from '@playwright/test';
import { genreIdByName } from './support/api';
import { HERO_TITLE, MOVIES, PAGE_SIZE } from './support/catalog';
import type { SeedMovie } from './support/catalog';
import { expect, movieCard, test } from './support/fixtures';

/** Mensaje del campo «Año» con un año que no se puede buscar (`YEAR_ERROR_MESSAGE` de `useCatalogFilters`). */
const YEAR_ERROR = 'Escribe un año entre 1888 y 2100.';

/**
 * Títulos sembrados que cumplen la condición, en el orden por defecto de la
 * página («Más recientes»): el INVERSO al de creación.
 */
function newestFirstWhere(predicate: (movie: SeedMovie) => boolean): string[] {
  return MOVIES.filter(predicate)
    .map((movie) => movie.title)
    .reverse();
}

const ACTION_TITLES = newestFirstWhere((movie) => movie.genres.includes('Acción'));
const ACTION_2014_TITLES = newestFirstWhere((movie) => movie.genres.includes('Acción') && movie.releaseYear === 2014);
const DRAMA_TITLES = newestFirstWhere((movie) => movie.genres.includes('Drama'));
const TITLES_2014 = newestFirstWhere((movie) => movie.releaseYear === 2014);

/**
 * Todo el catálogo por título (A–Z). `sort()` compara por puntos de código, como
 * H2 en la suite; en este catálogo coincide además con el orden alfabético
 * (ningún título empieza por minúscula ni por letra con tilde).
 */
const ALL_BY_TITLE = MOVIES.map((movie) => movie.title).sort();

/** Controles de la barra de filtros. */
function filterBar(page: Page) {
  const form = page.getByRole('form', { name: 'Filtrar películas' });
  return {
    form,
    genre: form.getByRole('combobox', { name: 'Género' }),
    year: form.getByRole('textbox', { name: 'Año' }),
    sort: form.getByRole('combobox', { name: 'Ordenar por' }),
    clear: form.getByRole('button', { name: 'Quitar filtros' }),
  };
}

/** Sección de resultados (solo existe con algún filtro activo). */
function results(page: Page): Locator {
  return page.getByRole('region', { name: 'Resultados' });
}

/**
 * Comprueba que la cuadrícula muestra EXACTAMENTE estas tarjetas y en este orden.
 *
 * Son dos aserciones y no una por tarjeta: el número de tarjetas y
 * `toContainText` con la lista (la tarjeta `i` contiene el título `i`). Con el
 * mismo número de elementos, eso solo se cumple con el orden exacto: ni siquiera
 * «Blade Runner» y «Blade Runner 2049» podrían intercambiarse. Menos aserciones
 * = menos instantáneas en la traza, que es lo que alargaba el cierre de los tests
 * con la suite en paralelo (la traza se graba siempre y se guarda si fallan).
 */
async function expectGrid(page: Page, titles: readonly string[]): Promise<void> {
  const cards = results(page).getByRole('listitem');
  await expect(cards).toHaveCount(titles.length);
  await expect(cards).toContainText(titles);
}

/**
 * Espera a que la cuadrícula muestre el resultado COMPLETO de un filtro (una
 * sola página): el recuento, las tarjetas en orden y el pie. Mirar el recuento
 * primero garantiza que ya han llegado los resultados del filtro nuevo y no
 * quedan los del anterior.
 */
async function expectResultTitles(page: Page, titles: readonly string[]): Promise<void> {
  const section = results(page);
  const label = titles.length === 1 ? '1 película' : `${titles.length} películas`;
  // La primera región `status` de la sección es el recuento visible; la segunda, la del pie (solo para lectores).
  await expect(section.getByRole('status').first()).toHaveText(label);
  await expectGrid(page, titles);
  await expect(section.getByText(`Mostrando ${titles.length} de ${label}`)).toBeVisible();
}

/** Comprueba que el elemento enfocado tiene el anillo de foco (`focus-ring`: `:focus-visible` con contorno). */
async function expectVisibleFocus(locator: Locator, label: string): Promise<void> {
  await expect(locator, `${label} debe tener el foco`).toBeFocused();
  expect(await locator.evaluate((el) => el.matches(':focus-visible')), `${label}: :focus-visible`).toBe(true);
  await expect(locator, `${label}: contorno visible`).toHaveCSS('outline-style', 'solid');
}

test.describe('Página Películas', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  test('«Películas» de la barra lleva a /peliculas: banner con la más reciente, filas, total y «Cargar más»', async ({
    page,
  }) => {
    await page.goto('/');
    const nav = page.getByRole('navigation', { name: 'Principal' });
    await nav.getByRole('link', { name: 'Películas' }).click();

    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page).toHaveTitle(/^Películas/);
    await expect(page.getByRole('heading', { level: 1, name: 'Películas' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Películas' })).toHaveAttribute('aria-current', 'page');

    const banner = page.getByRole('region', { name: HERO_TITLE });
    await expect(banner).toBeVisible();
    // «Novedades» empieza por la siguiente más reciente: la del banner no se repite debajo.
    const news = page.getByRole('region', { name: 'Novedades' });
    await expect(movieCard(news, MOVIES[MOVIES.length - 2].title)).toBeVisible();
    await expect(movieCard(page.getByRole('main'), HERO_TITLE)).toHaveCount(0);
    for (const genre of ['Ciencia ficción', 'Acción', 'Drama']) {
      await expect(page.getByRole('region', { name: genre, exact: true })).toBeVisible();
    }
    // Sin filtros no hay sección de resultados ni «Quitar filtros».
    await expect(results(page)).toHaveCount(0);
    await expect(filterBar(page).clear).toHaveCount(0);

    await expect(page.getByText(`Mostrando ${PAGE_SIZE} de ${MOVIES.length} películas`)).toBeVisible();
    await page.getByRole('button', { name: 'Cargar más películas' }).click();
    await expect(page.getByText(`Mostrando ${MOVIES.length} de ${MOVIES.length} películas`)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cargar más películas' })).toHaveCount(0);
  });

  test('género y después año: la URL los acumula, los resultados son exactos y «Atrás» deshace cada paso', async ({
    page,
  }) => {
    await page.goto('/peliculas');
    const bar = filterBar(page);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();

    await bar.genre.selectOption({ label: 'Acción' });
    await expect(page).toHaveURL(/\/peliculas\?genero=\d+$/);
    await expectResultTitles(page, ACTION_TITLES);
    // Con filtros, la cuadrícula SUSTITUYE al banner y a las filas.
    await expect(page.getByRole('region', { name: HERO_TITLE })).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Novedades' })).toHaveCount(0);

    await bar.year.fill('2014');
    await expect(page).toHaveURL(/\/peliculas\?genero=\d+&anio=2014$/);
    await expectResultTitles(page, ACTION_2014_TITLES);

    // «Atrás» quita el año (y el campo se vacía), luego el género.
    await page.goBack();
    await expect(page).toHaveURL(/\/peliculas\?genero=\d+$/);
    await expectResultTitles(page, ACTION_TITLES);
    await expect(bar.year).toHaveValue('');
    await expect(bar.genre).toHaveValue(/^\d+$/);

    await page.goBack();
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();
    await expect(bar.genre).toHaveValue('');
    await expect(results(page)).toHaveCount(0);
  });

  test('una URL con filtros funciona al abrirla directamente y al recargar', async ({ page, request, user }) => {
    const dramaId = await genreIdByName(request, user.token, 'Drama');
    await page.goto(`/peliculas?genero=${dramaId}&anio=2014&orden=titulo-asc`);
    const bar = filterBar(page);

    // Drama de 2014, por título: «Ex Machina» antes que «Interstellar» (por fecha de alta sería al revés).
    const expected = ['Ex Machina', 'Interstellar'];
    await expectResultTitles(page, expected);
    // Los controles reflejan la URL.
    await expect(bar.genre).toHaveValue(String(dramaId));
    await expect(bar.year).toHaveValue('2014');
    await expect(bar.sort).toHaveValue('titulo-asc');

    await page.reload();
    await expectResultTitles(page, expected);
    await expect(bar.genre).toHaveValue(String(dramaId));
    await expect(bar.year).toHaveValue('2014');
    await expect(page).toHaveURL(new RegExp(`\\?genero=${dramaId}&anio=2014&orden=titulo-asc$`));
  });

  test('ordenar por título muestra la cuadrícula en ese orden y «Cargar más» trae la segunda página sin duplicados', async ({
    page,
  }) => {
    await page.goto('/peliculas');
    const bar = filterBar(page);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();

    // Solo el orden ya es un "filtro": se pasa a la cuadrícula con TODO el catálogo (25 = dos páginas).
    await bar.sort.selectOption({ label: 'Título A–Z' });
    await expect(page).toHaveURL(/\/peliculas\?orden=titulo-asc$/);
    const section = results(page);
    await expect(section.getByRole('status').first()).toHaveText(`${MOVIES.length} películas`);
    // La primera página: las 20 primeras por título («Al filo del mañana», «Blade Runner», «Blade Runner 2049»...).
    await expectGrid(page, ALL_BY_TITLE.slice(0, PAGE_SIZE));
    await expect(section.getByText(`Mostrando ${PAGE_SIZE} de ${MOVIES.length} películas`)).toBeVisible();

    await section.getByRole('button', { name: 'Cargar más películas' }).click();
    await expect(section.getByText(`Mostrando ${MOVIES.length} de ${MOVIES.length} películas`)).toBeVisible();
    await expect(section.getByRole('button', { name: 'Cargar más películas' })).toHaveCount(0);
    // Las 5 restantes van detrás, en orden y sin repetir ninguna de la primera página (hasta «Tenet»).
    await expectGrid(page, ALL_BY_TITLE);
  });

  test('un filtro que cabe en una página no ofrece «Cargar más»', async ({ page, request, user }) => {
    // Con el catálogo sembrado ningún GÉNERO pasa de 20 películas (el mayor, Ciencia ficción, tiene 16):
    // la segunda página de la cuadrícula se prueba con el orden (test anterior).
    const dramaId = await genreIdByName(request, user.token, 'Drama');
    await page.goto(`/peliculas?genero=${dramaId}`);
    await expectResultTitles(page, DRAMA_TITLES);
    await expect(results(page).getByRole('button', { name: 'Cargar más películas' })).toHaveCount(0);
  });

  test('sin resultados: «No hay películas con estos filtros» y «Quitar filtros» vuelve a las filas y limpia la URL', async ({
    page,
    request,
    user,
  }) => {
    // Ninguna película de Drama es de 1999 («Matrix» es Ciencia ficción y Acción).
    const dramaId = await genreIdByName(request, user.token, 'Drama');
    await page.goto(`/peliculas?genero=${dramaId}&anio=1999`);
    const section = results(page);
    await expect(section.getByRole('heading', { name: 'No hay películas con estos filtros' })).toBeVisible();
    await expect(section.getByRole('status').first()).toHaveText('0 películas');

    await section.getByRole('button', { name: 'Quitar filtros' }).click();

    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    const bar = filterBar(page);
    await expect(bar.genre).toHaveValue('');
    await expect(bar.year).toHaveValue('');
    // El botón pulsado ha desaparecido: el foco pasa al primer control, no al <body>.
    await expect(bar.genre).toBeFocused();
    await expect(bar.clear).toHaveCount(0);
  });

  test('un año inválido en la URL se ignora sin romper la página', async ({ page, request, user }) => {
    await page.goto('/peliculas?anio=99');
    const bar = filterBar(page);
    // Se ve la página normal (sin filtros), con el campo vacío y sin error.
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();
    await expect(bar.year).toHaveValue('');
    await expect(bar.year).not.toHaveAttribute('aria-invalid', 'true');

    // Junto a un filtro válido, se aplica solo el válido.
    const dramaId = await genreIdByName(request, user.token, 'Drama');
    await page.goto(`/peliculas?genero=${dramaId}&anio=99`);
    await expectResultTitles(page, DRAMA_TITLES);
    await expect(bar.year).toHaveValue('');
  });

  // Dos tests y no uno: juntos rozaban los 15 s con la suite completa en paralelo.
  test('un año inválido tecleado muestra el error del campo: con cuatro cifras al momento y, si es más corto, al salir', async ({
    page,
  }) => {
    await page.goto('/peliculas');
    const bar = filterBar(page);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();

    // Cuatro cifras fuera de rango: el error sale sin salir del campo (con texto, no solo color).
    await bar.year.fill('1500');
    await expect(bar.year).toBeFocused();
    await expect(bar.year).toHaveAttribute('aria-invalid', 'true');
    await expect(bar.year).toHaveAccessibleDescription(YEAR_ERROR);
    await expect(page.getByRole('alert').filter({ hasText: YEAR_ERROR })).toBeVisible();

    // Mientras se escribe «99» (podría ser «1999») no se riñe; al salir del campo, sí.
    await bar.year.fill('99');
    await expect(bar.year).not.toHaveAttribute('aria-invalid', 'true');
    await bar.year.press('Tab');
    await expect(bar.year).toHaveAttribute('aria-invalid', 'true');
    await expect(bar.year).toHaveAccessibleDescription(YEAR_ERROR);

    // La página sigue igual: sin filtro en la URL y con el banner.
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();
  });

  test('un año inválido no llega a la búsqueda: junto a un género solo se aplica el género, y al corregirlo, sí', async ({
    page,
  }) => {
    await page.goto('/peliculas');
    const bar = filterBar(page);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();

    // Intro con el año inválido tampoco lo aplica.
    await bar.year.fill('1500');
    await bar.year.press('Enter');

    // Al elegir un género se aplica el género, pero el año inválido NO llega a la URL ni a la búsqueda.
    await bar.genre.selectOption({ label: 'Acción' });
    await expect(page).toHaveURL(/\/peliculas\?genero=\d+$/);
    await expectResultTitles(page, ACTION_TITLES);
    await expect(bar.year).toHaveValue('1500');

    // Corregido, se aplica.
    await bar.year.fill('2014');
    await expect(page).toHaveURL(/\/peliculas\?genero=\d+&anio=2014$/);
    await expect(bar.year).not.toHaveAttribute('aria-invalid', 'true');
    await expectResultTitles(page, ACTION_2014_TITLES);
  });

  test('una tarjeta lleva a la página de la película, en las filas y en la cuadrícula; «Atrás» conserva los filtros', async ({
    page,
  }) => {
    await page.goto('/peliculas');

    await movieCard(page.getByRole('region', { name: 'Novedades' }), 'Interstellar').click();
    await expect(page).toHaveURL(/\/peliculas\/\d+$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.goBack();
    await expect(page).toHaveURL(/\/peliculas$/);

    await filterBar(page).year.fill('2014');
    await expect(page).toHaveURL(/\?anio=2014$/);
    await movieCard(results(page), 'Interstellar').click();
    await expect(page).toHaveURL(/\/peliculas\/\d+$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();

    // Los filtros viven en la URL: «Atrás» vuelve a la misma búsqueda, con sus resultados.
    await page.goBack();
    await expect(page).toHaveURL(/\/peliculas\?anio=2014$/);
    await expect(filterBar(page).year).toHaveValue('2014');
    await expect(movieCard(results(page), 'Interstellar')).toBeVisible();
  });

  /**
   * Revisión QA: los géneros de la ficha de una película son enlaces a
   * `/peliculas?genero=<id>`. Los tests unitarios comprueban el `href`; este,
   * que al seguirlo la página de verdad aplica ese filtro (el control lo
   * refleja y la cuadrícula trae exactamente las películas de ese género) y que
   * «Atrás» vuelve a la película.
   */
  test('un género de la ficha lleva a /peliculas con ese filtro aplicado; «Atrás» vuelve a la película', async ({
    page,
    request,
    user,
  }) => {
    const dramaId = await genreIdByName(request, user.token, 'Drama');
    await page.goto('/peliculas');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), 'Interstellar').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();
    const detailUrl = page.url();

    // Por expresión: el nombre exacto (sin espacio ante la coma) lo vigila el test siguiente.
    await page
      .getByRole('region', { name: 'Ficha' })
      .getByRole('link', { name: /^Drama\b.*ver todas las películas del género$/ })
      .click();

    await expect(page).toHaveURL(new RegExp(`/peliculas\\?genero=${dramaId}$`));
    await expect(filterBar(page).genre).toHaveValue(String(dramaId));
    await expectResultTitles(page, DRAMA_TITLES);

    await page.goBack();
    await expect(page).toHaveURL(detailUrl);
    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();
  });

  /**
   * Revisión QA — regresión de un defecto ya corregido.
   *
   * El nombre accesible de un enlace de género debe ser «Drama, ver todas las
   * películas del género» (es lo que afirma el test unitario, que pasa en
   * jsdom). Con un `<span class="sr-only">` tras el texto visible, Chromium real
   * daba «Drama , ver todas…», con un espacio antes de la coma: el span lleva
   * `position: absolute`, que lo convierte en bloque, y el navegador separa con
   * un espacio el texto de los hijos que no son en línea al calcular el nombre.
   * jsdom no aplica CSS y no lo ve. Ahora el nombre va en `aria-label`.
   */
  test('el nombre accesible de un género de la ficha es «Drama, ver todas las películas del género» (sin espacio ante la coma)', async ({
    page,
  }) => {
    await page.goto('/peliculas');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), 'Interstellar').click();
    const facts = page.getByRole('region', { name: 'Ficha' });
    await expect(facts.getByRole('link').last()).toHaveAccessibleName('Drama, ver todas las películas del género', {
      timeout: 3000,
    });
  });

  test('móvil: tras bajar por la cuadrícula, la película se abre ARRIBA (título a la vista y foco en el contenido); «Atrás» no vuelve arriba del todo', async ({
    page,
  }) => {
    // La SPA conservaba el scroll y el foco de la página anterior (el foco, perdido en <body>).
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/peliculas?orden=titulo-asc');
    const section = results(page);
    await section.getByRole('button', { name: 'Cargar más películas' }).click();
    await expect(section.getByText(`Mostrando ${MOVIES.length} de ${MOVIES.length} películas`)).toBeVisible();

    const lastTitle = ALL_BY_TITLE[ALL_BY_TITLE.length - 1];
    const card = movieCard(section, lastTitle);
    await card.scrollIntoViewIfNeeded();
    const scrolledTo = await page.evaluate(() => window.scrollY);
    expect(scrolledTo, 'la última tarjeta queda muy por debajo de la primera pantalla').toBeGreaterThan(812);

    await card.click();
    await expect(page).toHaveURL(/\/peliculas\/\d+$/);
    const heading = page.getByRole('heading', { level: 1, name: lastTitle });
    await expect(heading).toBeVisible();
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    await expect(heading).toBeInViewport();
    // El foco no se queda en <body>: va al contenido, para que el lector de pantalla anuncie la página nueva.
    await expect(page.getByRole('main')).toBeFocused();

    // «Atrás» no sube arriba del todo: el navegador restaura la posición de esa entrada del historial.
    await page.goBack();
    await expect(page).toHaveURL(/\/peliculas\?orden=titulo-asc$/);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
  });

  test('móvil: un enlace de la barra desde lo más bajo de /peliculas abre la portada arriba, no a la misma altura', async ({
    page,
  }) => {
    // La página de una película cabe entera en 812 px (el navegador la deja arriba igualmente); la portada no,
    // así que aquí el scroll heredado sí se notaría.
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/peliculas?orden=titulo-asc');
    const section = results(page);
    await section.getByRole('button', { name: 'Cargar más películas' }).click();
    await expect(section.getByText(`Mostrando ${MOVIES.length} de ${MOVIES.length} películas`)).toBeVisible();
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(812);

    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Inicio' }).click();
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    // Comprobación de que la prueba sirve: la portada es más alta que la pantalla, así que podría quedarse abajo.
    expect(await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight)).toBeGreaterThan(812);
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    await expect(page.getByRole('main')).toBeFocused();
  });

  test('cambiar un filtro no sube la página (es la misma página con otra búsqueda)', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/peliculas?orden=titulo-asc');
    const bar = filterBar(page);
    await expect(results(page).getByRole('status').first()).toHaveText(`${MOVIES.length} películas`);
    await bar.sort.scrollIntoViewIfNeeded();
    await page.evaluate(() => window.scrollBy(0, 200));
    const before = await page.evaluate(() => window.scrollY);
    expect(before).toBeGreaterThan(0);

    await bar.sort.selectOption({ label: 'Título Z–A' });
    await expect(page).toHaveURL(/\/peliculas\?orden=titulo-desc$/);
    await expect(results(page).getByRole('listitem').first()).toContainText(ALL_BY_TITLE[ALL_BY_TITLE.length - 1]);
    expect(await page.evaluate(() => window.scrollY)).toBe(before);
  });

  test('teclado: la barra de filtros se recorre con Tab en orden y con foco visible, también «Quitar filtros»', async ({
    page,
  }) => {
    await page.goto('/peliculas');
    const bar = filterBar(page);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();

    // Lo primero del contenido tras la barra superior (el último control de esta es el menú de usuario).
    await page.getByRole('button', { name: 'Menú de usuario' }).focus();
    await page.keyboard.press('Tab');
    await expectVisibleFocus(bar.genre, 'Género');
    await page.keyboard.press('Tab');
    await expectVisibleFocus(bar.year, 'Año');
    await page.keyboard.press('Tab');
    await expectVisibleFocus(bar.sort, 'Ordenar por');

    // Solo con teclado: un año + Intro. El foco no se mueve y aparece «Quitar filtros» como siguiente parada.
    // (Que Intro aplique SIN esperar los 300 ms lo prueba Vitest con el reloj falso; aquí no se puede distinguir.)
    await bar.year.focus();
    await page.keyboard.type('2014');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\?anio=2014$/);
    await expect(results(page).getByRole('status').first()).toHaveText(`${TITLES_2014.length} películas`);
    await expect(bar.year).toBeFocused();
    await page.keyboard.press('Tab');
    await expectVisibleFocus(bar.sort, 'Ordenar por');
    await page.keyboard.press('Tab');
    await expectVisibleFocus(bar.clear, 'Quitar filtros');

    // Intro en «Quitar filtros»: vuelve a la vista por filas y el foco al primer control.
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();
    await expect(bar.genre).toBeFocused();
  });
});

/**
 * Fila «Películas similares» al pie de `/peliculas/:id`, contra el backend real
 * (`GET /api/movies/{id}/similar?limit=12`). El catálogo sembrado está elegido
 * para que el resultado sea EXACTO sin depender del desempate entre películas
 * con los mismos géneros en común (el contrato solo fija «más géneros en común
 * primero»): por eso se compara el conjunto de títulos y no su orden.
 */
test.describe('Películas similares', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  /** Títulos de la fila «Películas similares» de la página abierta. */
  async function similarTitles(page: Page): Promise<string[]> {
    const row = page.getByRole('region', { name: 'Películas similares' });
    await expect(row).toBeVisible();
    // El título es el primer párrafo de cada tarjeta (el segundo es «2014 · 2h 49m»).
    return row.getByRole('link').evaluateAll((cards) => cards.map((card) => card.querySelector('p')?.textContent ?? ''));
  }

  /** Abre la página de una película del catálogo sembrado buscándola por año en `/peliculas`. */
  async function openMovie(page: Page, title: string): Promise<void> {
    const movie = MOVIES.find((candidate) => candidate.title === title)!;
    await page.goto(`/peliculas?anio=${movie.releaseYear}`);
    await movieCard(results(page), title).click();
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  }

  test('las 12 que comparten dos géneros con «Dune: Parte Dos» (que tiene los tres), sin ella misma; tras la ficha', async ({
    page,
  }) => {
    await openMovie(page, HERO_TITLE);
    // Dune es Ciencia ficción + Acción + Drama: las de DOS géneros en común son exactamente 12 (el límite).
    const expected = MOVIES.filter(
      (movie) => movie.title !== HERO_TITLE && movie.genres.length === 2,
    ).map((movie) => movie.title);
    expect(expected).toHaveLength(12);

    expect((await similarTitles(page)).sort()).toEqual([...expected].sort());
    const headings = await page.getByRole('heading', { level: 2 }).allTextContents();
    expect(headings.indexOf('Películas similares')).toBeGreaterThan(headings.indexOf('Ficha'));
  });

  test('con un solo género («Puerto Seco», Drama) salen todas las demás de Drama', async ({ page }) => {
    await openMovie(page, 'Puerto Seco');
    const expected = MOVIES.filter((movie) => movie.title !== 'Puerto Seco' && movie.genres.includes('Drama')).map(
      (movie) => movie.title,
    );

    expect((await similarTitles(page)).sort()).toEqual([...expected].sort());
  });

  test('pulsar una similar abre su página arriba, con el foco en el contenido, y la fila pasa a ser la suya', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await openMovie(page, HERO_TITLE);
    const row = page.getByRole('region', { name: 'Películas similares' });
    const card = movieCard(row, 'Interstellar');
    await card.scrollIntoViewIfNeeded();
    expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);

    await card.click();

    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
    await expect(page.getByRole('main')).toBeFocused();
    // Ya son las de Interstellar: está Dune (comparte Ciencia ficción y Drama) y no Interstellar.
    await expect(movieCard(row, HERO_TITLE)).toBeVisible();
    await expect(movieCard(row, 'Interstellar')).toHaveCount(0);
    // A 375 px la fila se desplaza por dentro: la página no tiene scroll horizontal.
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  });

  /**
   * Regresión (revisión QA, corregida con `key={movie.id}` en MovieDetailPage).
   * Al pulsar una similar cuya página y cuya fila YA estaban en la caché, React
   * reutilizaba la misma `PosterRow` (la ruta no cambia de componente y nada
   * pasa por «cargando»), así que la fila nueva heredaba el desplazamiento
   * horizontal de la anterior: empezaba a mitad, con las más parecidas fuera de
   * la vista. Con datos nuevos no pasaba porque la silueta desmonta la fila.
   */
  test('volver a una película ya vista desde la fila desplazada empieza la fila nueva por el principio', async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 900 });
    await openMovie(page, HERO_TITLE);
    const row = page.getByRole('region', { name: 'Películas similares' });
    const scroller = row.getByRole('list');

    // Deja Interstellar (detalle y fila) en la caché y vuelve a Dune desde la fila de Interstellar.
    await movieCard(row, 'Interstellar').click();
    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();
    await movieCard(row, HERO_TITLE).click();
    await expect(page.getByRole('heading', { level: 1, name: HERO_TITLE })).toBeVisible();
    await expect(movieCard(row, 'Interstellar')).toBeVisible();

    // Desplaza la fila de Dune hasta el final y abre Interstellar (en caché) desde ahí.
    await scroller.evaluate((list) => (list.scrollLeft = list.scrollWidth));
    await expect.poll(() => scroller.evaluate((list) => list.scrollLeft)).toBeGreaterThan(0);
    // Clic sin que Playwright desplace la fila para enseñar la tarjeta (como un usuario que ya la ve).
    await movieCard(row, 'Interstellar').evaluate((card: HTMLElement) => card.click());
    await expect(page.getByRole('heading', { level: 1, name: 'Interstellar' })).toBeVisible();

    expect(await scroller.evaluate((list) => list.scrollLeft)).toBe(0);
  });
});
