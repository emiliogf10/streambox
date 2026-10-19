/**
 * E2E responsive: en móvil, tablet y escritorio ninguna pantalla debe tener
 * scroll horizontal de PÁGINA y los elementos clave deben verse enteros.
 *
 * Protege contra el fallo más común al tocar estilos: una tarjeta, una fila o la
 * barra de navegación que se sale por la derecha en 375 px. En la barra se
 * comprueba además que ningún elemento se solape con otro (a 768 px el cálculo
 * de anchos es muy justo), también con el enlace «Administrar» que solo ven los
 * administradores, y que las pantallas del panel (tabla, formulario, géneros)
 * caben sin scroll horizontal.
 *
 * Nota: las filas de películas SÍ tienen scroll horizontal INTERNO (carrusel);
 * lo que no puede desbordarse es la página (`documentElement`).
 */
import type { Locator, Page } from '@playwright/test';
import { addFavoritesByTitle, addSeriesFavoritesByTitle, findSeriesIdAsAdmin, genreIdByName, loginAdmin } from './support/api';
import { EMPTY_SERIES, HERO_TITLE, SERIES_HERO } from './support/catalog';
import { expect, test, waitForMovieForm } from './support/fixtures';
import { expectCovers, expectWholePoster } from './support/images';

const VIEWPORTS = [
  { name: 'móvil 375x812', width: 375, height: 812 },
  { name: 'tablet 768x1024', width: 768, height: 1024 },
  { name: 'escritorio 1280x800', width: 1280, height: 800 },
] as const;

/** Comprueba que la página no se desplaza horizontalmente. */
async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(scrollWidth, `scrollWidth (${scrollWidth}) debe ser ≤ clientWidth (${clientWidth})`).toBeLessThanOrEqual(clientWidth);
}

/** Comprueba que el elemento es visible y cabe entero, en horizontal, dentro de la ventana. */
async function expectFullyInsideViewportWidth(locator: Locator, page: Page, label: string): Promise<void> {
  await expect(locator, `${label} debe ser visible`).toBeVisible();
  const box = await locator.boundingBox();
  const width = page.viewportSize()?.width ?? 0;
  expect(box, `${label} sin caja`).not.toBeNull();
  expect(box!.x, `${label} se sale por la izquierda`).toBeGreaterThanOrEqual(-0.5);
  expect(box!.x + box!.width, `${label} se sale por la derecha`).toBeLessThanOrEqual(width + 0.5);
}

/**
 * La barra superior cabe: cada elemento se ve entero dentro de la ventana y ninguno pisa a otro (a 768 px
 * el margen es mínimo, y con el enlace «Administrar» de los administradores aún más).
 *
 * @param withAdminLink si se espera también el enlace «Administrar»
 */
async function expectNavbarFits(page: Page, withAdminLink: boolean): Promise<void> {
  const nav = page.getByRole('navigation', { name: 'Principal' });
  const items: [Locator, string][] = [
    [page.getByRole('link', { name: 'streambox' }), 'logo'],
    [nav.getByRole('link', { name: 'Inicio' }), 'Inicio'],
    [nav.getByRole('link', { name: 'Películas' }), 'Películas'],
    [nav.getByRole('link', { name: 'Series' }), 'Series'],
    [nav.getByRole('link', { name: 'Mi lista' }), 'Mi lista'],
    ...(withAdminLink ? ([[nav.getByRole('link', { name: 'Administrar' }), 'Administrar']] as [Locator, string][]) : []),
    [page.getByRole('combobox', { name: 'Buscar películas y series por título' }), 'buscador'],
    [page.getByRole('button', { name: 'Menú de usuario' }), 'menú de usuario'],
  ];

  const boxes: { label: string; x: number; y: number; width: number; height: number }[] = [];
  for (const [locator, label] of items) {
    await expectFullyInsideViewportWidth(locator, page, label);
    const box = await locator.boundingBox();
    boxes.push({ label, ...box! });
  }
  for (let i = 0; i < boxes.length; i += 1) {
    for (let j = i + 1; j < boxes.length; j += 1) {
      const a = boxes[i];
      const b = boxes[j];
      const overlap = a.x < b.x + b.width - 1 && b.x < a.x + a.width - 1 && a.y < b.y + b.height - 1 && b.y < a.y + a.height - 1;
      expect(overlap, `«${a.label}» y «${b.label}» se solapan en la barra`).toBe(false);
    }
  }
}

