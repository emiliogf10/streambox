/**
 * Tests de `SearchBar`, el buscador con el patrón ARIA "combobox + listbox".
 *
 * Protegen: la búsqueda con retardo (una petición por pausa, no por letra) y
 * cancelable, los roles ARIA correctos (`combobox`, `listbox`, `option`,
 * `aria-activedescendant`), el manejo con teclado (↑/↓ circular, Intro, Escape)
 * con el foco SIEMPRE en el campo, y los estados "sin resultados" y "error".
 *
 * `fetch` está simulado y el retardo de la búsqueda usa temporizadores falsos
 * (ningún test espera tiempo real).
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { installManualTimers, passTime } from '../test/fakeTimers';
import { errorResponse, jsonResponse, makeMovie, makePage, renderWithProviders, routeFetch } from '../test/helpers';
import { SearchBar } from './SearchBar';

const fetchMock = vi.fn<typeof fetch>();

const matrix = makeMovie({ id: 1, title: 'Matrix', releaseYear: 1999 });
const matrixReloaded = makeMovie({ id: 2, title: 'Matrix Reloaded', releaseYear: 2003 });
const matrixRev = makeMovie({ id: 3, title: 'Matrix Revolutions', releaseYear: 2003 });

const SEARCH_URL = 'GET /api/movies/search?title=matrix&size=10&sort=title';

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  // Reloj falso manual (y el puente que necesita Testing Library): ver `test/fakeTimers.ts`.
  installManualTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

/** Monta el buscador (con la lista de favoritos, que necesita el modal de detalles). */
function setup(searchResponse: () => Response | Promise<Response> = () => jsonResponse(makePage([matrix, matrixReloaded, matrixRev]))) {
  routeFetch(fetchMock, {
    'GET /api/users/me/favorites': () => jsonResponse([]),
    [SEARCH_URL]: searchResponse,
  });
  // `delay: null`: user-event no usa temporizadores propios (estarían falseados).
  const user = userEvent.setup({ delay: null });
  renderWithProviders(
    <FavoritesProvider>
      <SearchBar />
    </FavoritesProvider>,
    { token: 'jwt' },
  );
  return { user, input: screen.getByRole('combobox', { name: 'Buscar películas por título' }) };
}

/** Región `aria-live` del propio buscador (la zona de avisos de la app también es un `status`). */
function announced() {
  return within(screen.getByRole('search')).getByRole('status');
}

/** Deja pasar el retardo de la búsqueda (300 ms). */
function passDebounce(ms = 300) {
  passTime(ms);
}

/** Escribe "matrix", espera el retardo y a que aparezca la lista de resultados. */
async function searchMatrix(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByRole('combobox'), 'matrix');
  passDebounce();
  return screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
}

describe('SearchBar: petición', () => {
  it('es un combobox con etiqueta, cerrado y sin lista al empezar', () => {
    const { input } = setup();

    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(input).toHaveAttribute('aria-autocomplete', 'list');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.getByRole('search')).toBeInTheDocument();
  });

  it('espera 300 ms tras la última tecla y entonces hace UNA sola petición con el texto completo', async () => {
    const { user } = setup();

    await user.type(screen.getByRole('combobox'), 'matrix');
    passDebounce(299);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/search'))).toHaveLength(0);

    passDebounce(1);
    await screen.findByRole('listbox');
    const searches = fetchMock.mock.calls.filter(([url]) => String(url).includes('/search'));
    expect(searches).toHaveLength(1);
    expect(String(searches[0][0])).toBe('/api/movies/search?title=matrix&size=10&sort=title');
  });

  it('un texto de solo espacios no busca nada', async () => {
    const { user } = setup();

    await user.type(screen.getByRole('combobox'), '   ');
    passDebounce(1000);

    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/search'))).toHaveLength(0);
  });

  it('seguir escribiendo cancela la petición anterior para que una respuesta lenta no pise a la nueva', async () => {
    const { user } = setup();
    // Ambas búsquedas se quedan sin respuesta: solo importa si se cancelan.
    routeFetch(fetchMock, {
      'GET /api/users/me/favorites': () => jsonResponse([]),
      'GET /api/movies/search?title=m&size=10&sort=title': () => new Promise<Response>(() => {}),
      'GET /api/movies/search?title=ma&size=10&sort=title': () => new Promise<Response>(() => {}),
    });

    await user.type(screen.getByRole('combobox'), 'm');
    passDebounce();
    const firstSignal = fetchMock.mock.calls.find(([url]) => String(url).includes('title=m&'))?.[1]?.signal;
    expect(firstSignal?.aborted).toBe(false);

    await user.type(screen.getByRole('combobox'), 'a');

    expect(firstSignal?.aborted).toBe(true);
  });
});

