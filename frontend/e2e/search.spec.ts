/**
 * E2E del buscador de la barra superior (combobox con lista, patrón ARIA APG).
 *
 * Los resultados salen de `GET /api/movies/search` del backend real; lo que se
 * comprueba es la integración: el texto filtra, el teclado funciona y Escape cierra.
 */
import { expect, test } from './support/fixtures';

test.describe('Buscador', () => {
  test.beforeEach(async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/');
  });

  test('escribir filtra con resultados reales de la API y anuncia cuántos hay', async ({ page }) => {
    const search = page.getByRole('combobox', { name: 'Buscar películas por título' });
    await search.fill('blade');

    const list = page.getByRole('listbox', { name: 'Resultados de la búsqueda' });
    await expect(list).toBeVisible();
    // «Blade Runner» y «Blade Runner 2049», ordenadas por título.
    const options = list.getByRole('option');
    await expect(options).toHaveCount(2);
    await expect(options.nth(0)).toContainText('Blade Runner');
    await expect(options.nth(1)).toContainText('Blade Runner 2049');
    await expect(search).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByRole('status').filter({ hasText: '2 resultados' })).toHaveCount(1);
  });

  test('sin coincidencias muestra "Sin resultados" en lugar de quedarse mudo', async ({ page }) => {
    await page.getByRole('combobox', { name: 'Buscar películas por título' }).fill('zzzzzz-no-existe');
    await expect(page.getByText('Sin resultados para «zzzzzz-no-existe».')).toBeVisible();
    await expect(page.getByRole('listbox')).toHaveCount(0);
  });

  test('↓ resalta, ↑ vuelve atrás y Intro abre la película resaltada', async ({ page }) => {
    const search = page.getByRole('combobox', { name: 'Buscar películas por título' });
    await search.fill('blade');
    const options = page.getByRole('listbox').getByRole('option');
    await expect(options).toHaveCount(2);

    await search.press('ArrowDown');
    await expect(options.nth(0)).toHaveAttribute('aria-selected', 'true');
    await search.press('ArrowDown');
    await expect(options.nth(1)).toHaveAttribute('aria-selected', 'true');
    await search.press('ArrowUp');
    await expect(options.nth(0)).toHaveAttribute('aria-selected', 'true');
    await search.press('ArrowDown');

    // El foco real no sale del campo: la opción activa se indica con aria-activedescendant.
    await expect(search).toBeFocused();
    await expect(search).toHaveAttribute('aria-activedescendant', /.+/);

    await search.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Blade Runner 2049' });
    await expect(dialog).toBeVisible();
    // La lista se cierra y el campo se vacía tras elegir.
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(search).toHaveValue('');
  });

  test('Intro sin resaltar abre la primera coincidencia y un clic en una opción abre su película', async ({ page }) => {
    const search = page.getByRole('combobox', { name: 'Buscar películas por título' });
    await search.fill('dune');
    await expect(page.getByRole('option')).toHaveCount(1);
    await search.press('Enter');
    await expect(page.getByRole('dialog', { name: 'Dune: Parte Dos' })).toBeVisible();
    await page.getByRole('button', { name: /^Cerrar detalles/ }).click();

    await search.fill('interstellar');
    await page.getByRole('option', { name: /Interstellar/ }).click();
    await expect(page.getByRole('dialog', { name: 'Interstellar' })).toBeVisible();
  });

  test('Escape cierra la lista y vacía el campo', async ({ page }) => {
    const search = page.getByRole('combobox', { name: 'Buscar películas por título' });
    await search.fill('mad');
    await expect(page.getByRole('listbox')).toBeVisible();

    await search.press('Escape');
    await expect(page.getByRole('listbox')).toHaveCount(0);
    await expect(search).toHaveValue('');
    await expect(search).toHaveAttribute('aria-expanded', 'false');
  });
});
