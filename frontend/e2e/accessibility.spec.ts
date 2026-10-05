/**
 * E2E de teclado y accesibilidad (humo, sin librerías externas).
 *
 * Comprueba en un navegador real lo que los tests con jsdom no pueden: el orden
 * real de tabulación, el `<dialog>` modal nativo atrapando el foco, la
 * devolución del foco al cerrar y que el elemento enfocado no quede tapado por
 * la barra superior (WCAG 2.4.11). Además hace una revisión estructural básica de
 * cada pantalla (idioma, un solo `h1`, `id` únicos, imágenes con `alt`, controles
 * con nombre accesible).
 *
 * NO sustituye a una auditoría con axe-core (contraste, ARIA avanzada...):
 * ver el informe de la tarea 24 para la propuesta.
 */
import type { Locator, Page } from '@playwright/test';
import { loginAdmin } from './support/api';
import { expect, movieCard, test, waitForMovieForm } from './support/fixtures';

test.describe('Teclado', () => {
  test('el primer Tab muestra «Saltar al contenido» y Intro lleva el foco al contenido principal', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    // Espera al catálogo: durante la carga `<main>` no tiene controles y el Tab final no tendría adónde ir.
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();

    await page.keyboard.press('Tab');
    const skip = page.getByRole('link', { name: 'Saltar al contenido' });
    await expect(skip).toBeFocused();
    // Al enfocarse deja de estar oculto: tiene un tamaño real, no 1 px recortado.
    const box = await skip.boundingBox();
    expect(box?.width ?? 0).toBeGreaterThan(100);
    expect(box?.height ?? 0).toBeGreaterThan(20);

    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();
    // El hash no cambia: en una SPA eso podría interferir con el enrutador.
    expect(new URL(page.url()).hash).toBe('');

    // Tras saltar, el siguiente Tab continúa DENTRO del contenido, no desde el logo.
    await page.keyboard.press('Tab');
    const insideMain = await page.evaluate(() => document.getElementById('contenido')?.contains(document.activeElement));
    expect(insideMain).toBe(true);
  });

  test('un modal atrapa el foco, Escape lo cierra y el foco vuelve a la tarjeta que lo abrió', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');

    const card = movieCard(page.getByRole('region', { name: 'Novedades' }), 'Interstellar');
    await card.focus();
    await page.keyboard.press('Enter');

    const dialog = page.getByRole('dialog', { name: 'Interstellar' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    // El foco entra en el diálogo, en el botón de cerrar.
    await expect(dialog.getByRole('button', { name: /^Cerrar detalles/ })).toBeFocused();

    // Tabulando (hacia delante y hacia atrás) el foco NUNCA llega a un elemento de la página de detrás.
    // En Chromium un <dialog> modal deja el resto de la página inerte, pero al pasar del último
    // control el foco puede salir al navegador (barra de direcciones): entonces `activeElement`
    // es `<body>`, y es el comportamiento nativo y válido (no se queda "atrapado" como en una trampa).
    for (const key of ['Tab', 'Shift+Tab']) {
      for (let step = 0; step < 8; step += 1) {
        await page.keyboard.press(key);
        expect(await focusedOutsideDialog(page), `${key} ${step + 1}: el foco llegó a la página de detrás del modal`).toBeNull();
      }
    }

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(card).toBeFocused();
  });

  test('el menú de usuario se maneja con teclado: Intro abre, Escape cierra y devuelve el foco', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');

    const toggle = page.getByRole('button', { name: 'Menú de usuario' });
    await toggle.focus();
    await page.keyboard.press('Enter');
    await expect(toggle).toHaveAttribute('aria-expanded', 'true');
    const logout = page.getByRole('button', { name: 'Cerrar sesión' });
    await expect(logout).toBeVisible();

    await page.keyboard.press('Tab');
    await expect(logout).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(logout).toBeHidden();
    await expect(toggle).toBeFocused();
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  test('«Películas» y «Series» son texto reservado: no entran en el orden de tabulación', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    const nav = page.getByRole('navigation', { name: 'Principal' });

    // Recorre la navegación con Tab: solo se detiene en «Inicio» y «Mi lista».
    await nav.getByRole('link', { name: 'Inicio' }).focus();
    await page.keyboard.press('Tab');
    await expect(nav.getByRole('link', { name: 'Mi lista' })).toBeFocused();
    await expect(nav.getByRole('link')).toHaveCount(2);
  });

  test('para un administrador, «Administrar» es la siguiente parada de Tab tras «Mi lista»', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/');
    const nav = page.getByRole('navigation', { name: 'Principal' });
    // Aparece cuando el servidor confirma el rol (no antes): se espera a él.
    const admin = nav.getByRole('link', { name: 'Administrar' });
    await expect(admin).toBeVisible();

    await nav.getByRole('link', { name: 'Mi lista' }).focus();
    await page.keyboard.press('Tab');
    await expect(admin).toBeFocused();
    await expect(nav.getByRole('link')).toHaveCount(3);
  });

  test('géneros del panel: Escape cancela el renombrado y devuelve el foco a «Renombrar X»', async ({
    page,
    request,
    signIn,
  }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/admin/generos');

    const rename = page.getByRole('button', { name: 'Renombrar Drama' });
    await rename.focus();
    await page.keyboard.press('Enter');
    const input = page.getByRole('textbox', { name: 'Nuevo nombre para Drama' });
    await expect(input).toBeFocused();
    await page.keyboard.type('Algo distinto');
    await page.keyboard.press('Escape');

    await expect(input).toBeHidden();
    await expect(rename).toBeFocused();
  });
});