describe('SearchBar: resultados y roles ARIA', () => {
  it('los resultados forman un listbox de options y el combobox queda expandido', async () => {
    const { user, input } = setup();

    const list = await searchMatrix(user);

    expect(input).toHaveAttribute('aria-expanded', 'true');
    expect(input).toHaveAttribute('aria-controls', list.id);
    const options = within(list).getAllByRole('option');
    expect(options).toHaveLength(3);
    expect(options[0]).toHaveTextContent('Matrix1999');
    expect(options[1]).toHaveTextContent('Matrix Reloaded2003');
    options.forEach((option) => expect(option).toHaveAttribute('aria-selected', 'false'));
  });

  it('anuncia a los lectores de pantalla cuántos resultados hay (región role="status")', async () => {
    const { user } = setup();

    await searchMatrix(user);

    expect(announced()).toHaveTextContent(/^3 resultados\./);
  });

  it('sin coincidencias lo dice, tanto en pantalla como en la región anunciada', async () => {
    const { user, input } = setup(() => jsonResponse(makePage([])));

    await user.type(input, 'matrix');
    passDebounce();

    expect(await screen.findByText('Sin resultados para «matrix».')).toBeInTheDocument();
    expect(announced()).toHaveTextContent('Sin resultados para matrix.');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).toHaveAttribute('aria-expanded', 'false');
  });

  it('si la búsqueda falla muestra el error del servidor en lugar de quedarse muda', async () => {
    const { user, input } = setup(() => errorResponse(400, 'VALIDATION_ERROR', 'El título es demasiado largo'));

    await user.type(input, 'matrix');
    passDebounce();

    // Aparece dos veces: en el desplegable visible y en la región anunciada a lectores de pantalla.
    expect(await screen.findAllByText('El título es demasiado largo')).toHaveLength(2);
    expect(announced()).toHaveTextContent('El título es demasiado largo');
  });
});

describe('SearchBar: teclado', () => {
  it('↓ recorre las opciones (circular), marca aria-selected y aria-activedescendant, y el foco no sale del campo', async () => {
    const { user, input } = setup();
    const list = await searchMatrix(user);
    const options = within(list).getAllByRole('option');

    await user.keyboard('{ArrowDown}');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', options[0].id);

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(options[2]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowDown}'); // da la vuelta
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(options[2]).toHaveAttribute('aria-selected', 'false');
    expect(input).toHaveFocus();
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
  });

  it('↑ sin selección salta a la última opción y recorre hacia atrás', async () => {
    const { user, input } = setup();
    const list = await searchMatrix(user);
    const options = within(list).getAllByRole('option');

    await user.keyboard('{ArrowUp}');
    expect(options[2]).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', options[2].id);

    await user.keyboard('{ArrowUp}');
    expect(options[1]).toHaveAttribute('aria-selected', 'true');
  });

  it('Intro con una opción resaltada abre los detalles de ESA película y cierra y vacía el buscador', async () => {
    const { user, input } = setup();
    await searchMatrix(user);

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    const dialog = await screen.findByRole('dialog', { name: 'Matrix Reloaded' });
    expect(dialog).toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('Intro sin ninguna resaltada abre la primera', async () => {
    const { user } = setup();
    await searchMatrix(user);

    await user.keyboard('{Enter}');

    expect(await screen.findByRole('dialog', { name: 'Matrix' })).toBeInTheDocument();
  });

  it('Escape cierra la lista y vacía el campo', async () => {
    const { user, input } = setup();
    await searchMatrix(user);

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(input).toHaveFocus();
  });

  it('Escape con el buscador ya cerrado y vacío no se consume (otros componentes pueden usarlo)', async () => {
    const { user } = setup();
    const prevented: boolean[] = [];
    const record = (event: KeyboardEvent) => prevented.push(event.defaultPrevented);
    // Escucha en `document`: ahí llega el evento DESPUÉS de que React lo haya gestionado.
    document.addEventListener('keydown', record);

    await user.click(screen.getByRole('combobox')); // al enfocar se "abre" aunque no haya nada
    await user.keyboard('{Escape}'); // 1.º: cierra el desplegable y se consume
    await user.keyboard('{Escape}'); // 2.º: ya no hay nada que cerrar
    document.removeEventListener('keydown', record);

    expect(prevented).toEqual([true, false]);
  });
});

describe('SearchBar: ratón', () => {
  it('pulsar una opción abre sus detalles', async () => {
    const { user } = setup();
    const list = await searchMatrix(user);

    await user.click(within(list).getByRole('option', { name: /Matrix Revolutions/ }));

    expect(await screen.findByRole('dialog', { name: 'Matrix Revolutions' })).toBeInTheDocument();
  });

  it('pulsar fuera del buscador cierra la lista y vacía el campo', async () => {
    const { user, input } = setup();
    await searchMatrix(user);

    await user.click(document.body);

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
  });
});
