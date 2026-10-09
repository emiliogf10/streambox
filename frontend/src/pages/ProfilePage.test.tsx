/**
 * Tests de `ProfilePage` («Mi perfil») con la sesión y la lista de favoritos reales
 * y `fetch` simulado.
 *
 * Protegen: la cabecera según el rol (el botón «Panel de administración» solo
 * para el administrador), la inicial del avatar y «Miembro desde», los datos de
 * la cuenta (con la contraseña sin revelar), los tres estados del usuario
 * (cargando, error con reintento, listo) y de la lista (cargando, error con
 * reintento, vacía, con datos), el recuento y el orden de los géneros, que solo
 * se enseñen cinco títulos de la lista, y que «Cerrar sesión» espere al servidor
 * («Cerrando sesión...»; si falla, la sesión sigue abierta y se avisa).
 *
 * «Editar perfil»: cambiar el nombre (`PATCH /api/users/me`; la cabecera lo
 * enseña al instante; 409 y 400 junto al campo), cambiar la contraseña
 * (`PUT /api/users/me/password`; cada error del contrato en su sitio y la cuenta
 * atrás del 429) y «Cerrar sesión en todos los dispositivos» (confirmación,
 * 204 → sesión cerrada, 500/502 → sigue abierta y se avisa en el diálogo).
 * Ningún 400 cierra la sesión.
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  LOGOUT_ALL_DONE,
  LOGOUT_ALL_FAILED_NETWORK,
  LOGOUT_ALL_FAILED_SERVER,
  LOGOUT_FAILED_SERVER,
  LOGOUT_RETRY_DELAY_MS,
} from '../lib/logout';
import { PASSWORD_CHANGED, passwordRateLimitMessage, usernameChangedMessage } from '../lib/profile';
import { installManualTimers } from '../test/fakeTimers';
import {
  CURRENT_USER,
  errorResponse,
  FAVORITE_SERIES,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  makeUser,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import type { Movie, Series, User } from '../lib/types';
import { ProfilePage } from './ProfilePage';

const fetchMock = vi.fn<typeof fetch>();

const MOVIES = 'GET /api/users/me/favorites';
/** Lo que pregunta el estado vacío para saber si ofrecer «Explorar películas» (una página de tamaño 1). */
const ANY_MOVIE = 'GET /api/movies?page=0&size=1&sort=createdAt&direction=desc';

const drama = { id: 1, name: 'Drama' };
const scifi = { id: 2, name: 'Ciencia ficción' };
const thriller = { id: 3, name: 'Thriller' };

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

/** Opciones de {@link renderProfile}: el usuario, las listas y rutas extra de `fetch`. */
interface ProfileSetup {
  user?: User;
  movies?: Movie[];
  series?: Series[];
  extra?: Parameters<typeof routeFetch>[1];
  /** Opciones de user-event; con el reloj falso hace falta `{ delay: null }` (ver `src/test/fakeTimers.ts`). */
  events?: Parameters<typeof userEvent.setup>[0];
}

/** Monta el perfil con sesión iniciada. Por defecto: usuario normal con la lista vacía y películas en el catálogo. */
function renderProfile({ user = makeUser(), movies = [], series = [], extra = {}, events }: ProfileSetup = {}) {
  routeFetch(fetchMock, {
    [CURRENT_USER]: () => jsonResponse(user),
    [MOVIES]: () => jsonResponse(movies),
    [FAVORITE_SERIES]: () => jsonResponse(series),
    [ANY_MOVIE]: () => jsonResponse(makePage([makeMovie({ id: 99 })])),
    ...extra,
  });
  const userEvents = userEvent.setup(events);
  renderWithProviders(
    <FavoritesProvider>
      <ProfilePage />
    </FavoritesProvider>,
    { session: true, route: '/perfil' },
  );
  return userEvents;
}

/** Espera a que cargue el usuario (el `<h1>` pasa de «Mi perfil» a su nombre). */
const findName = (name: string) => screen.findByRole('heading', { level: 1, name });

/** El valor (`dd`) de la estadística o fila cuya etiqueta (`dt`) es `label`. */
const valueOf = (label: string) => screen.getByText(label).nextElementSibling;