/**
 * La barra de filtros de `/peliculas` (o la de `/series`, que es la misma) cabe: sus tres controles se ven
 * enteros y no se pisan entre sí. En móvil es una rejilla de dos columnas (género y año arriba, orden debajo)
 * y desde `sm` una fila.
 *
 * @param formName nombre accesible del formulario: «Filtrar películas» o «Filtrar series»
 */
async function expectFilterBarFits(page: Page, formName = 'Filtrar películas'): Promise<void> {
  const form = page.getByRole('form', { name: formName });
  const controls: [Locator, string][] = [
    [form.getByRole('combobox', { name: 'Género' }), 'filtro Género'],
    [form.getByRole('textbox', { name: 'Año' }), 'filtro Año'],
    [form.getByRole('combobox', { name: 'Ordenar por' }), 'filtro Ordenar por'],
  ];
  const boxes: { label: string; x: number; y: number; width: number; height: number }[] = [];
  for (const [locator, label] of controls) {
    await expectFullyInsideViewportWidth(locator, page, label);
    boxes.push({ label, ...(await locator.boundingBox())! });
  }
  for (let i = 0; i < boxes.length; i += 1) {
    for (let j = i + 1; j < boxes.length; j += 1) {
      const [a, b] = [boxes[i], boxes[j]];
      const overlap = a.x < b.x + b.width - 1 && b.x < a.x + a.width - 1 && a.y < b.y + b.height - 1 && b.y < a.y + a.height - 1;
      expect(overlap, `«${a.label}» y «${b.label}» se solapan`).toBe(false);
    }
  }
}

/**
 * «Quitar filtros» de la barra va pegado al desplegable «Ordenar por»: o en su
 * misma línea y centrado con él, o justo debajo (a la distancia normal entre
 * controles, 12 px). Antes, a 768 px, bajaba solo a otra línea con el margen
 * pensado para alinearse en fila y quedaba descolgado (~38 px de hueco).
 */
async function expectClearFiltersNextToSort(page: Page, formName = 'Filtrar películas'): Promise<void> {
  const form = page.getByRole('form', { name: formName });
  const sort = (await form.getByRole('combobox', { name: 'Ordenar por' }).boundingBox())!;
  const clear = (await form.getByRole('button', { name: 'Quitar filtros' }).boundingBox())!;
  const sameLine = clear.y < sort.y + sort.height && sort.y < clear.y + clear.height;
  if (sameLine) {
    const offset = Math.abs(clear.y + clear.height / 2 - (sort.y + sort.height / 2));
    expect(offset, '«Quitar filtros» no está centrado con «Ordenar por»').toBeLessThanOrEqual(1);
    expect(clear.x, '«Quitar filtros» debería ir a la derecha de «Ordenar por»').toBeGreaterThanOrEqual(sort.x + sort.width);
  } else {
    const gap = clear.y - (sort.y + sort.height);
    expect(gap, '«Quitar filtros» debería ir justo debajo de «Ordenar por»').toBeGreaterThanOrEqual(0);
    expect(gap, `hueco de ${gap}px entre «Ordenar por» y «Quitar filtros»`).toBeLessThanOrEqual(16);
  }
}

