/**
 * Tests de la sección «Te gusta» del perfil (`LikedTitlesSection`) con el hook
 * real `useLikes` y `fetch` simulado.
 *
 * Protegen los tres estados (cargando, error con «Reintentar», vacío con el
 * mismo texto verdadero para USER y ADMIN), los dos grupos con enlaces a la
 * página de cada título en el orden del servidor, el tope de
 * {@link LIKED_PREVIEW_LIMIT} con «Mostrar las N…» y que un «Me gusta» dado en
 * la misma pantalla aparece al momento, el primero.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CURRENT_USER,
  LIKED_MOVIES,
  LIKED_SERIES,
  errorResponse,
  jsonResponse,
  makeMovie,
  makeSeries,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { UserStatusProbe } from '../../test/UserStatusProbe';
import { LikeButton } from '../LikeButton';
import { LIKED_PREVIEW_LIMIT, LikedTitlesSection } from './LikedTitlesSection';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const dune = makeMovie({ id: 1, title: 'Dune', releaseYear: 2021 });
const arrival = makeMovie({ id: 2, title: 'Arrival', releaseYear: 2016 });
const dark = makeSeries({ id: 1, title: 'Dark', releaseYear: 2017, endYear: 2020, seasonCount: 3 });

/** Texto del estado vacío (el mismo para los dos roles). */
const EMPTY_TITLE = 'Todavía no te gusta ningún título';
const EMPTY_TEXT =
  'Cuando veas una película o una serie que te guste, pulsa «Me gusta» en su página. Aquí la verás, y en el inicio te recomendaremos títulos parecidos.';

/** Región de la sección. */
const section = () => screen.getByRole('region', { name: 'Te gusta' });

describe('LikedTitlesSection: estados', () => {
  it('mientras carga lo anuncia (role="status") dentro de la sección', () => {
    routeFetch(fetchMock, { [LIKED_MOVIES]: () => new Promise<Response>(() => {}) });
    renderWithProviders(<LikedTitlesSection />, { session: true });

    expect(within(section()).getByRole('status')).toHaveTextContent('Cargando lo que te gusta...');
  });

  it.each(['USER', 'ADMIN'] as const)(
    'vacía (%s): dice cómo llenarla y para qué sirve, sin botones que lleven a otra página',
    async (role) => {
      routeFetch(fetchMock, { [CURRENT_USER]: () => jsonResponse(makeUser({ role })) });
      renderWithProviders(
        <>
          <LikedTitlesSection />
          <UserStatusProbe />
        </>,
        { session: true },
      );

      expect(await within(section()).findByText(EMPTY_TITLE)).toBeInTheDocument();
      expect(within(section()).getByText(EMPTY_TEXT)).toBeInTheDocument();
      await screen.findByText('estado:ready');
      expect(within(section()).queryByRole('link')).not.toBeInTheDocument();
      expect(within(section()).queryByRole('button')).not.toBeInTheDocument();
    },
  );

  it('si falla la carga, avisa con «Reintentar» y al reintentar enseña la lista', async () => {
    routeFetch(fetchMock, { [LIKED_MOVIES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderWithProviders(<LikedTitlesSection />, { session: true });

    const alert = await within(section()).findByRole('alert');
    expect(within(alert).getByRole('heading', { name: 'No se pudo cargar lo que te gusta' })).toBeInTheDocument();
    expect(alert).toHaveTextContent('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');

    routeFetch(fetchMock, { [LIKED_MOVIES]: () => jsonResponse([dune]) });
    await user.click(within(alert).getByRole('button', { name: 'Reintentar' }));

    expect(await within(section()).findByRole('link', { name: /^Dune 2021 ·/ })).toBeInTheDocument();
  });
});

describe('LikedTitlesSection: con títulos', () => {
  it('enseña películas y series en el orden del servidor, con su número y enlace a su página', async () => {
    routeFetch(fetchMock, {
      [LIKED_MOVIES]: () => jsonResponse([dune, arrival]),
      [LIKED_SERIES]: () => jsonResponse([dark]),
    });
    renderWithProviders(<LikedTitlesSection />, { session: true });

    expect(await within(section()).findByText('3 títulos · lo más reciente primero')).toBeInTheDocument();
    expect(within(section()).getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual([
      'Películas (2)',
      'Series (1)',
    ]);
    const links = within(section()).getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/peliculas/1', '/peliculas/2', '/series/1']);
    expect(links[2]).toHaveAccessibleName('Dark 2017–2020 · 3 temporadas');
    expect(within(section()).queryByText(EMPTY_TITLE)).not.toBeInTheDocument();
  });

  it('un grupo sin títulos no se pinta', async () => {
    routeFetch(fetchMock, { [LIKED_SERIES]: () => jsonResponse([dark]) });
    renderWithProviders(<LikedTitlesSection />, { session: true });

    expect(await within(section()).findByRole('heading', { level: 3, name: 'Series (1)' })).toBeInTheDocument();
    expect(within(section()).queryByRole('heading', { level: 3, name: /^Películas/ })).not.toBeInTheDocument();
    expect(within(section()).getByText('1 título · lo más reciente primero')).toBeInTheDocument();
  });

  it(`con más de ${LIKED_PREVIEW_LIMIT}, enseña los primeros y despliega el resto aquí mismo`, async () => {
    const movies = Array.from({ length: 15 }, (_, index) => makeMovie({ id: index + 1, title: `Peli ${index + 1}` }));
    routeFetch(fetchMock, { [LIKED_MOVIES]: () => jsonResponse(movies) });
    const user = userEvent.setup();
    renderWithProviders(<LikedTitlesSection />, { session: true });

    const more = await within(section()).findByRole('button', { name: 'Mostrar las 15 películas' });
    expect(within(section()).getAllByRole('link')).toHaveLength(LIKED_PREVIEW_LIMIT);
    expect(more).toHaveAttribute('aria-expanded', 'false');
    expect(document.getElementById(more.getAttribute('aria-controls') ?? '')).toBeInstanceOf(HTMLUListElement);

    await user.click(more);
    expect(within(section()).getAllByRole('link')).toHaveLength(15);
    const less = within(section()).getByRole('button', { name: 'Mostrar menos' });
    expect(less).toHaveAttribute('aria-expanded', 'true');
    expect(less).toHaveFocus();

    await user.click(less);
    expect(within(section()).getAllByRole('link')).toHaveLength(LIKED_PREVIEW_LIMIT);
  });

  it('un «Me gusta» dado en la misma pantalla aparece al momento, el primero; y al quitarlo desaparece', async () => {
    routeFetch(fetchMock, {
      [LIKED_MOVIES]: () => jsonResponse([dune]),
      'PUT /api/users/me/likes/movies/2': () => noContentResponse(),
      'DELETE /api/users/me/likes/movies/2': () => noContentResponse(),
    });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <LikeButton movie={arrival} />
        <LikedTitlesSection />
      </>,
      { session: true },
    );
    await within(section()).findByRole('link', { name: /^Dune/ });

    await user.click(screen.getByRole('button', { name: /^Me gusta\s*—\s*Arrival$/ }));
    expect(within(section()).getAllByRole('link').map((link) => link.getAttribute('href'))).toEqual([
      '/peliculas/2',
      '/peliculas/1',
    ]);

    await user.click(await screen.findByRole('button', { name: /^Te gusta\s*—\s*Arrival$/ }));
    await waitFor(() => expect(within(section()).getAllByRole('link')).toHaveLength(1));
  });
});
