/**
 * Tests de la fila «Seguir viendo» (`ContinueWatchingRow`).
 *
 * Lo que tiene que cumplir: solo aparece si hay algo a medias, cada tarjeta
 * lleva DIRECTAMENTE al reproductor (no a la página del título), dice lo que
 * queda con palabras (no solo con la barra) y «Quitar» llama al `DELETE`
 * correcto (el de la SERIE para un episodio), avisa y no deja el foco perdido.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ContinueWatchingItem } from '../lib/types';
import {
  CONTINUE_WATCHING,
  errorResponse,
  jsonResponse,
  makeContinueWatchingItem,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { ContinueWatchingRow } from './ContinueWatchingRow';
import { MAIN_CONTENT_ID } from './SkipLink';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const movie: ContinueWatchingItem = {
  type: 'MOVIE',
  id: 7,
  title: 'Interstellar',
  imageUrl: 'https://img.example/interstellar.jpg',
  seasonNumber: null,
  episodeNumber: null,
  seriesId: null,
  seriesTitle: null,
  upNext: false,
  positionSeconds: 3000,
  durationSeconds: 5040,
  updatedAt: '2026-11-21T10:00:00Z',
};

/** Un episodio cuyo id (7) coincide con el de la película: deben ser tarjetas distintas. */
const episode: ContinueWatchingItem = {
  type: 'EPISODE',
  id: 7,
  title: 'Mentiras',
  imageUrl: 'https://img.example/dark.jpg',
  seasonNumber: 1,
  episodeNumber: 2,
  seriesId: 3,
  seriesTitle: 'Dark',
  upNext: false,
  positionSeconds: 600,
  durationSeconds: 3000,
  updatedAt: '2026-11-21T09:00:00Z',
};

/** Lo que el servidor simulado tiene en «Seguir viendo» (los `DELETE` que van bien lo cambian). */
let serverItems: ContinueWatchingItem[] = [];

function renderRow(items: ContinueWatchingItem[], extra: Parameters<typeof routeFetch>[1] = {}) {
  serverItems = items;
  routeFetch(fetchMock, { [CONTINUE_WATCHING]: () => jsonResponse(serverItems), ...extra });
  const user = userEvent.setup();
  renderWithProviders(
    <main id={MAIN_CONTENT_ID} tabIndex={-1}>
      <ContinueWatchingRow />
    </main>,
    { session: true },
  );
  return user;
}

