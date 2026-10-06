/**
 * Tests de la pestaña de géneros del panel (`AdminGenresPage`).
 *
 * Protegen el alta (con el nombre normalizado que devuelve el servidor), el
 * renombrado en línea con su gestión del foco (Escape cancela y devuelve el
 * foco), el borrado con confirmación y los dos 409 distintos: nombre repetido
 * (`GENRE_ALREADY_EXISTS`, junto al campo) y género en uso (`GENRE_IN_USE`, en
 * la fila y sin quitarlo). `fetch` está simulado; sesión, avisos y router son
 * los reales.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Genre } from '../../lib/types';
import { errorResponse, jsonResponse, noContentResponse, renderWithProviders, routeFetch } from '../../test/helpers';
import { AdminGenresPage } from './AdminGenresPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Géneros tal como los devuelve la API (sin orden garantizado). */
const GENRES: Genre[] = [
  { id: 2, name: 'Drama' },
  { id: 1, name: 'Ciencia ficción' },
  { id: 3, name: 'Acción' },
];

function renderGenres(routes: Parameters<typeof routeFetch>[1] = {}) {
  routeFetch(fetchMock, { 'GET /api/genres': () => jsonResponse(GENRES), ...routes });
  const user = userEvent.setup();
  renderWithProviders(<AdminGenresPage />, { route: '/admin/generos', token: 'jwt' });
  return user;
}

const list = () => screen.getByRole('list', { name: 'Géneros' });
const names = () => within(list()).getAllByRole('listitem').map((item) => item.firstChild?.textContent);
const newNameInput = () => screen.getByLabelText('Nombre del nuevo género');

/**
 * Espera a que un aviso (toast) quede en la PÁGINA (si se lanzó con el diálogo de confirmación abierto, se
 * pinta primero dentro de él y vuelve a la página al cerrarse).
 */
async function expectToastOnPage(text: string) {
  await waitFor(() => expect(screen.getByText(text).closest('dialog')).toBeNull());
}

/** Cuerpo JSON de la petición `método url`. */
function sentBody(method: string, url: string): unknown {
  const call = fetchMock.mock.calls.find(([input, init]) => String(input) === url && init?.method === method);
  if (!call) throw new Error(`No se envió ${method} ${url}`);
  return JSON.parse(String(call[1]?.body));
}

