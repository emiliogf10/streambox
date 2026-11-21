/**
 * Tests de la pestaña de géneros del panel (`AdminGenresPage`).
 *
 * Protegen el alta (con el nombre normalizado que devuelve el servidor), el
 * renombrado en línea con su gestión del foco (Escape cancela y devuelve el
 * foco), el borrado con confirmación y los dos 409 distintos: nombre repetido
 * (`GENRE_ALREADY_EXISTS`, junto al campo) y género en uso (`GENRE_IN_USE`, en
 * la fila y sin quitarlo). Y, desde D-4, el uso de cada género (`GET
 * /api/admin/genres`): un género en uso no ofrece «Borrar» y dice por qué.
 * `fetch` está simulado; sesión, avisos y router son los reales.
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../../lib/queryKeys';
import type { AdminGenre } from '../../lib/types';
import { errorResponse, jsonResponse, noContentResponse, renderWithProviders, routeFetch } from '../../test/helpers';
import { createTestQueryClient } from '../../test/queryClient';
import { watchToastInsertions } from '../../test/toastInsertions';
import { AdminGenresPage } from './AdminGenresPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Ruta de la lista del panel, con el uso de cada género. */
const ADMIN_GENRES = 'GET /api/admin/genres';

/**
 * Géneros tal como los devuelve la API del panel (sin orden garantizado en el test). Ninguno en uso salvo
 * que el test lo diga: así las pruebas de borrado pueden abrir la confirmación.
 */
const GENRES: AdminGenre[] = [
  { id: 2, name: 'Drama', movieCount: 0, seriesCount: 0 },
  { id: 1, name: 'Ciencia ficción', movieCount: 0, seriesCount: 0 },
  { id: 3, name: 'Acción', movieCount: 0, seriesCount: 0 },
];

function renderGenres(routes: Parameters<typeof routeFetch>[1] = {}) {
  routeFetch(fetchMock, { [ADMIN_GENRES]: () => jsonResponse(GENRES), ...routes });
  const user = userEvent.setup();
  renderWithProviders(<AdminGenresPage />, { route: '/admin/generos', session: true });
  return user;
}

