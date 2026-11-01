/**
 * Tests de «Tus gustos» del perfil (`FavoriteGenresSection`) con la sesión, la
 * caché y los avisos reales y `fetch` simulado.
 *
 * Protegen: ver la lista o un estado vacío verdadero (para USER y ADMIN, que
 * también tiene la sección), editar con el mismo selector que la bienvenida
 * (3–5, «Guardar» y «Cancelar»), quitar con confirmación, los errores de cada
 * paso, que un favorito que ya no tiene títulos no se envía (y se avisa), que
 * al llegar con `#tus-gustos` el foco va al título y que tras guardar se
 * descartan las recomendaciones en caché.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { queryKeys } from '../../lib/queryKeys';
import {
  CURRENT_USER,
  FAVORITE_GENRES,
  GENRE_OPTIONS,
  SAVE_FAVORITE_GENRES,
  errorResponse,
  jsonResponse,
  makeRecommendations,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { createTestQueryClient } from '../../test/queryClient';
import { FavoriteGenresSection, TASTES_REMOVED_MESSAGE, TASTES_SAVED_MESSAGE } from './FavoriteGenresSection';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const GENRES = ['Acción', 'Comedia', 'Drama', 'Fantasía', 'Terror'].map((name, index) => ({ id: index + 1, name }));
const [accion, comedia, drama, fantasia, terror] = GENRES;

/** Bodies de los `PUT /api/users/me/genres` enviados. */
function savedBodies(): unknown[] {
  return fetchMock.mock.calls
    .filter(([url, init]) => String(url) === '/api/users/me/genres' && init?.method === 'PUT')
    .map(([, init]) => JSON.parse(String(init?.body)));
}

/** Monta la sección con los favoritos `mine`, el rol y las rutas extra. */
function renderSection({
  mine = [] as typeof GENRES,
  role = 'USER' as 'USER' | 'ADMIN',
  route = '/perfil',
  routes = {} as Parameters<typeof routeFetch>[1],
} = {}) {
  routeFetch(fetchMock, {
    [CURRENT_USER]: () => jsonResponse(makeUser({ role })),
    [FAVORITE_GENRES]: () => jsonResponse(mine),
    [GENRE_OPTIONS]: () => jsonResponse(GENRES),
    [SAVE_FAVORITE_GENRES]: ({ init }) => {
      const { genreIds } = JSON.parse(String(init.body)) as { genreIds: number[] };
      return jsonResponse(GENRES.filter((genre) => genreIds.includes(genre.id)));
    },
    ...routes,
  });
  const queryClient = createTestQueryClient();
  const user = userEvent.setup();
  renderWithProviders(<FavoriteGenresSection />, { route, session: true, queryClient });
  return { user, queryClient, section: () => screen.getByRole('region', { name: 'Tus gustos' }) };
}

describe('FavoriteGenresSection: ver', () => {
  it.each(['USER', 'ADMIN'] as const)('sin favoritos (%s): un estado vacío que explica para qué sirven', async (role) => {
    const { section } = renderSection({ role });

    expect(await screen.findByText('Aún no has elegido tus géneros favoritos')).toBeInTheDocument();
    expect(section()).toHaveTextContent(
      'Elige entre 3 y 5 y te recomendaremos en el inicio películas y series de esos géneros, junto con las parecidas a lo que marques con «Me gusta».',
    );
    expect(within(section()).getByRole('button', { name: 'Elegir géneros' })).toBeInTheDocument();
    expect(within(section()).queryByRole('button', { name: 'Quitar mis gustos' })).not.toBeInTheDocument();
  });

  it.each(['USER', 'ADMIN'] as const)('con favoritos (%s): la lista, «Editar» y «Quitar mis gustos»', async (role) => {
    const { section } = renderSection({ role, mine: [accion, drama, terror] });

    const list = await within(section()).findByRole('list');
    expect(within(list).getAllByRole('listitem').map((item) => item.textContent)).toEqual(['Acción', 'Drama', 'Terror']);
    expect(within(section()).getByRole('button', { name: 'Editar' })).toBeInTheDocument();
    expect(within(section()).getByRole('button', { name: 'Quitar mis gustos' })).toBeInTheDocument();
  });

  it('si no se pueden cargar, solo falla la sección, con «Reintentar»', async () => {
    let fail = true;
    const { user } = renderSection({
      routes: { [FAVORITE_GENRES]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse([accion])) },
    });

    const heading = await screen.findByRole('heading', { name: 'No se pudieron cargar tus gustos' });
    fail = false;
    await user.click(within(heading.closest<HTMLElement>('[role="alert"]')!).getByRole('button', { name: 'Reintentar' }));
    expect(await screen.findByText('Acción')).toBeInTheDocument();
  });

  it('al llegar con #tus-gustos (la tarjeta de la portada), el foco va al título de la sección', async () => {
    renderSection({ route: '/perfil#tus-gustos' });

    const heading = await screen.findByRole('heading', { level: 2, name: 'Tus gustos' });
    await waitFor(() => expect(heading).toHaveFocus());
    expect(screen.getByRole('region', { name: 'Tus gustos' })).toHaveAttribute('id', 'tus-gustos');
  });
});