describe('AdminGenresPage: listado', () => {
  it('lista los géneros ordenados por nombre, con su número y acciones con nombre propio', async () => {
    renderGenres();

    expect(screen.getByText('Cargando géneros...')).toBeInTheDocument();
    await screen.findByRole('list', { name: 'Géneros' });
    expect(document.title).toBe('Géneros · Administración — StreamBox');
    expect(names()).toEqual(['Acción', 'Ciencia ficción', 'Drama']);
    expect(screen.getByText('3 géneros')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Renombrar Drama' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Borrar Drama' })).toBeInTheDocument();
  });

  it('sin géneros: lo explica y deja el formulario de alta a mano', async () => {
    renderGenres({ 'GET /api/genres': () => jsonResponse([]) });

    expect(await screen.findByRole('heading', { name: 'Todavía no hay géneros' })).toBeInTheDocument();
    // Las series también los necesitan: el texto no puede hablar solo de películas.
    expect(
      screen.getByText('Crea el primero con el formulario de arriba. Cada película o serie necesita al menos un género.'),
    ).toBeInTheDocument();
    expect(newNameInput()).toBeInTheDocument();
  });

  it('si falla la carga, muestra el error y «Reintentar» la repite', async () => {
    const user = renderGenres({ 'GET /api/genres': () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar los géneros' })).toBeInTheDocument();
    routeFetch(fetchMock, { 'GET /api/genres': () => jsonResponse(GENRES) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('list', { name: 'Géneros' })).toBeInTheDocument();
  });
});

describe('AdminGenresPage: alta', () => {
  it('valida la longitud en el cliente (como el servidor, tras recortar) y no envía nada', async () => {
    const user = renderGenres();
    await screen.findByRole('list', { name: 'Géneros' });

    await user.type(newNameInput(), '  a  ');
    await user.click(screen.getByRole('button', { name: 'Añadir' }));

    expect(newNameInput()).toHaveAccessibleDescription('El nombre del género debe tener entre 2 y 50 caracteres');
    expect(newNameInput()).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false);
  });

  it('crea el género, muestra el nombre NORMALIZADO que devuelve el servidor en su sitio y vacía el campo', async () => {
    const user = renderGenres({ 'POST /api/genres': () => jsonResponse({ id: 9, name: 'Ciencia ficción retro' }, 201) });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.type(newNameInput(), '  ciencia FICCIÓN   retro ');
    await user.click(screen.getByRole('button', { name: 'Añadir' }));

    expect(await screen.findByText('Género «Ciencia ficción retro» creado.')).toBeInTheDocument();
    expect(sentBody('POST', '/api/genres')).toEqual({ name: 'ciencia FICCIÓN   retro' });
    expect(names()).toEqual(['Acción', 'Ciencia ficción', 'Ciencia ficción retro', 'Drama']);
    expect(newNameInput()).toHaveValue('');
    expect(screen.getByText('4 géneros')).toBeInTheDocument();
  });

  it('409 GENRE_ALREADY_EXISTS: el mensaje del servidor va junto al campo y no se añade nada', async () => {
    const user = renderGenres({
      'POST /api/genres': () => errorResponse(409, 'GENRE_ALREADY_EXISTS', 'Ya existe un género con el nombre "Drama"'),
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.type(newNameInput(), 'drama');
    await user.click(screen.getByRole('button', { name: 'Añadir' }));

    expect(await screen.findByText('Ya existe un género con el nombre "Drama"')).toHaveAttribute('role', 'alert');
    expect(newNameInput()).toHaveAttribute('aria-invalid', 'true');
    expect(newNameInput()).toHaveFocus();
    expect(newNameInput()).toHaveValue('drama');
    expect(names()).toHaveLength(3);
  });
});

describe('AdminGenresPage: renombrar en línea', () => {
  it('«Renombrar X» abre un campo con el nombre seleccionado; al guardar actualiza la lista y devuelve el foco', async () => {
    const user = renderGenres({ 'PUT /api/genres/2': () => jsonResponse({ id: 2, name: 'Melodrama' }) });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));

    const input = screen.getByRole('textbox', { name: 'Nuevo nombre para Drama' });
    expect(input).toHaveFocus();
    expect(input).toHaveValue('Drama');
    expect([input as HTMLInputElement].map((el) => [el.selectionStart, el.selectionEnd])).toEqual([[0, 5]]);

    await user.keyboard('melodrama');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByText('Género «Drama» renombrado a «Melodrama».')).toBeInTheDocument();
    expect(sentBody('PUT', '/api/genres/2')).toEqual({ name: 'melodrama' });
    expect(names()).toEqual(['Acción', 'Ciencia ficción', 'Melodrama']);
    expect(screen.queryByRole('textbox', { name: /Nuevo nombre/ })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Renombrar Melodrama' })).toHaveFocus();
  });

  it('Escape cancela sin enviar nada y devuelve el foco a «Renombrar X»', async () => {
    const user = renderGenres();
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));
    await user.keyboard('Otro nombre');
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('textbox', { name: /Nuevo nombre/ })).not.toBeInTheDocument();
    expect(names()).toContain('Drama');
    expect(screen.getByRole('button', { name: 'Renombrar Drama' })).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
  });

  it('«Cancelar» hace lo mismo que Escape', async () => {
    const user = renderGenres();
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Acción' }));
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));

    expect(screen.getByRole('button', { name: 'Renombrar Acción' })).toHaveFocus();
  });

  it('sin cambios no molesta al servidor', async () => {
    const user = renderGenres();
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    expect(screen.getByRole('button', { name: 'Renombrar Drama' })).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
  });

  it('409 GENRE_ALREADY_EXISTS: el error va junto al campo de esa fila y sigue editando', async () => {
    const user = renderGenres({
      'PUT /api/genres/2': () =>
        errorResponse(409, 'GENRE_ALREADY_EXISTS', 'Ya existe un género con el nombre "Acción"'),
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));
    await user.keyboard('acción');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    const input = screen.getByRole('textbox', { name: 'Nuevo nombre para Drama' });
    expect(await screen.findByText('Ya existe un género con el nombre "Acción"')).toHaveAttribute('role', 'alert');
    expect(input).toHaveAccessibleDescription('Ya existe un género con el nombre "Acción"');
    expect(input).toHaveFocus();
    expect(names()).toHaveLength(3);
  });

  it('404: el género ya no existía; se informa y se recarga la lista', async () => {
    let gone = false;
    const user = renderGenres({
      'GET /api/genres': () => jsonResponse(gone ? GENRES.filter((genre) => genre.id !== 2) : GENRES),
      'PUT /api/genres/2': () => {
        gone = true;
        return errorResponse(404, 'RESOURCE_NOT_FOUND', 'Género no encontrado: 2');
      },
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));
    await user.keyboard('Nuevo');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByText('El género «Drama» ya no existía. Se ha actualizado la lista.')).toBeInTheDocument();
    await waitFor(() => expect(names()).toEqual(['Acción', 'Ciencia ficción']));
  });
});