/**
 * WCAG 2.2 · 2.4.11 (Foco no tapado): la barra superior está pegada arriba (`sticky`) y, al
 * desplazar la página hasta el elemento enfocado, el navegador solo comprueba que quepa en la
 * ventana, no que no lo tape la barra. El arreglo (`scroll-margin-top` en el contenido con la
 * altura medida de la barra, ver `index.css`) se prueba a 375 px (barra de tres filas, 165 px) y a
 * 1280 px (una fila, 57 px). 1280x800 es el tamaño de la captura en que se vio el fallo: el campo
 * «Título» quedaba justo debajo de la barra.
 */
test.describe('El foco no queda tapado por la barra superior (WCAG 2.2 · 2.4.11)', () => {
  const FOCUS_VIEWPORTS = [
    { width: 375, height: 812 },
    { width: 1280, height: 800 },
  ];

  for (const viewport of FOCUS_VIEWPORTS) {
    test.describe(`a ${viewport.width}x${viewport.height}`, () => {
      test.use({ viewport });

      test('formulario de película: el primer campo con error y el control anterior (Shift+Tab) quedan a la vista', async ({
        page,
        request,
        signIn,
      }) => {
        await signIn({ token: await loginAdmin(request) });
        await page.goto('/admin/peliculas/nueva');
        await waitForMovieForm(page);
        const title = page.getByLabel('Título', { exact: true });

        // Enviar vacío: el foco salta a «Título», que está más arriba, y el navegador desplaza la página.
        await page.getByRole('button', { name: 'Crear película' }).click();
        await expect(title).toBeFocused();
        await expectBelowNavbar(page, title, 'campo «Título»');
        // El margen extra (2rem) deja ver también la etiqueta del campo, no solo el campo.
        await expectBelowNavbar(page, page.getByText('Título', { exact: true }), 'etiqueta «Título»');

        // Tab hacia atrás: el control anterior está DENTRO de la ventana pero tapado por la barra.
        // Sin el arreglo el navegador no desplaza nada (para él ya es visible) y el foco queda oculto.
        const back = page.getByRole('link', { name: 'Volver al listado' });
        const backBox = await back.boundingBox();
        await page.evaluate((dy) => window.scrollBy(0, dy), backBox!.y - 8);
        const hiddenBox = await back.boundingBox();
        const navbarBox = await page.getByRole('banner').boundingBox();
        expect(hiddenBox!.y + hiddenBox!.height, 'precondición: el enlace está debajo de la barra').toBeLessThanOrEqual(
          navbarBox!.y + navbarBox!.height,
        );
        await expect(title).toBeFocused();

        await page.keyboard.press('Shift+Tab');
        await expect(back).toBeFocused();
        await expectBelowNavbar(page, back, '«Volver al listado»');
      });

      test('enfocar los controles de la propia barra no desplaza la página', async ({ page, request, signIn }) => {
        // Protege la elección de `scroll-margin-top` en el contenido frente a `scroll-padding-top` en
        // <html>: con esa otra receta, enfocar el buscador o el menú desde abajo subía la página entera.
        await signIn({ token: await loginAdmin(request) });
        await page.goto('/admin/peliculas/nueva');
        await waitForMovieForm(page);

        await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
        const scrolled = await page.evaluate(() => window.scrollY);
        expect(scrolled, 'precondición: la página está desplazada').toBeGreaterThan(0);

        await page.getByRole('combobox', { name: 'Buscar películas por título' }).focus();
        await page.keyboard.press('Tab');
        await expect(page.getByRole('button', { name: 'Menú de usuario' })).toBeFocused();
        expect(await page.evaluate(() => window.scrollY)).toBe(scrolled);
      });
    });
  }
});