describe('FavoriteGenresSection: editar', () => {
  it('abre el selector con los suyos marcados; «Guardar» solo con 3–5; guarda, avisa y vuelve a la lista', async () => {
    const { user, section, queryClient } = renderSection({ mine: [accion, drama, terror] });
    queryClient.setQueryData(queryKeys.recommendations.list(12), makeRecommendations());
    await user.click(await within(section()).findByRole('button', { name: 'Editar' }));

    const group = await within(section()).findByRole('group', { name: 'Elige entre 3 y 5 géneros' });
    await waitFor(() => expect(group).toHaveFocus());
    expect(within(group).getByRole('checkbox', { name: 'Acción' })).toBeChecked();
    expect(within(group).getByRole('checkbox', { name: 'Comedia' })).not.toBeChecked();

    await user.click(within(group).getByRole('checkbox', { name: 'Terror' }));
    const save = within(section()).getByRole('button', { name: 'Guardar' });
    expect(save).toHaveAttribute('aria-disabled', 'true');
    expect(save).toHaveAccessibleDescription('Has elegido 2 de 5. Elige al menos 1 más.');
    await user.click(within(group).getByRole('checkbox', { name: 'Fantasía' }));
    await user.click(within(group).getByRole('checkbox', { name: 'Comedia' }));
    await user.click(save);

    expect(await screen.findByText(TASTES_SAVED_MESSAGE)).toBeInTheDocument();
    expect(savedBodies()).toEqual([{ genreIds: [1, 3, 4, 2] }]);
    const list = within(section()).getByRole('list');
    expect(within(list).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Acción',
      'Comedia',
      'Drama',
      'Fantasía',
    ]);
    await waitFor(() => expect(within(section()).getByRole('button', { name: 'Editar' })).toHaveFocus());
    expect(queryClient.getQueryData(queryKeys.recommendations.list(12))).toBeUndefined();
  });

  it('desde el estado vacío, «Elegir géneros» abre el mismo selector y «Cancelar» lo cierra sin petición', async () => {
    const { user, section } = renderSection();
    await user.click(await within(section()).findByRole('button', { name: 'Elegir géneros' }));

    expect(await within(section()).findByRole('group', { name: 'Elige entre 3 y 5 géneros' })).toBeInTheDocument();
    await user.click(within(section()).getByRole('button', { name: 'Cancelar' }));

    expect(await within(section()).findByRole('button', { name: 'Elegir géneros' })).toHaveFocus();
    expect(savedBodies()).toEqual([]);
  });

  it('un favorito que ya no tiene títulos no se marca ni se envía, y se avisa', async () => {
    const huerfano = { id: 99, name: 'Wéstern' };
    const { user, section } = renderSection({ mine: [accion, drama, huerfano] });
    await user.click(await within(section()).findByRole('button', { name: 'Editar' }));

    const group = await within(section()).findByRole('group', { name: 'Elige entre 3 y 5 géneros' });
    expect(group).toHaveTextContent(
      '«Wéstern» ya no tiene títulos, así que no se puede elegir: si guardas, dejará de estar entre tus gustos.',
    );
    expect(within(group).getByRole('status')).toHaveTextContent('Has elegido 2 de 5.');
    await user.click(within(group).getByRole('checkbox', { name: 'Terror' }));
    await user.click(within(section()).getByRole('button', { name: 'Guardar' }));

    await screen.findByText(TASTES_SAVED_MESSAGE);
    expect(savedBodies()).toEqual([{ genreIds: [1, 3, 5] }]);
  });

  it('si falla el guardado, aviso en el editor, que sigue abierto para reintentar', async () => {
    const { user, section } = renderSection({
      mine: [accion, comedia, drama],
      routes: { [SAVE_FAVORITE_GENRES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') },
    });
    await user.click(await within(section()).findByRole('button', { name: 'Editar' }));
    await within(section()).findByRole('group', { name: 'Elige entre 3 y 5 géneros' });

    await user.click(within(section()).getByRole('button', { name: 'Guardar' }));

    expect(await within(section()).findByText('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.')).toBeInTheDocument();
    expect(within(section()).getByRole('group', { name: 'Elige entre 3 y 5 géneros' })).toBeInTheDocument();
  });

  /**
   * El texto habla de títulos VISIBLES: un ADMIN puede tener series con género
   * pero sin episodios (las ve en el panel), y «aún no hay películas ni series
   * con género en el catálogo» sería falso para él.
   */
  it.each(['USER', 'ADMIN'] as const)('sin géneros elegibles (%s) lo dice con un texto verdadero y solo queda «Cancelar»', async (role) => {
    const { user, section } = renderSection({ role, routes: { [GENRE_OPTIONS]: () => jsonResponse([]) } });
    await user.click(await within(section()).findByRole('button', { name: 'Elegir géneros' }));

    expect(await within(section()).findByText('Todavía no hay géneros que elegir')).toBeInTheDocument();
    expect(within(section()).getByText('Ningún título visible tiene género todavía.')).toBeInTheDocument();
    expect(section()).not.toHaveTextContent(/en el catálogo/);
    expect(section()).not.toHaveTextContent('Me gusta» también sirven');
    expect(within(section()).queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument();
    expect(within(section()).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
  });

  it('con 1 o 2 géneros elegibles dice cuáles hay, que hacen falta 3 y que los «Me gusta» sí sirven', async () => {
    const { user, section } = renderSection({
      mine: [accion, comedia, drama],
      routes: { [GENRE_OPTIONS]: () => jsonResponse([accion, comedia]) },
    });
    await user.click(await within(section()).findByRole('button', { name: 'Editar' }));

    expect(await within(section()).findByText('Todavía no hay géneros suficientes')).toBeInTheDocument();
    expect(
      within(section()).getByText(
        'Hacen falta títulos visibles de al menos 3 géneros y por ahora solo los hay de «Acción» y «Comedia». Mientras tanto, los títulos que marques con «Me gusta» también sirven para recomendarte otros parecidos.',
      ),
    ).toBeInTheDocument();
    expect(within(section()).queryByRole('group')).not.toBeInTheDocument();

    // «Cancelar» vuelve a la lista sin enviar nada: «Quitar mis gustos» sigue disponible fuera del editor.
    await user.click(within(section()).getByRole('button', { name: 'Cancelar' }));
    expect(await within(section()).findByRole('button', { name: 'Quitar mis gustos' })).toBeInTheDocument();
    expect(savedBodies()).toEqual([]);
  });

  /**
   * Revisión de QA. Con menos de 3 géneros elegibles es imposible llegar al
   * mínimo: «Elegir géneros» no debe abrir un selector cuyo «Guardar» nunca se
   * habilita mientras el contador pide «Elige al menos 1 más» sin que quede
   * ninguno. Igual que sin géneros, hay que decirlo (USER y ADMIN).
   */
  it.each(['USER', 'ADMIN'] as const)(
    'con menos de 3 géneros elegibles (%s) no ofrece un «Guardar» imposible',
    async (role) => {
      const { user, section } = renderSection({
        role,
        routes: { [GENRE_OPTIONS]: () => jsonResponse([accion, comedia]) },
      });
      await user.click(await within(section()).findByRole('button', { name: 'Elegir géneros' }));
      await waitFor(() => expect(within(section()).queryByText('Cargando géneros...')).not.toBeInTheDocument());

      expect(within(section()).queryByRole('button', { name: 'Guardar' })).not.toBeInTheDocument();
      expect(within(section()).queryByText(/Elige al menos \d+ más/)).not.toBeInTheDocument();
      expect(within(section()).getByRole('button', { name: 'Cancelar' })).toBeInTheDocument();
    },
  );
});

describe('FavoriteGenresSection: quitar', () => {
  it('pide confirmación; al confirmar envía la lista vacía, avisa y deja el estado vacío con el foco en el título', async () => {
    const { user, section } = renderSection({ mine: [accion, drama, fantasia] });
    await user.click(await within(section()).findByRole('button', { name: 'Quitar mis gustos' }));

    const dialog = await screen.findByRole('alertdialog', { name: '¿Quitar tus gustos?' });
    expect(dialog).toHaveTextContent(
      'Dejaremos de usar estos géneros para recomendarte títulos. Podrás volver a elegirlos cuando quieras.',
    );
    expect(savedBodies()).toEqual([]);
    await user.click(within(dialog).getByRole('button', { name: 'Quitar mis gustos' }));

    expect(await screen.findByText(TASTES_REMOVED_MESSAGE)).toBeInTheDocument();
    expect(savedBodies()).toEqual([{ genreIds: [] }]);
    expect(await within(section()).findByText('Aún no has elegido tus géneros favoritos')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('heading', { level: 2, name: 'Tus gustos' })).toHaveFocus());
  });

  it('si falla, el error sale dentro del diálogo, que sigue abierto; «Cancelar» no envía nada', async () => {
    const { user, section } = renderSection({
      mine: [accion, drama, fantasia],
      routes: { [SAVE_FAVORITE_GENRES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') },
    });
    await user.click(await within(section()).findByRole('button', { name: 'Quitar mis gustos' }));
    const dialog = await screen.findByRole('alertdialog', { name: '¿Quitar tus gustos?' });

    await user.click(within(dialog).getByRole('button', { name: 'Quitar mis gustos' }));

    expect(await within(dialog).findByText('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    expect(within(section()).getByText('Acción')).toBeInTheDocument();
  });
});
