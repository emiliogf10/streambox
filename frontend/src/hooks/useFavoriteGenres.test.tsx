/**
 * Tests de `useSaveFavoriteGenres` con la sesión y la caché reales y `fetch`
 * simulado: lo que hace al terminar el `PUT /api/users/me/genres` con el usuario
 * de la sesión.
 *
 * Protegen la observación de `security`: cerrar la pregunta de bienvenida
 * (`genresOnboardingPending: false`) debe cambiar SOLO ese campo sobre el
 * usuario más reciente. Si el nombre cambió mientras el `PUT` estaba en vuelo
 * (`PATCH /users/me` desde el perfil, en otra parte de la pantalla), no puede
 * volver al nombre anterior.
 *
 * Y que un refresco de la lista que salió ANTES del `PUT` no sustituya lo
 * guardado por la lista de antes cuando responde después.
 */
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuth } from '../context/AuthContext';
import { queryKeys } from '../lib/queryKeys';
import type { Genre } from '../lib/types';
import {
  CURRENT_USER,
  FAVORITE_GENRES,
  SAVE_FAVORITE_GENRES,
  jsonResponse,
  makeUser,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { createTestQueryClient } from '../test/queryClient';
import { useFavoriteGenres, useSaveFavoriteGenres } from './useFavoriteGenres';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

const SAVED: Genre[] = [
  { id: 1, name: 'Acción' },
  { id: 2, name: 'Comedia' },
  { id: 3, name: 'Drama' },
];

/** El guardado, desde un componente que puede desmontarse con el `PUT` en vuelo. */
function SaveButton() {
  const { save } = useSaveFavoriteGenres();
  return (
    <button type="button" onClick={() => void save(SAVED.map((genre) => genre.id))}>
      Guardar gustos
    </button>
  );
}

/**
 * El guardado y un cambio de nombre desde otro componente, como en la
 * aplicación. «Salir» desmonta el que guardó (p. ej. se navega a otra página
 * mientras responde el servidor): su mutación termina igual, con lo último que
 * vio ese componente, que ya no se vuelve a pintar.
 */
function Screen() {
  const [showSave, setShowSave] = useState(true);
  return (
    <>
      {showSave && <SaveButton />}
      <button type="button" onClick={() => setShowSave(false)}>
        Salir
      </button>
      <RenameButton />
    </>
  );
}

/** Simula `UsernameForm`: sustituye el usuario por la respuesta del `PATCH`. */
function RenameButton() {
  const { user, updateUser } = useAuth();
  return (
    <>
      <button type="button" onClick={() => user && updateUser({ ...user, username: 'ana_nueva' })}>
        Renombrar
      </button>
      <p>{`nombre:${user?.username ?? ''} pendiente:${String(user?.genresOnboardingPending)}`}</p>
    </>
  );
}

describe('useSaveFavoriteGenres: usuario de la sesión', () => {
  it('cierra la pregunta sin deshacer un cambio de nombre hecho mientras el PUT estaba en vuelo (y ya fuera de la pantalla)', async () => {
    let answer!: (response: Response) => void;
    routeFetch(fetchMock, {
      [CURRENT_USER]: () => jsonResponse(makeUser({ genresOnboardingPending: true })),
      [SAVE_FAVORITE_GENRES]: () =>
        new Promise<Response>((resolve) => {
          answer = resolve;
        }),
    });
    const user = userEvent.setup();
    renderWithProviders(<Screen />, { session: true });
    expect(await screen.findByText('nombre:ana pendiente:true')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Guardar gustos' }));
    await waitFor(() => expect(answer).toBeTypeOf('function'));
    await user.click(screen.getByRole('button', { name: 'Salir' }));
    await user.click(screen.getByRole('button', { name: 'Renombrar' }));
    expect(screen.getByText('nombre:ana_nueva pendiente:true')).toBeInTheDocument();

    answer(jsonResponse(SAVED));

    expect(await screen.findByText('nombre:ana_nueva pendiente:false')).toBeInTheDocument();
  });
});

/** Los favoritos que se ven en pantalla y el botón de guardar. */
function TastesScreen() {
  const favorites = useFavoriteGenres();
  return (
    <>
      <SaveButton />
      <p>{`gustos:${favorites.genres.map((genre) => genre.name).join(',')}`}</p>
    </>
  );
}

describe('useSaveFavoriteGenres: lista en caché', () => {
  it('un refresco en vuelo anterior al PUT no deshace lo guardado al responder después', async () => {
    const queryClient = createTestQueryClient();
    const before: Genre[] = [{ id: 9, name: 'Terror' }];
    let answerStaleRead: (response: Response) => void = () => {};
    let reads = 0;
    routeFetch(fetchMock, {
      [FAVORITE_GENRES]: () => {
        reads += 1;
        if (reads === 2) {
          return new Promise<Response>((resolve) => {
            answerStaleRead = resolve;
          });
        }
        return jsonResponse(before);
      },
      [SAVE_FAVORITE_GENRES]: () => jsonResponse(SAVED),
    });
    const user = userEvent.setup();
    renderWithProviders(<TastesScreen />, { session: true, queryClient });
    expect(await screen.findByText('gustos:Terror')).toBeInTheDocument();

    // Un refresco en segundo plano, como el de volver a la pestaña con el dato anticuado.
    await act(async () => {
      void queryClient.refetchQueries({ queryKey: queryKeys.favoriteGenres.mine });
      await Promise.resolve();
    });
    await waitFor(() => expect(reads).toBe(2));

    await user.click(screen.getByRole('button', { name: 'Guardar gustos' }));
    expect(await screen.findByText('gustos:Acción,Comedia,Drama')).toBeInTheDocument();

    await act(async () => {
      answerStaleRead(jsonResponse(before));
      await Promise.resolve();
    });
    await waitFor(() => expect(queryClient.getQueryState(queryKeys.favoriteGenres.mine)?.fetchStatus).toBe('idle'));

    expect(screen.getByText('gustos:Acción,Comedia,Drama')).toBeInTheDocument();
  });
});