const section = (name: string) => screen.getByRole('region', { name });

describe('ProfilePage: cabecera', () => {
  it('un usuario normal ve su nombre (único h1), el rol «Usuario», la inicial y «Miembro desde», y NO el panel de administración', async () => {
    renderProfile({ user: makeUser({ username: 'ana', role: 'USER', createdAt: '2026-10-03T08:15:00Z' }) });

    await findName('ana');
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByText('Usuario')).toBeInTheDocument();
    expect(screen.getByText('Miembro desde octubre de 2026')).toBeInTheDocument();
    // La inicial es decorativa: el nombre ya está en el h1 y no debe leerse dos veces.
    expect(screen.getByText('A')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.queryByRole('link', { name: 'Panel de administración' })).not.toBeInTheDocument();
    expect(screen.queryByText('Administrador')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).toBeInTheDocument();
    // No hay un «Editar perfil» genérico en la cabecera: cada dato se cambia en su fila de «Cuenta».
    expect(screen.queryByRole('button', { name: /editar/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /editar/i })).not.toBeInTheDocument();
  });

  it('un administrador ve el rol «Administrador» y el botón «Panel de administración», que lleva a /admin', async () => {
    renderProfile({ user: makeUser({ username: 'jefa', role: 'ADMIN' }) });

    await findName('jefa');
    expect(screen.getByText('Administrador')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Panel de administración' })).toHaveAttribute('href', '/admin');
    expect(screen.getByText('J')).toHaveAttribute('aria-hidden', 'true');
  });

  it('pone el título de la pestaña «Mi perfil — StreamBox»', async () => {
    renderProfile();
    await findName('ana');

    expect(document.title).toBe('Mi perfil — StreamBox');
  });

  it('«Cerrar sesión» dice «Cerrando sesión...» mientras el servidor contesta y cierra la sesión cuando confirma', async () => {
    let answer!: (response: Response) => void;
    const user = renderProfile({
      extra: { 'POST /api/auth/logout': () => new Promise<Response>((resolve) => (answer = resolve)) },
    });
    await findName('ana');

    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(screen.getByRole('button', { name: 'Cerrando sesión...' })).toHaveAttribute('aria-disabled', 'true');
    // Aún sin respuesta, la sesión (y con ella el perfil) sigue ahí.
    expect(screen.getByRole('heading', { level: 1, name: 'ana' })).toBeInTheDocument();
    answer(noContentResponse());
    // Sin sesión el perfil deja de pintarse (en la app real, la ruta protegida lleva al login).
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'ana' })).not.toBeInTheDocument());
    expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/auth/logout')).toHaveLength(1);
  });

  it('si el servidor falla también en el reintento, el perfil sigue con la sesión abierta y avisa', async () => {
    installManualTimers();
    const user = renderProfile({
      extra: { 'POST /api/auth/logout': () => errorResponse(500, 'INTERNAL_ERROR', 'x') },
      events: { delay: null },
    });
    await findName('ana');

    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));
    await act(() => vi.advanceTimersByTimeAsync(LOGOUT_RETRY_DELAY_MS));

    expect(await screen.findByText(LOGOUT_FAILED_SERVER)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'ana' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cerrar sesión' })).not.toHaveAttribute('aria-disabled');
  });
});