test.describe('Estructura accesible del panel de administración (humo)', () => {
  const ADMIN_PAGES = [
    { path: '/admin/peliculas', ready: (page: Page) => page.getByRole('table', { name: /^Películas del catálogo/ }) },
    { path: '/admin/peliculas/nueva', ready: (page: Page) => page.getByRole('checkbox', { name: 'Drama' }) },
    { path: '/admin/generos', ready: (page: Page) => page.getByRole('list', { name: 'Géneros' }) },
  ];

  for (const { path, ready } of ADMIN_PAGES) {
    test(`${path}: estructura básica correcta`, async ({ page, request, signIn }) => {
      await signIn({ token: await loginAdmin(request) });
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1, name: 'Administración' })).toBeVisible();
      await expect(ready(page)).toBeVisible();
      expect(await auditPage(page)).toEqual([]);
    });
  }

  test('el formulario con todos los errores a la vista sigue siendo correcto', async ({ page, request, signIn }) => {
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/admin/peliculas/nueva');
    await waitForMovieForm(page);
    await page.getByRole('button', { name: 'Crear película' }).click();
    await expect(page.getByLabel('Título', { exact: true })).toBeFocused();
    expect(await auditPage(page)).toEqual([]);
  });
});

test.describe('Estructura accesible (humo)', () => {
  const PUBLIC_PAGES = ['/login', '/registro'];
  const PRIVATE_PAGES = ['/', '/favorites'];

  for (const path of PUBLIC_PAGES) {
    test(`${path}: estructura básica correcta`, async ({ page }) => {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      expect(await auditPage(page)).toEqual([]);
    });
  }

  for (const path of PRIVATE_PAGES) {
    test(`${path}: estructura básica correcta`, async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeAttached();
      // Espera a que haya contenido real (no la pantalla de carga).
      await (path === '/'
        ? expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible()
        : expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible());
      expect(await auditPage(page)).toEqual([]);
    });
  }

  test('con un modal abierto la estructura sigue siendo correcta', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), 'Interstellar').click();
    await expect(page.getByRole('dialog', { name: 'Interstellar' })).toBeVisible();
    expect(await auditPage(page)).toEqual([]);
  });
});

/**
 * Comprueba que `target` se ve entero entre el borde inferior de la barra superior (el `banner`,
 * pegado arriba) y el borde inferior de la ventana: ni tapado por la barra ni fuera de la pantalla.
 */