const list = () => screen.getByRole('list', { name: 'Géneros' });
/** Nombre de cada fila: el primer texto de la fila (debajo o al lado va su uso). */
const names = () => within(list()).getAllByRole('listitem').map((item) => item.querySelector('span')?.textContent);
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
    renderGenres({ [ADMIN_GENRES]: () => jsonResponse([]) });

    expect(await screen.findByRole('heading', { name: 'Todavía no hay géneros' })).toBeInTheDocument();
    // Las series también los necesitan: el texto no puede hablar solo de películas.
    expect(
      screen.getByText('Crea el primero con el formulario de arriba. Cada película o serie necesita al menos un género.'),
    ).toBeInTheDocument();
    expect(newNameInput()).toBeInTheDocument();
  });

  it('si falla la carga, muestra el error y «Reintentar» la repite', async () => {
    const user = renderGenres({ [ADMIN_GENRES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar los géneros' })).toBeInTheDocument();
    routeFetch(fetchMock, { [ADMIN_GENRES]: () => jsonResponse(GENRES) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('list', { name: 'Géneros' })).toBeInTheDocument();
  });
});

describe('AdminGenresPage: uso de cada género (D-4)', () => {
  const IN_USE: AdminGenre[] = [
    { id: 2, name: 'Drama', movieCount: 12, seriesCount: 5 },
    { id: 4, name: 'Western', movieCount: 1, seriesCount: 0 },
    { id: 3, name: 'Acción', movieCount: 0, seriesCount: 0 },
  ];

  it('cada fila dice cuántas películas y series lo usan, y la cabecera explica la regla', async () => {
    renderGenres({ [ADMIN_GENRES]: () => jsonResponse(IN_USE) });
    await screen.findByRole('list', { name: 'Géneros' });

    const rows = within(list()).getAllByRole('listitem');
    expect(rows[0]).toHaveTextContent('Sin películas ni series');
    expect(rows[1]).toHaveTextContent('12 películas y 5 series');
    expect(rows[2]).toHaveTextContent('1 película');
    expect(screen.getByText('Solo se pueden borrar los géneros que no usa ninguna película ni serie.')).toBeInTheDocument();
  });

  it('un género en uso: «Borrar» no disponible (sin soltar el foco), con el porqué, y no abre la confirmación', async () => {
    const user = renderGenres({ [ADMIN_GENRES]: () => jsonResponse(IN_USE) });
    await screen.findByRole('list', { name: 'Géneros' });

    const drama = screen.getByRole('button', { name: 'Borrar Drama' });
    expect(drama).toHaveAttribute('aria-disabled', 'true');
    expect(drama).not.toBeDisabled(); // sigue en el orden de tabulación
    expect(drama).toHaveAccessibleDescription(
      'No se puede borrar: lo usan 12 películas y 5 series. Quítalo de esos títulos para poder borrarlo.',
    );
    expect(screen.getByRole('button', { name: 'Borrar Western' })).toHaveAccessibleDescription(
      'No se puede borrar: lo usa 1 película. Quítalo de ese título para poder borrarlo.',
    );

    drama.focus();
    await user.keyboard('{Enter}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(drama).toHaveFocus();
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false);

    // El que no usa nadie sí se puede borrar.
    const accion = screen.getByRole('button', { name: 'Borrar Acción' });
    expect(accion).not.toHaveAttribute('aria-disabled');
    await user.click(accion);
    expect(screen.getByRole('alertdialog', { name: '¿Borrar el género «Acción»?' })).toBeInTheDocument();
  });

  it('un género recién creado sale sin uso y la lista completa de los formularios queda anticuada', async () => {
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.genres.all, [{ id: 1, name: 'Vieja' }]);
    routeFetch(fetchMock, {
      [ADMIN_GENRES]: () => jsonResponse(IN_USE),
      'POST /api/genres': () => jsonResponse({ id: 9, name: 'Terror' }, 201),
    });
    const user = userEvent.setup();
    renderWithProviders(<AdminGenresPage />, { route: '/admin/generos', session: true, queryClient });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.type(newNameInput(), 'Terror');
    await user.click(screen.getByRole('button', { name: 'Añadir' }));

    expect(await screen.findByText('Género «Terror» creado.')).toBeInTheDocument();
    const row = within(list()).getAllByRole('listitem').find((item) => item.textContent?.startsWith('Terror'));
    expect(row).toHaveTextContent('Sin películas ni series');
    expect(screen.getByRole('button', { name: 'Borrar Terror' })).not.toHaveAttribute('aria-disabled');
    await waitFor(() => expect(queryClient.getQueryState(queryKeys.genres.all)?.isInvalidated).toBe(true));
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
      'POST /api/genres': () => errorResponse(409, 'GENRE_ALREADY_EXISTS', 'Ya existe un género con el nombre «Drama»'),
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.type(newNameInput(), 'drama');
    await user.click(screen.getByRole('button', { name: 'Añadir' }));

    // Sin región viva propia (varias se pisarían): se oye al llegar el foco al campo, que lo tiene de descripción.
    expect(await screen.findByText('Ya existe un género con el nombre «Drama»')).not.toHaveAttribute('role');
    expect(newNameInput()).toHaveAttribute('aria-invalid', 'true');
    expect(newNameInput()).toHaveAccessibleDescription('Ya existe un género con el nombre «Drama»');
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
        errorResponse(409, 'GENRE_ALREADY_EXISTS', 'Ya existe un género con el nombre «Acción»'),
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));
    await user.keyboard('acción');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    const input = screen.getByRole('textbox', { name: 'Nuevo nombre para Drama' });
    // Sin región viva propia (varias se pisarían): se oye al llegar el foco al campo, que lo tiene de descripción.
    expect(await screen.findByText('Ya existe un género con el nombre «Acción»')).not.toHaveAttribute('role');
    expect(input).toHaveAccessibleDescription('Ya existe un género con el nombre «Acción»');
    expect(input).toHaveFocus();
    expect(names()).toHaveLength(3);
  });

  it('404: el género ya no existía; se informa y se recarga la lista', async () => {
    let gone = false;
    const user = renderGenres({
      [ADMIN_GENRES]: () => jsonResponse(gone ? GENRES.filter((genre) => genre.id !== 2) : GENRES),
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
    // Solo se abre para un género sin uso: lo dice (ni películas NI series).
    expect(dialog).toHaveTextContent('No lo usa ninguna película ni serie. Esta acción no se puede deshacer.');
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: 'Sí, borrar género' }));

    await expectToastOnPage('Género «Acción» borrado.');
    expect(names()).toEqual(['Ciencia ficción', 'Drama']);
    expect(screen.getByRole('heading', { level: 2, name: 'Géneros' })).toHaveFocus();
  });

  it('409 GENRE_IN_USE (le asignaron títulos entre medias): el mensaje se queda EN LA FILA, no se quita y se ponen al día los recuentos', async () => {
    const message =
      'No se puede eliminar el género «Drama»: lo usan 3 películas. Quítalo de esas películas antes de borrarlo.';
    let assigned = false;
    const user = renderGenres({
      [ADMIN_GENRES]: () =>
        jsonResponse(GENRES.map((genre) => (assigned && genre.id === 2 ? { ...genre, movieCount: 3 } : genre))),
      'DELETE /api/genres/2': () => {
        assigned = true;
        return errorResponse(409, 'GENRE_IN_USE', message);
      },
    });
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
    // Con los recuentos nuevos, «Borrar» pasa a no disponible y la fila dice cuántos lo usan.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Borrar Drama' })).toHaveAttribute('aria-disabled', 'true'));
    expect(notice.closest('li')).toHaveTextContent('3 películas');
    expect(screen.getByRole('button', { name: 'Borrar Drama' })).toHaveFocus();

    // La siguiente acción retira el aviso.
    await user.click(screen.getByRole('button', { name: 'Renombrar Acción' }));
    expect(screen.queryByText(message)).not.toBeInTheDocument();
  });

  it('404: ya estaba borrado; se informa y se recarga la lista', async () => {
    let gone = false;
    const user = renderGenres({
      [ADMIN_GENRES]: () => jsonResponse(gone ? GENRES.filter((genre) => genre.id !== 3) : GENRES),
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

/**
 * Una lectura de `GET /api/genres` que salió ANTES de una escritura y responde
 * DESPUÉS no puede deshacerla en la lista: la escritura del panel gana siempre.
 */
describe('AdminGenresPage: lecturas en vuelo durante una escritura', () => {
  it('crear con la primera carga en vuelo: se descarta esa lectura, se repite y el género nuevo sale en la lista', async () => {
    const terror = { id: 9, name: 'Terror' };
    let answerStaleRead: (response: Response) => void = () => {};
    let reads = 0;
    const user = renderGenres({
      [ADMIN_GENRES]: () => {
        reads += 1;
        if (reads === 1) {
          return new Promise<Response>((resolve) => {
            answerStaleRead = resolve;
          });
        }
        return jsonResponse([...GENRES, { ...terror, movieCount: 0, seriesCount: 0 }]);
      },
      'POST /api/genres': () => jsonResponse(terror, 201),
    });
    await waitFor(() => expect(reads).toBe(1));
    expect(screen.getByText('Cargando géneros...')).toBeInTheDocument();

    await user.type(newNameInput(), 'Terror');
    await user.click(screen.getByRole('button', { name: 'Añadir' }));
    expect(await screen.findByText('Género «Terror» creado.')).toBeInTheDocument();

    answerStaleRead(jsonResponse(GENRES));

    await waitFor(() => expect(names()).toEqual(['Acción', 'Ciencia ficción', 'Drama', 'Terror']));
    expect(reads).toBe(2);
  });

  it('borrar con un refresco en vuelo: el género borrado no vuelve a la lista', async () => {
    const queryClient = createTestQueryClient();
    let answerStaleRead: (response: Response) => void = () => {};
    let reads = 0;
    routeFetch(fetchMock, {
      [ADMIN_GENRES]: () => {
        reads += 1;
        if (reads === 2) {
          return new Promise<Response>((resolve) => {
            answerStaleRead = resolve;
          });
        }
        return jsonResponse(GENRES);
      },
      'DELETE /api/genres/3': () => noContentResponse(),
    });
    const user = userEvent.setup();
    renderWithProviders(<AdminGenresPage />, { route: '/admin/generos', session: true, queryClient });
    await screen.findByRole('list', { name: 'Géneros' });

    // Un refresco en segundo plano, como el de volver a la pestaña con el dato anticuado.
    await act(async () => {
      void queryClient.refetchQueries({ queryKey: queryKeys.genres.admin });
      await Promise.resolve();
    });
    await waitFor(() => expect(reads).toBe(2));

    await user.click(screen.getByRole('button', { name: 'Borrar Acción' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar género' }));
    await expectToastOnPage('Género «Acción» borrado.');

    await act(async () => {
      answerStaleRead(jsonResponse(GENRES));
      await Promise.resolve();
    });

    await waitFor(() => expect(queryClient.getQueryState(queryKeys.genres.admin)?.fetchStatus).toBe('idle'));
    expect(names()).toEqual(['Ciencia ficción', 'Drama']);
  });
});

/**
 * 404 al renombrar o borrar: el género ya no existe. Además de la lista del
 * panel, hay que dar por anticuada la lista COMPLETA de los formularios de
 * película y serie (`genres.all`): si no, seguían ofreciendo el género borrado
 * hasta que caducara su caché (60 s).
 */
describe('AdminGenresPage: 404 deja anticuada la lista de los formularios', () => {
  function renderWithFullList(routes: Parameters<typeof routeFetch>[1]) {
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(queryKeys.genres.all, GENRES.map(({ id, name }) => ({ id, name })));
    routeFetch(fetchMock, { [ADMIN_GENRES]: () => jsonResponse(GENRES), ...routes });
    const user = userEvent.setup();
    renderWithProviders(<AdminGenresPage />, { route: '/admin/generos', session: true, queryClient });
    return { user, queryClient };
  }

  it('al renombrar', async () => {
    const { user, queryClient } = renderWithFullList({
      'PUT /api/genres/2': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Género no encontrado: 2'),
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Renombrar Drama' }));
    await user.keyboard('Nuevo');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    expect(await screen.findByText('El género «Drama» ya no existía. Se ha actualizado la lista.')).toBeInTheDocument();
    await waitFor(() => expect(queryClient.getQueryState(queryKeys.genres.all)?.isInvalidated).toBe(true));
  });

  it('al borrar', async () => {
    const { user, queryClient } = renderWithFullList({
      'DELETE /api/genres/3': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Género no encontrado: 3'),
    });
    await screen.findByRole('list', { name: 'Géneros' });

    await user.click(screen.getByRole('button', { name: 'Borrar Acción' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar género' }));

    await expectToastOnPage('El género «Acción» ya no existía. Se ha actualizado la lista.');
    await waitFor(() => expect(queryClient.getQueryState(queryKeys.genres.all)?.isInvalidated).toBe(true));
  });
});

/**
 * Los avisos del borrado se lanzan con la confirmación YA cerrada
 * (`useToastAfterDialog`): nacen una sola vez y en la página. Antes nacían
 * dentro del diálogo y se volvían a crear en la página un instante después (un
 * lector de pantalla podía leerlos dos veces y la cuenta atrás se reiniciaba).
 */
describe('AdminGenresPage: los avisos del borrado nacen en la página, una vez', () => {
  it.each([
    ['borrado', () => noContentResponse(), 'Género «Acción» borrado.'],
    [
      'ya no existía (404)',
      () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Género no encontrado: 3'),
      'El género «Acción» ya no existía. Se ha actualizado la lista.',
    ],
    ['error del servidor', () => errorResponse(500, 'INTERNAL_ERROR', 'boom'), 'El servidor ha tenido un problema'],
  ])('%s', async (_case, answer, text) => {
    const user = renderGenres({ 'DELETE /api/genres/3': answer });
    await screen.findByRole('list', { name: 'Géneros' });
    const toasts = watchToastInsertions(text);

    await user.click(screen.getByRole('button', { name: 'Borrar Acción' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar género' }));

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    await waitFor(() => expect(toasts.places()).toContain('page'));
    expect(toasts.places()).toEqual(['page']);
    toasts.stop();
  });
});