describe('ProfilePage: tarjeta «Cuenta»', () => {
  it('muestra nombre y correo reales, que el correo no se puede cambiar y la contraseña sin puntos ni pistas', async () => {
    renderProfile({ user: makeUser({ username: 'ana', email: 'ana@example.com' }) });
    await findName('ana');

    const account = section('Cuenta');
    expect(within(account).getByText('Nombre').nextElementSibling).toHaveTextContent('ana');
    const email = within(account).getByText('Correo').nextElementSibling;
    expect(email).toHaveTextContent('ana@example.com');
    expect(email).toHaveTextContent('No se puede cambiar: es el dato con el que inicias sesión.');
    const password = within(account).getByText('Contraseña').nextElementSibling;
    expect(password).toHaveTextContent('No se muestra por seguridad.');
    // Sin los puntos de antes, que parecían un campo editable.
    expect(password).not.toHaveTextContent('•');
    // Solo se pueden cambiar el nombre y la contraseña; el correo no tiene botón.
    expect(within(account).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual([
      'Cambiar nombre',
      'Cambiar contraseña',
    ]);
    expect(within(email as HTMLElement).queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('ProfilePage: estados del usuario', () => {
  it('mientras carga, avisa y no enseña ni datos ni «undefined»', () => {
    renderProfile({ extra: { [CURRENT_USER]: () => new Promise<Response>(() => {}) } });

    expect(screen.getByRole('heading', { level: 1, name: 'Mi perfil' })).toBeInTheDocument();
    expect(screen.getByText('Cargando tu perfil...')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Cuenta' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('undefined');
  });

  it('si falla, muestra el error y «Reintentar» vuelve a pedir el usuario', async () => {
    let attempts = 0;
    const user = renderProfile({
      extra: {
        [CURRENT_USER]: () => {
          attempts += 1;
          return attempts === 1 ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse(makeUser({ username: 'ana' }));
        },
      },
    });

    expect(await screen.findByRole('heading', { level: 2, name: 'No se pudo cargar tu perfil' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Mi perfil' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cerrar sesión' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    await findName('ana');
    expect(screen.queryByRole('heading', { name: 'No se pudo cargar tu perfil' })).not.toBeInTheDocument();
    expect(attempts).toBe(2);
  });
});

describe('ProfilePage: lista vacía', () => {
  it('las estadísticas valen 0 y «Tus géneros» explica cómo llenarla, con «Explorar películas» hacia /peliculas', async () => {
    renderProfile();
    await findName('ana');

    expect(valueOf('Películas en mi lista')).toHaveTextContent('0');
    expect(valueOf('Series en mi lista')).toHaveTextContent('0');
    expect(valueOf('Géneros distintos')).toHaveTextContent('0');
    const genres = section('Tus géneros');
    expect(within(genres).getByText(/Tu lista está vacía/)).toBeInTheDocument();
    expect(await within(genres).findByRole('link', { name: 'Explorar películas' })).toHaveAttribute('href', '/peliculas');
    // Un único estado vacío: la sección «De tu lista» no se repite.
    expect(screen.queryByRole('heading', { name: 'De tu lista' })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Ver toda mi lista' })).not.toBeInTheDocument();
  });

  it('si no hay películas en el catálogo no ofrece «Explorar películas» (llevaría a otra página vacía)', async () => {
    renderProfile({ extra: { [ANY_MOVIE]: () => jsonResponse(makePage([])) } });
    await findName('ana');
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('size=1'))).toBe(true));
    // Una vuelta del bucle de eventos para que la respuesta llegue y se pinte antes de comprobar la AUSENCIA.
    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 0));
    });

    const genres = section('Tus géneros');
    expect(within(genres).getByText(/Tu lista está vacía/)).toBeInTheDocument();
    expect(within(genres).queryByRole('link')).not.toBeInTheDocument();
  });
});

describe('ProfilePage: con la lista llena', () => {
  const dune = makeMovie({ id: 1, title: 'Dune', releaseYear: 2021, duration: 155, genres: [drama, scifi] });
  const arrival = makeMovie({ id: 2, title: 'Arrival', releaseYear: 2016, duration: 116, genres: [drama, scifi] });
  const dark = makeSeries({ id: 1, title: 'Dark', releaseYear: 2017, endYear: 2020, seasonCount: 3, genres: [drama, thriller] });

  it('cuenta películas, series y géneros distintos', async () => {
    renderProfile({ movies: [dune, arrival], series: [dark] });
    await findName('ana');

    await waitFor(() => expect(valueOf('Películas en mi lista')).toHaveTextContent('2'));
    expect(valueOf('Serie en mi lista')).toHaveTextContent('1');
    // Drama, Ciencia ficción y Thriller: 3 distintos aunque haya 6 etiquetas de género en total.
    expect(valueOf('Géneros distintos')).toHaveTextContent('3');
  });

  it('con una sola película, una serie y un género usa el singular', async () => {
    renderProfile({
      movies: [makeMovie({ id: 1, genres: [drama] })],
      series: [makeSeries({ id: 1, genres: [drama] })],
    });
    await findName('ana');

    expect(await screen.findByText('Película en mi lista')).toBeInTheDocument();
    expect(screen.getByText('Serie en mi lista')).toBeInTheDocument();
    expect(screen.getByText('Género distinto')).toBeInTheDocument();
  });

  it('«Tus géneros» ordena por frecuencia y luego por nombre, con el recuento accesible', async () => {
    renderProfile({ movies: [dune, arrival], series: [dark] });
    await findName('ana');

    const genres = await screen.findByRole('region', { name: 'Tus géneros' });
    // Drama 3; Ciencia ficción 2; Thriller 1.
    expect(within(genres).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Drama3, 3 títulos',
      'Ciencia ficción2, 2 títulos',
      'Thriller1, 1 título',
    ]);
    expect(within(genres).getByText('El número indica cuántos títulos de tu lista tienen cada género.')).toBeInTheDocument();
    // Con la lista llena ya no se pinta el estado vacío.
    expect(within(genres).queryByText(/Tu lista está vacía/)).not.toBeInTheDocument();
  });

  it('con más de 6 géneros enseña solo los 6 más frecuentes y lo dice', async () => {
    const many = Array.from({ length: 8 }, (_, index) => ({ id: 100 + index, name: `Género ${index + 1}` }));
    renderProfile({ movies: [makeMovie({ id: 1, genres: many })] });
    await findName('ana');

    const genres = await screen.findByRole('region', { name: 'Tus géneros' });
    expect(within(genres).getAllByRole('listitem')).toHaveLength(6);
    expect(within(genres).getByText('Los 6 más frecuentes de 8 géneros en tu lista.')).toBeInTheDocument();
    expect(valueOf('Géneros distintos')).toHaveTextContent('8');
  });

  it('«De tu lista» enseña las tarjetas de siempre y «Ver toda mi lista» lleva a /favorites', async () => {
    renderProfile({ movies: [dune, arrival], series: [dark] });
    await findName('ana');

    const preview = await screen.findByRole('region', { name: 'De tu lista' });
    expect(within(preview).getByRole('button', { name: /^Dune 2021 ·/ })).toBeInTheDocument();
    expect(within(preview).getByRole('button', { name: /^Arrival 2016 ·/ })).toBeInTheDocument();
    expect(within(preview).getByRole('link', { name: 'Dark 2017–2020 · 3 temporadas' })).toHaveAttribute('href', '/series/1');
    expect(within(preview).getByRole('link', { name: 'Ver toda mi lista' })).toHaveAttribute('href', '/favorites');
    expect(screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent)).toEqual([
      'Resumen de tu lista',
      'Cuenta',
      'Sesiones',
      'Tus géneros',
      'De tu lista',
    ]);
  });

  it('solo enseña cinco títulos, primero las películas', async () => {
    const movies = [1, 2, 3, 4, 5, 6].map((id) => makeMovie({ id, title: `Peli ${id}`, genres: [drama] }));
    renderProfile({ movies, series: [dark] });
    await findName('ana');

    const preview = await screen.findByRole('region', { name: 'De tu lista' });
    expect(within(preview).getAllByRole('button', { name: /\d{4} ·/ }).map((card) => card.textContent)).toEqual(
      [1, 2, 3, 4, 5].map((id) => expect.stringContaining(`Peli ${id}`)),
    );
    expect(within(preview).queryByText('Peli 6')).not.toBeInTheDocument();
    expect(within(preview).queryByRole('link', { name: /^Dark/ })).not.toBeInTheDocument();
    // El recuento de las estadísticas sí cuenta todo, no solo lo que se enseña.
    expect(valueOf('Películas en mi lista')).toHaveTextContent('6');
  });

  it('pulsar una película abre su diálogo de detalles', async () => {
    const user = renderProfile({ movies: [dune], series: [] });
    await findName('ana');

    await user.click(await screen.findByRole('button', { name: /^Dune 2021 ·/ }));

    expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
  });

  it('títulos sin ningún género: la tarjeta lo dice en lugar de quedarse en blanco', async () => {
    renderProfile({ movies: [makeMovie({ id: 1, genres: [] })] });
    await findName('ana');

    const genres = await screen.findByRole('region', { name: 'Tus géneros' });
    expect(within(genres).getByText('Los títulos de tu lista todavía no tienen géneros asignados.')).toBeInTheDocument();
    expect(within(genres).queryByRole('list')).not.toBeInTheDocument();
  });
});

describe('ProfilePage: estados de la lista', () => {
  it('mientras carga la lista, los datos de la cuenta ya se ven y «Tus géneros» avisa', async () => {
    renderProfile({ extra: { [MOVIES]: () => new Promise<Response>(() => {}) } });
    await findName('ana');

    expect(within(section('Cuenta')).getByText('ana@example.com')).toBeInTheDocument();
    expect(within(section('Tus géneros')).getByRole('status')).toHaveTextContent('Cargando tu lista...');
    // Sin lista no se inventan estadísticas (un «0» sería falso).
    expect(screen.queryByText('Películas en mi lista')).not.toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'De tu lista' })).not.toBeInTheDocument();
  });

  it('si falla la lista, solo falla esa zona (la cuenta sigue) y «Reintentar» la recarga', async () => {
    let attempts = 0;
    const user = renderProfile({
      extra: {
        [MOVIES]: () => {
          attempts += 1;
          return attempts === 1 ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse([makeMovie({ id: 1, genres: [drama] })]);
        },
      },
    });
    await findName('ana');

    expect(await screen.findByRole('heading', { level: 2, name: 'No se pudo cargar tu lista' })).toBeInTheDocument();
    expect(within(section('Cuenta')).getByText('ana@example.com')).toBeInTheDocument();
    expect(screen.queryByText('Películas en mi lista')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByText('Película en mi lista')).toBeInTheDocument();
    expect(valueOf('Película en mi lista')).toHaveTextContent('1');
    expect(screen.queryByRole('heading', { name: 'No se pudo cargar tu lista' })).not.toBeInTheDocument();
  });
});