async function expectBelowNavbar(page: Page, target: Locator, label: string): Promise<void> {
  const navbar = await page.getByRole('banner').boundingBox();
  const box = await target.boundingBox();
  const viewportHeight = page.viewportSize()?.height ?? 0;
  expect(navbar, 'la barra superior no tiene caja').not.toBeNull();
  expect(box, `${label} no tiene caja`).not.toBeNull();
  expect(box!.y, `${label} queda tapado por la barra`).toBeGreaterThanOrEqual(navbar!.y + navbar!.height - 0.5);
  expect(box!.y + box!.height, `${label} queda por debajo de la ventana`).toBeLessThanOrEqual(viewportHeight + 0.5);
}

/**
 * Si el foco está en un elemento de la página que NO pertenece al `<dialog>` abierto,
 * devuelve su descripción; si está dentro del diálogo (o fuera del documento, `<body>`), `null`.
 */
function focusedOutsideDialog(page: Page): Promise<string | null> {
  return page.evaluate(() => {
    const active = document.activeElement;
    if (!active || active === document.body) return null;
    if (document.querySelector('dialog[open]')?.contains(active)) return null;
    return `<${active.tagName.toLowerCase()}> «${(active.textContent ?? '').trim().slice(0, 40)}»`;
  });
}

/**
 * Revisión estructural mínima de la página actual. Devuelve la lista de problemas
 * encontrados (vacía = correcta). Se ejecuta DENTRO del navegador.
 *
 * Es una aproximación deliberadamente simple del cálculo de nombre accesible:
 * `aria-label`, `aria-labelledby`, `<label>`, texto visible o `title`.
 */
function auditPage(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const problems: string[] = [];
    const describe = (el: Element) => `<${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}> «${(el.textContent ?? '').trim().slice(0, 40)}»`;

    if (!document.documentElement.lang) problems.push('<html> sin atributo lang');
    if (!document.title.trim()) problems.push('Página sin <title>');
    // Tras abrir un modal la página sigue teniendo un único h1; los diálogos usan h2.
    const h1 = document.querySelectorAll('h1').length;
    if (h1 !== 1) problems.push(`Debe haber un único <h1> y hay ${h1}`);
    const mains = document.querySelectorAll('main').length;
    if (mains !== 1) problems.push(`Debe haber un único <main> y hay ${mains}`);

    const ids = new Map<string, number>();
    document.querySelectorAll('[id]').forEach((el) => ids.set(el.id, (ids.get(el.id) ?? 0) + 1));
    ids.forEach((count, id) => {
      if (count > 1) problems.push(`id duplicado: ${id}`);
    });

    document.querySelectorAll('img').forEach((img) => {
      if (!img.hasAttribute('alt')) problems.push(`Imagen sin atributo alt: ${img.src}`);
    });

    const accessibleName = (el: Element): string => {
      const labelledBy = el.getAttribute('aria-labelledby');
      if (labelledBy) {
        return labelledBy
          .split(/\s+/)
          .map((id) => document.getElementById(id)?.textContent ?? '')
          .join(' ')
          .trim();
      }
      const aria = el.getAttribute('aria-label')?.trim();
      if (aria) return aria;
      if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement || el instanceof HTMLTextAreaElement) {
        const fromLabels = Array.from(el.labels ?? [])
          .map((label) => label.textContent ?? '')
          .join(' ')
          .trim();
        if (fromLabels) return fromLabels;
      }
      return ((el as HTMLElement).innerText || el.textContent || el.getAttribute('title') || '').trim();
    };

    document
      .querySelectorAll('button, a[href], input:not([type="hidden"]), select, textarea, [role="button"], [role="combobox"]')
      .forEach((el) => {
        if (!accessibleName(el)) problems.push(`Control sin nombre accesible: ${describe(el)}`);
      });

    return problems;
  });
}