for (const viewport of VIEWPORTS) {
  test.describe(`Responsive ${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    test('/login', async ({ page }) => {
      await page.goto('/login');
      await expectFullyInsideViewportWidth(page.getByRole('heading', { level: 1, name: 'Bienvenido de nuevo' }), page, 'título');
      await expectFullyInsideViewportWidth(page.getByLabel('Correo electrónico'), page, 'campo de correo');
      await expectFullyInsideViewportWidth(page.getByLabel('Contraseña'), page, 'campo de contraseña');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Iniciar sesión' }), page, 'botón de entrar');
      await expectFullyInsideViewportWidth(page.getByRole('link', { name: 'Regístrate' }), page, 'enlace a registro');
      await expectNoHorizontalScroll(page);
    });

    test('/registro (también con todos los errores a la vista)', async ({ page }) => {
      await page.goto('/registro');
      await expectFullyInsideViewportWidth(page.getByLabel('Nombre de usuario'), page, 'campo de usuario');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Crear cuenta' }), page, 'botón de crear cuenta');
      await expectNoHorizontalScroll(page);

      // Con los mensajes de error los textos son más largos: tampoco debe desbordar.
      await page.getByRole('button', { name: 'Crear cuenta' }).click();
      await expect(page.getByText('La contraseña debe tener entre 12 y 64 caracteres.')).toBeVisible();
      await expectNoHorizontalScroll(page);
    });

    test('«Revisa tu correo» con un correo muy largo (sin cortes de palabra no hay sitio a 375 px)', async ({ page }) => {
      // Respuesta simulada: aquí solo importa la maquetación, no crear una cuenta.
      await page.route('**/api/users', (route) =>
        route.fulfill({
          status: 202,
          contentType: 'application/json',
          body: JSON.stringify({ message: 'Si el correo es correcto, te llegará un enlace para confirmar tu cuenta.' }),
        }),
      );
      const longEmail = `nombre.apellido.muy.largo.sin.espacios.${'x'.repeat(20)}@subdominio.ejemplo-largo.com`;
      await page.goto('/registro');
      await page.getByLabel('Nombre de usuario').fill('usuario_responsive');
      await page.getByLabel('Correo electrónico').fill(longEmail);
      await page.getByLabel('Contraseña').fill('Tranvia-Lienzo-Faro-47');
      await page.getByRole('button', { name: 'Crear cuenta' }).click();

      await expectFullyInsideViewportWidth(page.getByRole('heading', { level: 1, name: 'Revisa tu correo' }), page, 'título');
      await expectFullyInsideViewportWidth(page.getByText(longEmail), page, 'correo escrito');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: /^Reenviar en / }), page, 'botón de reenviar');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Corregir el correo' }), page, '«Corregir el correo»');
      await expectNoHorizontalScroll(page);
    });

    test('/verificar-email: «Confirma tu correo» y «enlace no válido» con su formulario', async ({ page }) => {
      await page.goto(`/verificar-email#token=${'A'.repeat(43)}`);
      await expectFullyInsideViewportWidth(page.getByRole('heading', { level: 1, name: 'Confirma tu correo' }), page, 'título');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Confirmar mi correo' }), page, 'botón de confirmar');
      await expectNoHorizontalScroll(page);

      await page.goto('/verificar-email');
      await expectFullyInsideViewportWidth(
        page.getByRole('heading', { level: 1, name: 'El enlace no es válido o ha caducado' }),
        page,
        'título',
      );
      await expectFullyInsideViewportWidth(page.getByLabel('Correo electrónico'), page, 'campo de correo');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Enviar un enlace nuevo' }), page, 'botón de enviar');
      await expectNoHorizontalScroll(page);
    });

    test('/ (portada): barra, banner y botones', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/');
      await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();

      // Barra de un usuario normal: sin «Administrar». Nada se sale ni se pisa (a 768 px el margen es mínimo).
      await expectNavbarFits(page, false);
      await expect(page.getByRole('link', { name: 'Administrar' })).toHaveCount(0);

      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expectFullyInsideViewportWidth(banner, page, 'banner');
      await expectFullyInsideViewportWidth(banner.getByRole('heading', { level: 2, name: HERO_TITLE }), page, 'título del banner');
      await expectFullyInsideViewportWidth(banner.getByRole('link', { name: /^Ver ahora/ }), page, '«Ver ahora»');
      await expectFullyInsideViewportWidth(banner.getByRole('button', { name: /^Mi lista/ }), page, '«Mi lista» del banner');
      await expectFullyInsideViewportWidth(banner.getByRole('link', { name: /^Más información/ }), page, '«Más información»');

      // El fondo cubre el banner y el póster nítido (tarjeta 2:3) se ve ENTERO en todos los anchos.
      // Antes, en móvil, el póster era el fondo nítido y el degradado del texto tapaba su mitad
      // inferior; ahora va pequeño junto al título (como en la página de cada título) y debe verse sin recortes.
      const images = banner.locator('img');
      await expectCovers(images.first(), banner, 'fondo del banner');
      await expectWholePoster(images.last(), banner, 'póster del banner');
      // El póster va JUNTO al título (misma fila en móvil, columna contigua en escritorio): el hueco
      // horizontal entre ambos no supera los 64 px. Regresión que evita: a 1280 px el póster estaba
      // en el borde derecho, a ~430 px del texto.
      const posterBox = await images.last().boundingBox();
      const titleBox = await banner.getByRole('heading', { level: 2, name: HERO_TITLE }).boundingBox();
      expect(titleBox!.x - (posterBox!.x + posterBox!.width), 'hueco entre póster y título').toBeLessThanOrEqual(64);
      expect(titleBox!.x - (posterBox!.x + posterBox!.width), 'el título no pisa el póster').toBeGreaterThanOrEqual(0);
      await expectNoHorizontalScroll(page);

      // «Estreno reciente» comparte fila con el primer dato (el año): antes, a 375 px, la lista de datos
      // entera no cabía a su lado, pasaba a la línea de abajo y la etiqueta se quedaba sola.
      const facts = banner.getByRole('list', { name: 'Datos de la película' }).getByRole('listitem');
      const badgeBox = await facts.first().boundingBox();
      const yearBox = await facts.nth(1).boundingBox();
      expect(await facts.first().textContent()).toBe('Estreno reciente');
      expect(Math.abs(badgeBox!.y + badgeBox!.height / 2 - (yearBox!.y + yearBox!.height / 2)), '«Estreno reciente» y el año en la misma fila').toBeLessThanOrEqual(6);

      // Con la página completa (segunda página incluida) tampoco hay desborde.
      await page.getByRole('button', { name: 'Cargar más películas' }).scrollIntoViewIfNeeded();
      await page.getByRole('button', { name: 'Cargar más películas' }).click();
      await expect(page.getByText(/Mostrando 25 de 25/)).toBeVisible();
      await expectNoHorizontalScroll(page);
    });

    test('/series y la página de una serie (selector de temporadas y episodios)', async ({ page, request, user, signIn }) => {
      await signIn(user);
      await page.goto('/series');
      const banner = page.getByRole('region', { name: SERIES_HERO.title });
      await expect(banner).toBeVisible();
      await expectNavbarFits(page, false);
      await expectFullyInsideViewportWidth(page.getByRole('heading', { level: 1, name: 'Series' }), page, 'título «Series»');
      await expectFullyInsideViewportWidth(banner.getByRole('link', { name: /^Más información/ }), page, '«Más información»');
      await expectFullyInsideViewportWidth(banner.getByRole('button', { name: /^Mi lista/ }), page, '«Mi lista» del banner');
      await expectWholePoster(banner.locator('img').last(), banner, 'póster del banner de series');
      await expectFilterBarFits(page, 'Filtrar series');
      await expectNoHorizontalScroll(page);

      // Con filtros: la cuadrícula de resultados, «Quitar filtros» junto al orden y las tarjetas enteras.
      const dramaId = await genreIdByName(request, user.token, 'Drama');
      await page.goto(`/series?genero=${dramaId}&orden=titulo-asc`);
      const results = page.getByRole('region', { name: 'Resultados' });
      await expect(results.getByRole('status').first()).toHaveText('4 series');
      await expectFilterBarFits(page, 'Filtrar series');
      await expectClearFiltersNextToSort(page, 'Filtrar series');
      const cards = results.getByRole('listitem');
      for (let index = 0; index < 4; index += 1) {
        await expectFullyInsideViewportWidth(cards.nth(index), page, `tarjeta ${index + 1} de la cuadrícula de series`);
      }
      await expectNoHorizontalScroll(page);

      // De vuelta al catálogo sin filtros para abrir la serie del banner.
      await page.goto('/series');

      await banner.getByRole('link', { name: /^Más información/ }).click();
      await expect(page.getByRole('heading', { level: 1, name: SERIES_HERO.title })).toBeVisible();
      const picker = page.getByRole('navigation', { name: 'Temporadas' });
      for (const name of ['Temporada 1', 'Temporada 2']) {
        await expectFullyInsideViewportWidth(picker.getByRole('link', { name }), page, `«${name}»`);
      }
      const episodes = page.getByRole('list', { name: 'Episodios de la temporada 1' });
      const watchLinks = episodes.getByRole('link', { name: /^Ver T1:E/ });
      await expect(watchLinks).toHaveCount(SERIES_HERO.seasons[0]);
      for (let index = 0; index < SERIES_HERO.seasons[0]; index += 1) {
        await expectFullyInsideViewportWidth(watchLinks.nth(index), page, `«Ver» del episodio ${index + 1}`);
      }
      await expectNoHorizontalScroll(page);

      // Con su sección de series en «Mi lista» tampoco hay desborde.
      await addSeriesFavoritesByTitle(request, user.token, [SERIES_HERO.title]);
      await page.goto('/favorites');
      const seriesSection = page.getByRole('region', { name: 'Series' });
      await expectFullyInsideViewportWidth(seriesSection.getByRole('link', { name: new RegExp(`^${SERIES_HERO.title}`) }), page, 'tarjeta de serie');
      await expectFullyInsideViewportWidth(
        page.getByRole('region', { name: 'Películas' }).getByRole('link', { name: 'Explorar películas' }),
        page,
        '«Explorar películas»',
      );
      await expectNoHorizontalScroll(page);
    });

    test('/peliculas sin filtros: barra superior, título, barra de filtros y banner', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/peliculas');
      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expect(banner).toBeVisible();
      await expectNavbarFits(page, false);
      await expectFullyInsideViewportWidth(page.getByRole('heading', { level: 1, name: 'Películas' }), page, 'título «Películas»');
      await expectFilterBarFits(page);
      await expectFullyInsideViewportWidth(banner.getByRole('link', { name: /^Ver ahora/ }), page, '«Ver ahora»');
      await expectFullyInsideViewportWidth(banner.getByRole('link', { name: /^Más información/ }), page, '«Más información»');
      await expectWholePoster(banner.locator('img').last(), banner, 'póster del banner de películas');
      await expectNoHorizontalScroll(page);
    });

    // Con filtros, dos tests (cuadrícula + error del año, y vacío): juntos rozaban los 15 s a 375 px con la suite completa.
    // Se abren con la URL ya filtrada (como un enlace compartido): el manejo de la barra lo prueba `movies.spec.ts`.
    test('/peliculas con filtros: cuadrícula, «Quitar filtros» y el error del año', async ({ page, request, user, signIn }) => {
      const actionId = await genreIdByName(request, user.token, 'Acción');
      await signIn(user);
      // Acción de 2014: dos resultados en la cuadrícula.
      await page.goto(`/peliculas?genero=${actionId}&anio=2014`);
      const form = page.getByRole('form', { name: 'Filtrar películas' });
      const results = page.getByRole('region', { name: 'Resultados' });
      const cards = results.getByRole('listitem').getByRole('link');
      await expect(cards).toHaveCount(2);
      await expectFilterBarFits(page);
      await expectFullyInsideViewportWidth(form.getByRole('button', { name: 'Quitar filtros' }), page, '«Quitar filtros»');
      await expectClearFiltersNextToSort(page);
      for (let index = 0; index < 2; index += 1) {
        await expectFullyInsideViewportWidth(cards.nth(index), page, `tarjeta ${index + 1}`);
      }
      await expectNoHorizontalScroll(page);

      // El error bajo el año (el campo más estrecho) no se sale, no empuja la barra fuera de la pantalla ni descoloca «Quitar filtros».
      const year = form.getByRole('textbox', { name: 'Año' });
      await year.fill('99');
      await year.press('Tab');
      const yearError = page.getByRole('alert').filter({ hasText: 'Escribe un año entre 1888 y 2100.' });
      await expectFullyInsideViewportWidth(yearError, page, 'error del año');
      await expectFilterBarFits(page);
      await expectClearFiltersNextToSort(page);
      await expectNoHorizontalScroll(page);
    });

    test('/peliculas sin resultados: el estado vacío y sus dos «Quitar filtros» caben', async ({ page, request, user, signIn }) => {
      const actionId = await genreIdByName(request, user.token, 'Acción');
      await signIn(user);
      // Acción de 1982: ninguna («Blade Runner» es solo Ciencia ficción).
      await page.goto(`/peliculas?genero=${actionId}&anio=1982`);
      const results = page.getByRole('region', { name: 'Resultados' });
      await expect(results.getByRole('heading', { name: 'No hay películas con estos filtros' })).toBeVisible();
      await expectFilterBarFits(page);
      await expectFullyInsideViewportWidth(
        page.getByRole('form', { name: 'Filtrar películas' }).getByRole('button', { name: 'Quitar filtros' }),
        page,
        '«Quitar filtros» de la barra',
      );
      await expectClearFiltersNextToSort(page);
      await expectFullyInsideViewportWidth(results.getByRole('button', { name: 'Quitar filtros' }), page, '«Quitar filtros» del vacío');
      await expectNoHorizontalScroll(page);
    });

    test('/favorites con películas y vacía', async ({ page, request, user, signIn }) => {
      await signIn(user);
      await page.goto('/favorites');
      await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
      await expectFullyInsideViewportWidth(page.getByRole('link', { name: 'Explorar catálogo' }), page, 'invitación a explorar');
      await expectNoHorizontalScroll(page);

      await addFavoritesByTitle(request, user.token, [
        'Dredd',
        'Mad Max',
        'Interstellar',
        'El increíble viaje de la nave perdida más allá de las estrellas olvidadas',
        'La Casa del Lago',
      ]);
      await page.reload();
      await expect(page.getByRole('main').getByRole('link', { name: /\d{4} ·/ })).toHaveCount(5);
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Vaciar lista' }), page, '«Vaciar lista»');
      await expectNoHorizontalScroll(page);

      // Toda tarjeta cabe entera en horizontal (la rejilla no debe sacarlas de la pantalla).
      const cards = page.getByRole('main').getByRole('link', { name: /\d{4} ·/ });
      for (let index = 0; index < 5; index += 1) {
        await expectFullyInsideViewportWidth(cards.nth(index), page, `tarjeta ${index + 1}`);
      }
    });

    test('página de una película y diálogo de confirmación caben en pantalla', async ({ page, request, user, signIn }) => {
      await addFavoritesByTitle(request, user.token, ['Dredd']);
      await signIn(user);
      await page.goto('/favorites');

      await page.getByRole('button', { name: 'Vaciar lista' }).click();
      const confirm = page.getByRole('alertdialog');
      await expectFullyInsideViewportWidth(confirm, page, 'diálogo de confirmación');
      await expectFullyInsideViewportWidth(confirm.getByRole('button', { name: 'Sí, vaciar lista' }), page, 'botón de confirmar');
      await confirm.getByRole('button', { name: 'Cancelar' }).click();

      await page.getByRole('link', { name: /^Dredd \d{4}/ }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Dredd' })).toBeVisible();
      await expectNavbarFits(page, false);
      const header = page.getByRole('region', { name: 'Dredd' });
      await expectFullyInsideViewportWidth(header, page, 'cabecera de la película');
      await expectFullyInsideViewportWidth(header.getByRole('heading', { level: 1, name: 'Dredd' }), page, 'título');
      await expectFullyInsideViewportWidth(header.getByRole('link', { name: /^Ver ahora/ }), page, '«Ver ahora»');
      await expectFullyInsideViewportWidth(header.getByRole('button', { name: /^En mi lista/ }), page, '«En mi lista»');
      // El póster de la cabecera se ve entero también en 375 px (más pequeño, a la izquierda del título).
      await expectWholePoster(header.locator('img').last(), header, 'póster de la cabecera');
      // La ficha y sus enlaces de género caben (en 375 px es una rejilla de dos columnas).
      const facts = page.getByRole('region', { name: 'Ficha' });
      await expectFullyInsideViewportWidth(facts, page, 'ficha');
      const genreLinks = facts.getByRole('link', { name: /ver todas las películas del género$/ });
      await expect(genreLinks.first()).toBeVisible();
      for (let index = 0; index < (await genreLinks.count()); index += 1) {
        await expectFullyInsideViewportWidth(genreLinks.nth(index), page, `género ${index + 1} de la ficha`);
      }
      await expectNoHorizontalScroll(page);
    });

    // Test aparte (y no dentro del anterior, que ya roza el límite de tiempo en móvil).
    test('panel: edición de una serie con episodios y el diálogo de episodio con errores', async ({ page, request, signIn }) => {
      // Solo lectura: el diálogo se abre y se valida en el cliente, pero no se guarda nada.
      const adminToken = await loginAdmin(request);
      await signIn({ token: adminToken });
      await page.goto(`/admin/series/${await findSeriesIdAsAdmin(request, adminToken, SERIES_HERO.title)}/editar`);
      const episodes = page.getByRole('region', { name: 'Episodios', exact: true });
      const firstEpisode = episodes.getByRole('listitem').first();
      await expect(firstEpisode).toBeVisible();
      await expectFullyInsideViewportWidth(firstEpisode.getByRole('button', { name: /^Editar episodio / }), page, '«Editar» de un episodio');
      await expectFullyInsideViewportWidth(firstEpisode.getByRole('button', { name: /^Borrar episodio / }), page, '«Borrar» de un episodio');
      await expectNoHorizontalScroll(page);
      await episodes.getByRole('button', { name: 'Añadir episodio' }).click();
      const episodeDialog = page.getByRole('dialog', { name: 'Añadir episodio' });
      await episodeDialog.getByRole('button', { name: 'Añadir episodio' }).click();
      await expect(episodeDialog.getByLabel('Título', { exact: true })).toHaveAccessibleDescription('El título es obligatorio');
      await expectFullyInsideViewportWidth(episodeDialog.getByLabel('Número', { exact: true }), page, 'campo Número del episodio');
      await expectFullyInsideViewportWidth(episodeDialog.getByLabel('URL del vídeo (opcional)'), page, 'campo URL del vídeo');
      await expectFullyInsideViewportWidth(episodeDialog.getByRole('button', { name: 'Cancelar' }), page, '«Cancelar» del episodio');
      await expectNoHorizontalScroll(page);
      await page.keyboard.press('Escape');
      await expect(episodeDialog).toHaveCount(0);
    });

    /**
     * Pantallas del panel de administración, una por test.
     *
     * Antes eran un único test que recorría las cinco pantallas y, a 375 px con la suite completa, tardaba
     * ~22 s de los 30 del límite: cada visita suma tiempo de carga y, al cerrar el contexto, el de guardar
     * su traza (`trace: 'retain-on-failure'`). Con la máquina cargada acabaría fallando por tiempo sin que
     * nada estuviera roto. Separados, cada uno tarda pocos segundos, son independientes (si uno falla, los
     * demás siguen comprobando lo suyo) y el informe dice directamente QUÉ pantalla se desborda.
     *
     * Todos son de solo lectura: no crean ni borran nada (el administrador y el catálogo son compartidos).
     */
    test.describe('panel de administración', () => {
      test.beforeEach(async ({ request, signIn }) => {
        await signIn({ token: await loginAdmin(request) });
      });

      test('la barra con «Administrar» y el listado de películas', async ({ page }) => {
        await page.goto('/admin/peliculas');
        const table = page.getByRole('table', { name: /^Películas del catálogo/ });
        await expect(table).toBeVisible();

        // Con el quinto enlace, la barra sigue cabiendo sin solaparse (en 375 y 768 px «Administrar» queda en icono).
        await expectNavbarFits(page, true);
        await expectNoHorizontalScroll(page);

        const sections = page.getByRole('navigation', { name: 'Secciones de administración' });
        await expectFullyInsideViewportWidth(sections.getByRole('link', { name: 'Géneros' }), page, 'pestaña Géneros');
        await expectFullyInsideViewportWidth(page.getByRole('link', { name: 'Nueva película' }), page, '«Nueva película»');
        await expectFullyInsideViewportWidth(page.getByRole('searchbox', { name: 'Buscar por título' }), page, 'buscador del panel');
        // La tabla (y las acciones de cada fila) caben: en móvil se ocultan columnas, no se desborda.
        await expectFullyInsideViewportWidth(table, page, 'tabla');
        const firstRow = table.getByRole('row').nth(1);
        await expectFullyInsideViewportWidth(firstRow.getByRole('link', { name: /^Editar / }), page, '«Editar» de la primera fila');
        await expectFullyInsideViewportWidth(firstRow.getByRole('button', { name: /^Borrar / }), page, '«Borrar» de la primera fila');
        const pagination = page.getByRole('navigation', { name: 'Paginación de películas' });
        await expectFullyInsideViewportWidth(pagination.getByRole('button', { name: 'Siguiente' }), page, '«Siguiente»');
      });

      test('/peliculas con la barra de un administrador (cinco enlaces) y la barra de filtros', async ({ page }) => {
        await page.goto('/peliculas');
        await expect(page.getByRole('region', { name: HERO_TITLE })).toBeVisible();
        // «Películas» marcada como actual y «Administrar» a la vez: el momento más ancho de la barra.
        await expect(page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Administrar' })).toBeVisible();
        await expectNavbarFits(page, true);
        await expectFilterBarFits(page);
        await expectNoHorizontalScroll(page);
      });

      test('el listado de series: tres pestañas en una fila y la marca «sin episodios»', async ({ page }) => {
        await page.goto('/admin/series');
        const seriesTable = page.getByRole('table', { name: /^Series del catálogo/ });
        await expect(seriesTable).toBeVisible();
        const sections = page.getByRole('navigation', { name: 'Secciones de administración' });
        await expectFullyInsideViewportWidth(sections.getByRole('link', { name: 'Películas' }), page, 'pestaña Películas');
        await expectFullyInsideViewportWidth(sections.getByRole('link', { name: 'Series' }), page, 'pestaña Series');
        await expectFullyInsideViewportWidth(sections.getByRole('link', { name: 'Géneros' }), page, 'pestaña Géneros (con Series)');
        await expectFullyInsideViewportWidth(page.getByRole('link', { name: 'Nueva serie' }), page, '«Nueva serie»');
        await expectFullyInsideViewportWidth(seriesTable, page, 'tabla de series');
        const emptyRow = seriesTable.getByRole('row', { name: new RegExp(`^${EMPTY_SERIES.title}`) });
        await expectFullyInsideViewportWidth(
          emptyRow.getByText('Sin episodios · oculta para los usuarios'),
          page,
          'marca de serie sin episodios',
        );
        await expectFullyInsideViewportWidth(emptyRow.getByRole('button', { name: /^Borrar / }), page, '«Borrar» de la serie vacía');
        await expectNoHorizontalScroll(page);
      });

      test('el formulario de serie con todos los errores a la vista', async ({ page }) => {
        // El año de fin, con su ayuda, va en la misma rejilla que el de estreno: no debe empujarla fuera.
        await page.goto('/admin/series/nueva');
        // Sin esta espera el clic podía caer durante el salto que provoca la llegada de los géneros (ver la función).
        await waitForMovieForm(page);
        await page.getByRole('button', { name: 'Crear serie' }).click();
        await expect(page.getByText('Indica al menos un género')).toBeVisible();
        await expectFullyInsideViewportWidth(page.getByLabel('Año de fin (opcional)'), page, 'campo de año de fin');
        await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Crear serie' }), page, '«Crear serie»');
        await expectNoHorizontalScroll(page);
      });

      test('el formulario de película con todos los errores a la vista y la vista previa', async ({ page }) => {
        await page.goto('/admin/peliculas/nueva');
        // Misma espera que en el formulario de serie: los géneros llegan aparte y desplazan el botón.
        await waitForMovieForm(page);
        await page.getByRole('button', { name: 'Crear película' }).click();
        await expect(page.getByText('Elige al menos un género')).toBeVisible();
        await expectFullyInsideViewportWidth(page.getByLabel('URL de la portada'), page, 'campo de portada');
        await expectFullyInsideViewportWidth(
          page.getByRole('complementary', { name: 'Vista previa de la portada' }),
          page,
          'vista previa',
        );
        await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Crear película' }), page, '«Crear película»');
        await expectNoHorizontalScroll(page);
      });

      test('los géneros, también con el renombrado en línea abierto', async ({ page }) => {
        // Abierto, la fila lleva el campo y dos botones a la vez: es su momento más ancho.
        await page.goto('/admin/generos');
        await expect(page.getByRole('list', { name: 'Géneros' })).toBeVisible();
        await page.getByRole('button', { name: 'Renombrar Drama' }).click();
        await expectFullyInsideViewportWidth(page.getByRole('textbox', { name: 'Nuevo nombre para Drama' }), page, 'campo de renombrar');
        await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Guardar' }), page, '«Guardar»');
        await expectNoHorizontalScroll(page);
        await page.keyboard.press('Escape');
      });
    });
  });
}