describe('ContinueWatchingRow', () => {
  it('sin nada a medias no pinta nada (ni título ni estado vacío)', async () => {
    renderRow([]);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/users/me/continue-watching?limit=12', expect.anything()));
    expect(screen.queryByRole('heading', { name: 'Seguir viendo' })).not.toBeInTheDocument();
  });

  it('cada tarjeta lleva al reproductor, reanudando, y dice lo que queda (el episodio, con su código)', async () => {
    renderRow([movie, episode]);
    const row = await screen.findByRole('region', { name: 'Seguir viendo' });

    const movieLink = within(row).getByRole('link', { name: /^Reanudar Interstellar/ });
    expect(movieLink).toHaveAttribute('href', '/ver/peliculas/7');
    expect(movieLink).toHaveTextContent('Quedan 34 min');

    // La tarjeta de un episodio se llama como la serie (es lo que se reconoce) y lleva a ESE episodio.
    const episodeLink = within(row).getByRole('link', { name: /^Reanudar Dark/ });
    expect(episodeLink).toHaveAttribute('href', '/ver/series/3/episodios/7');
    expect(episodeLink).toHaveTextContent('T1:E2 · Quedan 40 min');
    // La barra es un refuerzo visual: el texto ya lo dice y no entra en el nombre del enlace.
    expect(within(row).queryByRole('progressbar')).not.toBeInTheDocument();
  });

  describe('siguiente episodio (`upNext`, decisión QA-4)', () => {
    /** Terminó T1:E2 y la tarjeta es T1:E3, que aún no ha empezado: sin posición ni duración. */
    const nextEpisode = makeContinueWatchingItem({
      id: 103,
      title: 'Pasado y presente',
      episodeNumber: 3,
      upNext: true,
      positionSeconds: null,
      durationSeconds: null,
    });

    it('sin progreso propio: «Ver» (no «Reanudar»), «T1:E3 · Siguiente episodio» y la barra vacía', async () => {
      renderRow([nextEpisode]);
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });

      const link = within(row).getByRole('link', { name: /^Ver Dark T1:E3 · Siguiente episodio/ });
      expect(link).toHaveAttribute('href', '/ver/series/3/episodios/103');
      expect(within(row).queryByRole('link', { name: /Reanudar/ })).not.toBeInTheDocument();
      // Ni «Quedan…» inventado a partir de los nulos (sería «Queda menos de 1 min») ni nada parecido.
      expect(link).not.toHaveTextContent(/Queda/);
      // La barra sigue ahí (misma altura que las demás tarjetas), pero vacía: no ha visto nada de este episodio.
      const bar = within(row).getByRole('progressbar', { hidden: true });
      expect(bar).toHaveAttribute('value', '0');
      expect(within(row).getByRole('button', { name: 'Quitar Dark de Seguir viendo' })).toBeInTheDocument();
    });

    it('con progreso propio a medias: una tarjeta normal («Reanudar», lo que queda y su barra)', async () => {
      renderRow([{ ...nextEpisode, positionSeconds: 600, durationSeconds: 3000 }]);
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });

      const link = within(row).getByRole('link', { name: /^Reanudar Dark T1:E3 · Quedan 40 min/ });
      expect(link).toHaveAttribute('href', '/ver/series/3/episodios/103');
      expect(link).not.toHaveTextContent('Siguiente episodio');
      expect(within(row).getByRole('progressbar', { hidden: true })).toHaveAttribute('value', '600');
    });

    it('«Quitar» el siguiente episodio borra el progreso de toda la serie (DELETE de la serie)', async () => {
      const user = renderRow([nextEpisode], {
        'DELETE /api/users/me/continue-watching/series/3': () => {
          serverItems = [];
          return noContentResponse();
        },
      });
      const row = await screen.findByRole('region', { name: 'Seguir viendo' });

      await user.click(within(row).getByRole('button', { name: 'Quitar Dark de Seguir viendo' }));

      expect(await screen.findByText(/«Dark» ya no está en Seguir viendo/)).toBeInTheDocument();
      expect(fetchMock).toHaveBeenCalledWith('/api/users/me/continue-watching/series/3', expect.objectContaining({ method: 'DELETE' }));
    });
  });

  it('apenas empezado (menos de 5 s): «Ver», no «Reanudar», como en la página del título (el reproductor empezará en el 0)', async () => {
    renderRow([{ ...movie, positionSeconds: 3 }]);
    const row = await screen.findByRole('region', { name: 'Seguir viendo' });

    expect(within(row).getByRole('link', { name: /^Ver Interstellar/ })).toHaveAttribute('href', '/ver/peliculas/7');
  });

  /**
   * Revisión QA de QA-4: el umbral de «Reanudar» de la tarjeta es exactamente
   * el del reproductor (`resumePositionOf`, 5 s): a los 4 s empezará en el 0
   * («Ver»), a los 5 s reanudará («Reanudar»). También en un siguiente episodio
   * (`upNext`) que ya se había empezado.
   */
  it('umbral de «Reanudar»: 4 s es «Ver» y 5 s es «Reanudar», también en el siguiente episodio', async () => {
    renderRow([
      { ...movie, positionSeconds: 4 },
      { ...episode, upNext: true, positionSeconds: 5 },
    ]);
    const row = await screen.findByRole('region', { name: 'Seguir viendo' });

    expect(within(row).getByRole('link', { name: /^Ver Interstellar/ })).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: /^Reanudar Dark T1:E2 · Quedan 50 min/ })).toBeInTheDocument();
  });

  it('«Quitar» una película: DELETE de la película, la tarjeta desaparece, aviso y foco a la siguiente', async () => {
    const user = renderRow([movie, episode], {
      'DELETE /api/users/me/continue-watching/movies/7': () => {
        serverItems = serverItems.filter((item) => item !== movie);
        return noContentResponse();
      },
    });
    const row = await screen.findByRole('region', { name: 'Seguir viendo' });

    await user.click(within(row).getByRole('button', { name: 'Quitar Interstellar de Seguir viendo' }));

    await waitFor(() => expect(within(row).queryByRole('link', { name: /Interstellar/ })).not.toBeInTheDocument());
    expect(
      await screen.findByText('«Interstellar» ya no está en Seguir viendo. Si vuelves a verla, empezará desde el principio.'),
    ).toBeInTheDocument();
    expect(within(row).getByRole('link', { name: /^Reanudar Dark/ })).toHaveFocus();
    const deleteCall = fetchMock.mock.calls.find(([, init]) => init?.method === 'DELETE');
    expect(new Headers(deleteCall?.[1]?.headers).get('X-Requested-With')).toBe('StreamBox');
  });

  it('«Quitar» un episodio borra el progreso de la SERIE (una tarjeta por serie); sin más tarjetas, el foco va al contenido', async () => {
    const user = renderRow([episode], {
      'DELETE /api/users/me/continue-watching/series/3': () => {
        serverItems = [];
        return noContentResponse();
      },
    });
    const row = await screen.findByRole('region', { name: 'Seguir viendo' });

    await user.click(within(row).getByRole('button', { name: 'Quitar Dark de Seguir viendo' }));

    expect(await screen.findByText(/«Dark» ya no está en Seguir viendo/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('region', { name: 'Seguir viendo' })).not.toBeInTheDocument());
    expect(document.getElementById(MAIN_CONTENT_ID)).toHaveFocus();
  });

  it('si el servidor falla, la tarjeta vuelve a su sitio y se avisa del error', async () => {
    const user = renderRow([movie, episode], {
      'DELETE /api/users/me/continue-watching/movies/7': () => errorResponse(500, 'INTERNAL_ERROR', 'Fallo.'),
    });
    const row = await screen.findByRole('region', { name: 'Seguir viendo' });

    await user.click(within(row).getByRole('button', { name: 'Quitar Interstellar de Seguir viendo' }));

    expect(await screen.findByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    const links = within(row).getAllByRole('link');
    expect(links[0]).toHaveAccessibleName(/^Reanudar Interstellar/);
  });

  it('si no se puede cargar, lo dice dentro de la fila con «Reintentar»', async () => {
    renderRow([], { [CONTINUE_WATCHING]: () => errorResponse(500, 'INTERNAL_ERROR', 'Fallo.') });

    expect(await screen.findByText('No se pudo cargar lo que tienes a medias.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Reintentar/ })).toBeInTheDocument();
  });
});
