/**
 * Tests de `ProfilePage` («Mi perfil») con la sesión y la lista de favoritos reales
 * y `fetch` simulado.
 *
 * Protegen: la cabecera según el rol (el botón «Panel de administración» solo
 * para el administrador), la inicial del avatar y «Miembro desde», los datos de
 * la cuenta (con la contraseña sin revelar), los tres estados del usuario
 * (cargando, error con reintento, listo) y de la lista (cargando, error con
 * reintento, vacía, con datos), el recuento y el orden de los géneros, que solo
 * se enseñen cinco títulos de la lista, y que «Cerrar sesión» cierre la sesión.
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  CURRENT_USER,
  errorResponse,
  FAVORITE_SERIES,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  makeUser,
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

/** Opciones de {@link renderProfile}: el usuario, las listas y rutas extra de `fetch`. */
interface ProfileSetup {
  user?: User;
  movies?: Movie[];
  series?: Series[];
  extra?: Parameters<typeof routeFetch>[1];
}

/** Monta el perfil con sesión iniciada. Por defecto: usuario normal con la lista vacía y películas en el catálogo. */
function renderProfile({ user = makeUser(), movies = [], series = [], extra = {} }: ProfileSetup = {}) {
  routeFetch(fetchMock, {
    [CURRENT_USER]: () => jsonResponse(user),
    [MOVIES]: () => jsonResponse(movies),
    [FAVORITE_SERIES]: () => jsonResponse(series),
    [ANY_MOVIE]: () => jsonResponse(makePage([makeMovie({ id: 99 })])),
    ...extra,
  });
  const userEvents = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <ProfilePage />
    </FavoritesProvider>,
    { token: 'jwt', route: '/perfil' },
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
    // «Editar perfil» no existe: no se pinta un botón que no hace nada.
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

  it('«Cerrar sesión» cierra la sesión', async () => {
    const user = renderProfile();
    await findName('ana');

    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(localStorage.getItem('token')).toBeNull();
  });
});

describe('ProfilePage: tarjeta «Cuenta»', () => {
  it('muestra nombre y correo reales y la contraseña oculta, sin revelar nada', async () => {
    renderProfile({ user: makeUser({ username: 'ana', email: 'ana@example.com' }) });
    await findName('ana');

    const account = section('Cuenta');
    expect(within(account).getByText('Nombre').nextElementSibling).toHaveTextContent('ana');
    expect(within(account).getByText('Correo').nextElementSibling).toHaveTextContent('ana@example.com');
    const password = within(account).getByText('Contraseña').nextElementSibling;
    // A la vista, puntos fijos; a un lector de pantalla, «Oculta» (no «viñeta viñeta...» ocho veces).
    expect(password).toHaveTextContent('••••••••');
    expect(within(password as HTMLElement).getByText('Oculta')).toBeInTheDocument();
    expect(within(password as HTMLElement).getByText('••••••••')).toHaveAttribute('aria-hidden', 'true');
    // No hay forma de cambiarla desde aquí (no existe esa función): ningún enlace ni botón en la tarjeta.
    expect(within(account).queryByRole('link')).not.toBeInTheDocument();
    expect(within(account).queryByRole('button')).not.toBeInTheDocument();
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