/** Llamadas hechas a `url` con `method`. */
const callsTo = (url: string, method: string) =>
  fetchMock.mock.calls.filter(([u, init]) => String(u) === url && (init?.method ?? 'GET') === method);

/** Cuerpo JSON enviado en una llamada. */
const bodyOf = (call: Parameters<typeof fetch>) => JSON.parse(String(call[1]?.body)) as unknown;

/** La sesión sigue abierta: el perfil se pinta y nadie ha pedido cerrarla. */
function expectSessionStillOpen(name = 'ana') {
  expect(screen.getByRole('heading', { level: 1, name })).toBeInTheDocument();
  expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
  expect(screen.queryByText(/Tu sesión ha caducado/)).not.toBeInTheDocument();
}

type Events = ReturnType<typeof userEvent.setup>;

describe('ProfilePage: cambiar el nombre', () => {
  const PATCH_ME = 'PATCH /api/users/me';

  /** Abre el formulario, escribe `name` en lugar del actual y pulsa «Guardar». */
  async function changeName(user: Events, name: string) {
    await user.click(screen.getByRole('button', { name: 'Cambiar nombre' }));
    const input = screen.getByRole('textbox', { name: 'Nuevo nombre de usuario' });
    await user.clear(input);
    await user.type(input, name);
    await user.click(screen.getByRole('button', { name: 'Guardar' }));
    return input;
  }

  it('«Cambiar nombre» abre el campo con el nombre actual y el foco; al guardar, la cabecera cambia al instante y el foco vuelve', async () => {
    const user = renderProfile({
      extra: { [PATCH_ME]: () => jsonResponse(makeUser({ username: 'Ana Nueva' })) },
    });
    await findName('ana');

    await user.click(screen.getByRole('button', { name: 'Cambiar nombre' }));
    const input = screen.getByRole('textbox', { name: 'Nuevo nombre de usuario' });
    expect(input).toHaveValue('ana');
    expect(input).toHaveFocus();
    await user.clear(input);
    await user.type(input, '  Ana   Nueva ');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));

    await findName('Ana Nueva');
    expect(screen.getByText(usernameChangedMessage('Ana Nueva'))).toBeInTheDocument();
    // Solo `username`, normalizado como lo hará el servidor, y con la cabecera anti-CSRF.
    const [call] = callsTo('/api/users/me', 'PATCH');
    expect(bodyOf(call)).toEqual({ username: 'Ana Nueva' });
    expect(new Headers(call[1]?.headers).get('X-Requested-With')).toBe('StreamBox');
    // Se usa la respuesta: no se vuelve a pedir el usuario.
    expect(callsTo('/api/users/me', 'GET')).toHaveLength(1);
    expect(screen.queryByRole('textbox', { name: 'Nuevo nombre de usuario' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cambiar nombre' })).toHaveFocus();
  });

  it('409: «ya está en uso» junto al campo, con el foco en él; la sesión sigue abierta', async () => {
    const user = renderProfile({
      extra: {
        [PATCH_ME]: () => errorResponse(409, 'USER_ALREADY_EXISTS', 'El nombre de usuario ya está en uso'),
      },
    });
    await findName('ana');

    const input = await changeName(user, 'beto');

    expect(await screen.findByText('El nombre de usuario ya está en uso')).toHaveAttribute('role', 'alert');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('El nombre de usuario ya está en uso');
    expect(input).toHaveFocus();
    expectSessionStillOpen();
  });

  it('400 VALIDATION_ERROR: el mensaje del servidor va junto al campo y NO cierra la sesión', async () => {
    const message = 'El nombre de usuario debe tener entre 3 y 50 caracteres';
    const user = renderProfile({
      extra: {
        [PATCH_ME]: () =>
          errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', { validationErrors: { username: message } }),
      },
    });
    await findName('ana');

    const input = await changeName(user, 'beto');

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(input).toHaveFocus();
    expectSessionStillOpen();
  });

  it('valida en el cliente (sin petición) y, si el nombre no cambia, se cierra sin llamar al servidor', async () => {
    const user = renderProfile();
    await findName('ana');

    const input = await changeName(user, '  ab ');
    expect(screen.getByText('El nombre de usuario debe tener entre 3 y 50 caracteres.')).toBeInTheDocument();
    expect(input).toHaveFocus();

    await user.clear(input);
    await user.type(input, ' ana ');
    await user.click(screen.getByRole('button', { name: 'Guardar' }));
    expect(screen.queryByRole('textbox', { name: 'Nuevo nombre de usuario' })).not.toBeInTheDocument();
    expect(callsTo('/api/users/me', 'PATCH')).toHaveLength(0);
  });

  it('si el servidor falla, lo dice en el formulario, que sigue abierto para reintentar; «Cancelar» lo cierra y devuelve el foco', async () => {
    const user = renderProfile({ extra: { [PATCH_ME]: () => errorResponse(500, 'INTERNAL_ERROR', 'x') } });
    await findName('ana');

    await changeName(user, 'beto');

    expect(await screen.findByText(/El servidor ha tenido un problema/)).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('button', { name: 'Guardar' })).toBeEnabled();
    expectSessionStillOpen();

    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('textbox', { name: 'Nuevo nombre de usuario' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cambiar nombre' })).toHaveFocus();
  });
});

describe('ProfilePage: cambiar la contraseña', () => {
  const PUT_PASSWORD = 'PUT /api/users/me/password';
  const NEW = 'tres palabras al azar 2026';

  /** Abre el formulario y rellena los campos que no estén vacíos. */
  async function fillPasswordForm(user: Events, current = 'la-de-siempre', next = NEW, repeat = next) {
    await user.click(screen.getByRole('button', { name: 'Cambiar contraseña' }));
    if (current) await user.type(screen.getByLabelText('Contraseña actual'), current);
    if (next) await user.type(screen.getByLabelText('Nueva contraseña'), next);
    if (repeat) await user.type(screen.getByLabelText('Repite la nueva contraseña'), repeat);
  }

  /** El formulario (con nombre accesible) y su botón de enviar. */
  const passwordForm = () => screen.getByRole('form', { name: 'Cambiar contraseña' });
  const submitButton = () => within(passwordForm()).getAllByRole('button')[0];

  it('éxito: envía la actual y la nueva (no la repetición), avisa de las otras sesiones y esta sigue abierta', async () => {
    let answer!: (response: Response) => void;
    const user = renderProfile({
      extra: { [PUT_PASSWORD]: () => new Promise<Response>((resolve) => (answer = resolve)) },
    });
    await findName('ana');

    await fillPasswordForm(user);
    expect(screen.getByLabelText('Contraseña actual')).toHaveAttribute('autocomplete', 'current-password');
    expect(submitButton()).toHaveTextContent('Cambiar contraseña');
    await user.click(submitButton());

    expect(screen.getByRole('button', { name: 'Cambiando contraseña...' })).toBeDisabled();
    answer(noContentResponse());

    expect(await screen.findByText(PASSWORD_CHANGED)).toBeInTheDocument();
    expect(bodyOf(callsTo('/api/users/me/password', 'PUT')[0])).toEqual({
      currentPassword: 'la-de-siempre',
      newPassword: NEW,
    });
    expect(screen.queryByLabelText('Contraseña actual')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cambiar contraseña' })).toHaveFocus();
    expectSessionStillOpen();
  });

  it('valida en el cliente sin gastar intentos: actual vacía, nueva corta, repetición distinta; foco al primer error', async () => {
    const user = renderProfile();
    await findName('ana');

    await fillPasswordForm(user, '', 'corta', 'otra');
    await user.click(submitButton());

    expect(screen.getByText('Introduce tu contraseña actual.')).toBeInTheDocument();
    expect(screen.getByText('Las contraseñas no coinciden.')).toBeInTheDocument();
    expect(screen.getByLabelText('Contraseña actual')).toHaveFocus();
    expect(screen.getByLabelText('Nueva contraseña')).toHaveAccessibleDescription(
      'La contraseña debe tener entre 12 y 64 caracteres.',
    );
    expect(callsTo('/api/users/me/password', 'PUT')).toHaveLength(0);
  });

  it('400 CURRENT_PASSWORD_INCORRECT: el error va junto a «Contraseña actual», con el foco; la sesión NO se cierra', async () => {
    const message = 'La contraseña actual no es correcta';
    const user = renderProfile({
      extra: {
        [PUT_PASSWORD]: () =>
          errorResponse(400, 'CURRENT_PASSWORD_INCORRECT', message, { validationErrors: { currentPassword: message } }),
      },
    });
    await findName('ana');

    await fillPasswordForm(user);
    await user.click(submitButton());

    const current = screen.getByLabelText('Contraseña actual');
    await waitFor(() => expect(current).toHaveAccessibleDescription(message));
    expect(current).toHaveAttribute('aria-invalid', 'true');
    expect(current).toHaveFocus();
    expectSessionStillOpen();
  });

  it('400 VALIDATION_ERROR de la política: el motivo del servidor va junto a «Nueva contraseña»', async () => {
    const message = 'La contraseña no puede contener tu nombre de usuario';
    const user = renderProfile({
      extra: {
        [PUT_PASSWORD]: () =>
          errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', { validationErrors: { newPassword: message } }),
      },
    });
    await findName('ana');

    await fillPasswordForm(user);
    await user.click(submitButton());

    const next = screen.getByLabelText('Nueva contraseña');
    await waitFor(() => expect(next).toHaveAccessibleDescription(message));
    expect(next).toHaveFocus();
    expectSessionStillOpen();
  });

  describe('429 (demasiados intentos)', () => {
    /** Solo se falsean el intervalo y `Date` de la cuenta atrás; el resto sigue en tiempo real para `findBy*`. */
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    });

    it('avisa con la espera, bloquea el botón con la cuenta atrás y lo reactiva al terminar', async () => {
      const user = renderProfile({
        extra: {
          [PUT_PASSWORD]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '90' }),
        },
        events: { delay: null },
      });
      await findName('ana');

      await fillPasswordForm(user);
      await user.click(submitButton());

      expect(await screen.findByRole('button', { name: 'Reintentar en 1 min 30 s' })).toBeDisabled();
      expect(screen.getByText(passwordRateLimitMessage('1 min 30 s'))).toHaveAttribute('role', 'alert');

      act(() => {
        vi.advanceTimersByTime(30_000);
      });
      expect(screen.getByRole('button', { name: 'Reintentar en 1 min' })).toBeDisabled();

      act(() => {
        vi.advanceTimersByTime(60_000);
      });
      expect(submitButton()).toHaveTextContent('Cambiar contraseña');
      expect(submitButton()).toBeEnabled();
      expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
      expectSessionStillOpen();
    });
  });
});

