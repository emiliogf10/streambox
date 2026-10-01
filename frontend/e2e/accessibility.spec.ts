/**
 * E2E de teclado y accesibilidad (humo, sin librerías externas).
 *
 * Comprueba en un navegador real lo que los tests con jsdom no pueden: el orden
 * real de tabulación, el `<dialog>` modal nativo atrapando el foco y la
 * devolución del foco al cerrar. Además hace una revisión estructural básica de
 * cada pantalla (idioma, un solo `h1`, `id` únicos, imágenes con `alt`, controles
 * con nombre accesible).
 *
 * NO sustituye a una auditoría con axe-core (contraste, ARIA avanzada...):
 * ver el informe de la tarea 24 para la propuesta.
 */
import type { Page } from '@playwright/test';
import { expect, movieCard, test } from './support/fixtures';

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