describe('AdminGenresPage: borrar', () => {
  it('pide confirmación; al confirmar lo quita, avisa y lleva el foco al título', async () => {
    const user = renderGenres({ 'DELETE /api/genres/3': () => noContentResponse() });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Borrar Acción' }));
    const dialog = screen.getByRole('alertdialog', { name: '¿Borrar el género «Acción»?' });
    // La regla real del servidor: ni películas NI series (antes solo nombraba las películas).
    expect(dialog).toHaveTextContent('Solo se puede borrar un género que no tenga ninguna película ni serie.');
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: 'Sí, borrar género' }));

    await expectToastOnPage('Género «Acción» borrado.');
    expect(names()).toEqual(['Ciencia ficción', 'Drama']);
    expect(screen.getByRole('heading', { level: 2, name: 'Géneros' })).toHaveFocus();
  });

  it('409 GENRE_IN_USE: el mensaje del servidor se queda EN LA FILA y el género no se quita', async () => {
    const message =
      'No se puede eliminar el género "Drama": lo usan 3 películas. Quítalo de esas películas antes de borrarlo.';
    const user = renderGenres({ 'DELETE /api/genres/2': () => errorResponse(409, 'GENRE_IN_USE', message) });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Borrar Drama' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar género' }));

    const notice = await screen.findByText(message);
    expect(notice).toHaveAttribute('role', 'alert');
    expect(notice.closest('li')).toHaveTextContent('Drama');
    expect(names()).toEqual(['Acción', 'Ciencia ficción', 'Drama']);
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    // El foco vuelve al botón que abrió la confirmación (sigue existiendo).
    expect(screen.getByRole('button', { name: 'Borrar Drama' })).toHaveFocus();

    // La siguiente acción retira el aviso.
    await user.click(screen.getByRole('button', { name: 'Renombrar Acción' }));
    expect(screen.queryByText(message)).not.toBeInTheDocument();
  });

  it('404: ya estaba borrado; se informa y se recarga la lista', async () => {
    let gone = false;
    const user = renderGenres({
      'GET /api/genres': () => jsonResponse(gone ? GENRES.filter((genre) => genre.id !== 3) : GENRES),
      'DELETE /api/genres/3': () => {
        gone = true;
        return errorResponse(404, 'RESOURCE_NOT_FOUND', 'Género no encontrado: 3');
      },
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Borrar Acción' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar género' }));

    await expectToastOnPage('El género «Acción» ya no existía. Se ha actualizado la lista.');
    await waitFor(() => expect(names()).toEqual(['Ciencia ficción', 'Drama']));
  });
});
