/**
 * Revisión de QA de «Ver tráiler»: la rejilla de acciones de las cabeceras con
 * de UNA a CUATRO acciones, según lo que tenga el título (vídeo, tráiler) y el
 * rol (USER / ADMIN, que añade «Editar»).
 *
 * `FeaturedBanner` reparte las acciones en móvil de dos en dos y, si una no
 * cabe en media fila o se queda sola, la pone en su propia fila a todo el
 * ancho (fila flexible con `grow` y `basis` de media fila; ningún texto se
 * parte, `min-width: max-content`). Antes cada página fijaba a mano qué
 * botón ocupaba dos columnas; ahora el número de botones varía y un error de
 * esa regla dejaría un botón a media anchura con un hueco al lado, dos
 * botones que no caben o un texto partido en dos líneas. Los tests de Vitest no lo ven (jsdom no maqueta), por
 * eso va aquí, con el navegador real:
 *
 *  - **375 px** (y 320 en la portada): ninguna fila con más de dos acciones;
 *    una acción sola en su fila ocupa todo el ancho; dos en una fila, la mitad
 *    cada una; nada se sale.
 *  - **Todos los anchos**: cada acción en una sola línea (alto de un botón).
 *  - **768, 1280 y 1920 px**: nada se sale de la pantalla y, desde 1280, todas
 *    las acciones caben en una sola fila.
 *
 * Casos (catálogo sembrado en `support/catalog.ts`):
 *  - Portada, «Dune: Parte Dos» (vídeo + tráiler), USER: 4 acciones.
 *  - «Tenet» (solo tráiler): USER 2 acciones; ADMIN 3 («Editar» sola abajo).
 *  - «Noches de Invierno» (nada que ver), USER: 1 acción («Mi lista», a todo el ancho).
 *  - «Dune: Parte Dos», ADMIN, en su página: 4 acciones.
 *
 * Solo lee el catálogo (no pulsa «Mi lista»): va en el proyecto `chromium`.
 */
import type { Locator, Page } from '@playwright/test';
import { HERO_TITLE, MOVIES } from './support/catalog';
import { loginAdmin } from './support/api';
import { expect, movieCard, test } from './support/fixtures';

/** Anchos que exige el autor (móvil, tablet, portátil y escritorio). */
const WIDTHS = [375, 768, 1280, 1920] as const;

/**
 * Alto máximo de una acción en UNA línea: los botones miden 44 px (`min-h-11`);
 * con el texto partido en dos pasaban de 56 (incidencia QA trailer-7).
 */
const ONE_LINE_MAX_HEIGHT = 48;

/** Abre la página de una película del catálogo sembrado (filtrando por su año, para no paginar). */
async function openMovie(page: Page, title: string): Promise<void> {
  const movie = MOVIES.find((candidate) => candidate.title === title);
  if (!movie) throw new Error(`«${title}» no está en el catálogo sembrado`);
  await page.goto(`/peliculas?anio=${movie.releaseYear}`);
  await movieCard(page.getByRole('region', { name: 'Resultados' }), title).click();
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
}

/** Caja de cada acción (hijo directo del contenedor) y del propio contenedor, en píxeles. */
interface ActionsLayout {
  container: { left: number; right: number; width: number };
  items: { text: string; top: number; left: number; right: number; width: number; height: number }[];
}

/**
 * Contenedor de las acciones de la cabecera: el padre del botón «Mi lista»,
 * que está en todas las combinaciones.
 */
function actionsOf(header: Locator): Locator {
  return header.getByRole('button', { name: /^(Mi lista|En mi lista)/ }).locator('xpath=..');
}

async function measure(actions: Locator): Promise<ActionsLayout> {
  return actions.evaluate((element) => {
    const box = element.getBoundingClientRect();
    return {
      container: { left: box.left, right: box.right, width: box.width },
      items: Array.from(element.children).map((child) => {
        const rect = child.getBoundingClientRect();
        return {
          text: (child.textContent ?? '').trim(),
          top: Math.round(rect.top),
          left: rect.left,
          right: rect.right,
          width: rect.width,
          height: rect.height,
        };
      }),
    };
  });
}

/** Agrupa las acciones por fila (misma coordenada `top`). */
function rowsOf(layout: ActionsLayout): ActionsLayout['items'][] {
  const rows = new Map<number, ActionsLayout['items']>();
  for (const item of layout.items) rows.set(item.top, [...(rows.get(item.top) ?? []), item]);
  return [...rows.values()];
}

/**
 * Comprueba la maquetación de las acciones a un ancho dado y que estén las
 * esperadas, en ese orden (por el principio del texto visible).
 */