/**
 * Pantallas anchas: la página de un título no se queda pegada a la izquierda.
 *
 * Protege contra el defecto que vio el autor (2026-10-14): a 1280 px y más, el
 * póster y el texto ocupaban una franja a la izquierda y el resto de la
 * pantalla quedaba vacío. Ahora la cabecera y el cuerpo comparten un contenedor
 * centrado con ancho máximo, así que el hueco a la izquierda del póster (inicio
 * del contenedor) es igual al que queda a la derecha de la ficha (su final).
 */
async function expectCenteredDetail(page: Page, header: Locator): Promise<void> {
  // El banner va a sangre (de borde a borde del área de la página, sin la barra de desplazamiento): es la referencia.
  const bleed = (await header.boundingBox())!;
  const poster = (await header.locator('img').last().boundingBox())!;
  const facts = (await page.getByRole('region', { name: 'Ficha' }).boundingBox())!;
  const left = poster.x - bleed.x;
  const right = bleed.x + bleed.width - (facts.x + facts.width);
  expect(Math.abs(left - right), `contenido descentrado: ${left}px a la izquierda y ${right}px a la derecha`).toBeLessThanOrEqual(2);
  // Y de verdad aprovecha el ancho: no es una columna estrecha centrada.
  expect(facts.x + facts.width - poster.x, 'el contenido ocupa demasiado poco ancho').toBeGreaterThanOrEqual(1000);
}

for (const viewport of [
  { name: '1440x900', width: 1440, height: 900 },
  { name: '1920x1080', width: 1920, height: 1080 },
] as const) {
  test.describe(`Pantalla ancha ${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    test('la página de una película y la de una serie van centradas y sin scroll horizontal', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/');
      await page.getByRole('region', { name: HERO_TITLE }).getByRole('link', { name: /^Más información/ }).click();
      await expect(page.getByRole('heading', { level: 1, name: HERO_TITLE })).toBeVisible();
      await expectCenteredDetail(page, page.getByRole('region', { name: HERO_TITLE }));
      await expectNoHorizontalScroll(page);

      await page.goto('/series');
      await page.getByRole('region', { name: SERIES_HERO.title }).getByRole('link', { name: /^Más información/ }).click();
      await expect(page.getByRole('heading', { level: 1, name: SERIES_HERO.title })).toBeVisible();
      await expectCenteredDetail(page, page.getByRole('region', { name: SERIES_HERO.title }));
      await expectNoHorizontalScroll(page);
    });
  });
}