describe('ProfilePage: cerrar sesión en todos los dispositivos', () => {
  const LOGOUT_ALL = 'POST /api/auth/logout-all';

  /** Pulsa el botón de la tarjeta «Sesiones» y devuelve el diálogo de confirmación. */
  async function openDialog(user: Events) {
    await user.click(screen.getByRole('button', { name: 'Cerrar sesión en todos los dispositivos' }));
    return screen.getByRole('alertdialog', { name: '¿Cerrar sesión en todos los dispositivos?' });
  }

  it('pide confirmación explicando qué pasa, con el foco en «Cancelar»; cancelar no llama al servidor', async () => {
    const user = renderProfile();
    await findName('ana');

    const dialog = await openDialog(user);
    expect(dialog).toHaveAccessibleDescription(
      'Se cerrará tu sesión aquí y en cualquier otro navegador o dispositivo donde hayas entrado, y tendrás que volver a iniciar sesión. En los demás dispositivos puede tardar hasta 15 minutos en cerrarse.',
    );
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus();

    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(callsTo('/api/auth/logout-all', 'POST')).toHaveLength(0);
  });

  it('204: «Cerrando sesiones...» mientras espera; después la sesión se cierra y se avisa', async () => {
    let answer!: (response: Response) => void;
    const user = renderProfile({
      extra: { [LOGOUT_ALL]: () => new Promise<Response>((resolve) => (answer = resolve)) },
    });
    await findName('ana');

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar todas las sesiones' }));
    expect(within(dialog).getByRole('button', { name: 'Cerrando sesiones...' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeDisabled();
    expect(screen.getByRole('heading', { level: 1, name: 'ana' })).toBeInTheDocument();

    answer(noContentResponse());

    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'ana' })).not.toBeInTheDocument());
    expect(await screen.findByText(LOGOUT_ALL_DONE)).toBeInTheDocument();
    const [call] = callsTo('/api/auth/logout-all', 'POST');
    expect(new Headers(call[1]?.headers).get('X-Requested-With')).toBe('StreamBox');
    // No hace falta además el logout normal: el servidor ya revocó todo y borró las cookies.
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
  });

  it('500: la sesión SIGUE abierta, el diálogo lo dice y el mismo botón reintenta', async () => {
    let fail = true;
    const user = renderProfile({
      extra: { [LOGOUT_ALL]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'x') : noContentResponse()) },
    });
    await findName('ana');

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar todas las sesiones' }));

    expect(await within(dialog).findByText(LOGOUT_ALL_FAILED_SERVER)).toHaveAttribute('role', 'alert');
    expectSessionStillOpen();
    expect(screen.queryByText(LOGOUT_ALL_DONE)).not.toBeInTheDocument();

    fail = false;
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar todas las sesiones' }));
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: 'ana' })).not.toBeInTheDocument());
    expect(callsTo('/api/auth/logout-all', 'POST')).toHaveLength(2);
  });

  it('un 502 del proxy (backend caído) dice que no llegó al servidor y que la sesión sigue abierta', async () => {
    const user = renderProfile({
      extra: { [LOGOUT_ALL]: () => new Response('<html>Bad Gateway</html>', { status: 502 }) },
    });
    await findName('ana');

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar todas las sesiones' }));

    expect(await within(dialog).findByText(LOGOUT_ALL_FAILED_NETWORK)).toBeInTheDocument();
    expectSessionStillOpen();
  });
});