async function expectActionsLayout(page: Page, header: Locator, width: number, expected: RegExp[]): Promise<void> {
  const layout = await measure(actionsOf(header));
  expect(layout.items.map((item) => item.text), `acciones a ${width} px`).toHaveLength(expected.length);
  layout.items.forEach((item, index) => expect(item.text, `acción ${index + 1} a ${width} px`).toMatch(expected[index]));

  // Nada se sale de la pantalla ni del contenedor.
  for (const item of layout.items) {
    expect(item.left, `«${item.text}» a ${width} px`).toBeGreaterThanOrEqual(0);
    expect(item.right, `«${item.text}» a ${width} px`).toBeLessThanOrEqual(width);
    expect(item.right, `«${item.text}» dentro del contenedor a ${width} px`).toBeLessThanOrEqual(layout.container.right + 1);
  }
  // Ningún texto partido en dos líneas (antes, «Más información» a 375 px).
  for (const item of layout.items) {
    expect(item.height, `«${item.text}» en una sola línea a ${width} px`).toBeLessThanOrEqual(ONE_LINE_MAX_HEIGHT);
  }
  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
  expect(scrollWidth, `sin desplazamiento horizontal a ${width} px`).toBeLessThanOrEqual(width);

  const rows = rowsOf(layout);
  if (width < 640) {
    for (const row of rows) {
      expect(row.length, `filas de dos en dos a ${width} px`).toBeLessThanOrEqual(2);
      if (row.length === 1) {
        // Sola en su fila: a todo el ancho, sin hueco al lado.
        expect(row[0].width, `«${row[0].text}» sola en su fila`).toBeCloseTo(layout.container.width, 0);
      } else {
        expect(row[0].width, 'dos por fila, a partes iguales').toBeCloseTo(row[1].width, 0);
        expect(row[0].width).toBeLessThan(layout.container.width / 2);
      }
    }
  } else if (width >= 1280) {
    expect(rows, `todas en una fila a ${width} px`).toHaveLength(1);
  }
}

test.describe('Acciones de la cabecera con 1–4 botones (vídeo, tráiler y rol)', () => {
  test.beforeEach(async ({ page }) => {
    // Cada test recorre la misma página a cuatro anchos (y «Tenet», con dos roles): más
    // cargas de página que un test normal, así que se le da el triple de tiempo.
    test.slow();
    await page.route('https://www.youtube-nocookie.com/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Reproductor de prueba</title>' }),
    );
  });

  // A 375 px, «Más información» no cabe en media fila: baja a la suya, entera, en vez de partirse
  // (incidencia QA trailer-7). También a 320 px, el móvil más estrecho que se sigue viendo.
  test('portada, USER, vídeo y tráiler: 4 acciones, de dos en dos o enteras en móvil (sin partir textos) y en una fila en escritorio', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    for (const width of [320, ...WIDTHS]) {
      await page.setViewportSize({ width, height: 900 });
      await page.goto('/');
      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expect(banner.getByRole('button', { name: `Ver tráiler de ${HERO_TITLE}` })).toBeVisible();
      await expectActionsLayout(page, banner, width, [/^Ver ahora/, /^Ver tráiler/, /^(Mi lista|En mi lista)/, /^Más información/]);
    }
  });

  test('«Tenet» (solo tráiler): USER 2 acciones; ADMIN 3, con «Editar» a todo el ancho en móvil', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await signIn(user);
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 });
      await openMovie(page, 'Tenet');
      await expectActionsLayout(page, page.getByRole('region', { name: 'Tenet' }), width, [
        /^Ver tráiler/,
        /^(Mi lista|En mi lista)/,
      ]);
    }

    await signIn({ token: await loginAdmin(request) });
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 });
      await openMovie(page, 'Tenet');
      const header = page.getByRole('region', { name: 'Tenet' });
      await expect(header.getByRole('link', { name: 'Editar Tenet' })).toBeVisible();
      await expectActionsLayout(page, header, width, [/^Ver tráiler/, /^(Mi lista|En mi lista)/, /^Editar/]);
    }
  });

  test('«Noches de Invierno» (nada que ver), USER: «Mi lista» es la única acción y ocupa todo el ancho en móvil', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 });
      await openMovie(page, 'Noches de Invierno');
      const header = page.getByRole('region', { name: 'Noches de Invierno' });
      await expectActionsLayout(page, header, width, [/^(Mi lista|En mi lista)/]);
      // Y algo explica por qué no hay nada que ver (incidencia QA trailer-5), fuera de las acciones.
      await expect(header.getByText('Todavía no está disponible para ver.')).toBeVisible();
    }
  });

  test('«Dune: Parte Dos», ADMIN, en su página: 4 acciones (con «Editar») sin salirse a ningún ancho', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    for (const width of WIDTHS) {
      await page.setViewportSize({ width, height: 900 });
      await openMovie(page, HERO_TITLE);
      await expectActionsLayout(page, page.getByRole('region', { name: HERO_TITLE }), width, [
        /^Ver ahora/,
        /^Ver tráiler/,
        /^(Mi lista|En mi lista)/,
        /^Editar/,
      ]);
    }
  });
});
