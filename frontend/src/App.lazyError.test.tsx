/**
 * Las rutas reales (`AppRoutes`) cuando NO llega el código de una pantalla que
 * se carga bajo demanda (revisión de `qa`, 2026-11): una pestaña abierta durante
 * un despliegue pide el trozo con el hash viejo (404) o se corta la red. Antes
 * de `LazyRouteBoundary`, React desmontaba toda la aplicación y la página se
 * quedaba en blanco.
 *
 * Archivo aparte porque `vi.mock` sustituye el módulo para TODO el archivo: aquí
 * el panel y el registro fallan siempre al importarse, como un trozo que no se
 * pudo descargar.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from './App';
import { RouteChangeFocus } from './components/RouteChangeFocus';
import { reloadPage } from './lib/lazyChunks';
import { CURRENT_USER, currentUserAs, jsonResponse, makeUser, renderWithProviders, routeFetch } from './test/helpers';

vi.mock('./pages/admin/adminPages', () => {
  throw new Error('404: /assets/adminPages-viejo.js');
});
vi.mock('./pages/RegisterPage', () => {
  throw new Error('404: /assets/RegisterPage-viejo.js');
});
// Con un poco de retraso, como una descarga real: así React llega a pintar la espera («Cargando...», con su propio
// <main>) antes del error, que es el caso en el que el foco podía perderse. Es otra pantalla y no el registro porque
// `React.lazy` guarda el resultado durante todo el archivo: tras el test del registro, ya fallaría sin espera.
vi.mock('./pages/ForgotPasswordPage', async () => {
  await new Promise((resolve) => setTimeout(resolve, 50));
  throw new Error('404: /assets/ForgotPasswordPage-viejo.js');
});
// La recarga real no existe en jsdom: se comprueba que el botón la pide.
vi.mock('./lib/lazyChunks', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./lib/lazyChunks')>()),
  reloadPage: vi.fn(),
}));

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  // React escribe en la consola el error que captura el límite (lo que se quiere en la app); aquí solo ensucia.
  vi.spyOn(console, 'error').mockImplementation(() => {});
  routeFetch(fetchMock, {
    [CURRENT_USER]: currentUserAs(makeUser({ role: 'ADMIN', username: 'jefa' })),
    'GET /api/users/me/favorites': () => jsonResponse([]),
  });
});

afterEach(() => {
  vi.mocked(reloadPage).mockClear();
  vi.mocked(console.error).mockRestore();
});

describe('Pantallas bajo demanda cuyo código no llega', () => {
  it('el panel (/admin) enseña «No se pudo cargar esta pantalla» con la barra a la vista, y no una página en blanco', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/admin/peliculas', session: true });

    const heading = await screen.findByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' });
    const main = screen.getByRole('main');
    expect(main).toContainElement(heading);
    expect(within(main).getByRole('alert')).toHaveTextContent(
      'Puede que se haya cortado la conexión o que StreamBox se haya actualizado mientras tenías la página abierta.',
    );
    // La barra sigue ahí: se puede ir a otra sección sin recargar.
    const nav = screen.getByRole('navigation', { name: 'Principal' });
    expect(within(nav).getByRole('link', { name: 'Inicio' })).toBeInTheDocument();
    expect(within(nav).getByRole('link', { name: 'Administrar' })).toHaveAttribute('aria-current', 'page');

    await user.click(screen.getByRole('button', { name: 'Recargar la página' }));
    expect(reloadPage).toHaveBeenCalledOnce();
  });

  it('una pantalla de cuenta (/registro) enseña el aviso dentro de su propio <main>, y no una página en blanco', async () => {
    const user = userEvent.setup();
    renderWithProviders(<AppRoutes />, { route: '/registro' });

    const heading = await screen.findByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' });
    const main = screen.getByRole('main');
    expect(main).toContainElement(heading);
    expect(within(main).getByRole('alert')).toHaveTextContent('Recarga la página para intentarlo de nuevo.');

    await user.click(screen.getByRole('button', { name: 'Recargar la página' }));
    expect(reloadPage).toHaveBeenCalledOnce();
  });

  it('al llegar navegando (login → «¿Has olvidado tu contraseña?»), el foco queda en el <main> del aviso, no en <body>', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <AppRoutes />
        <RouteChangeFocus />
      </>,
      { route: '/login' },
    );

    await user.click(await screen.findByRole('link', { name: '¿Has olvidado tu contraseña?' }));
    // Primero la espera, con el foco en su <main> (lo pone RouteChangeFocus)...
    expect(await screen.findByText('Cargando...')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveFocus();

    // ...y después el aviso, que trae otro <main>: el foco pasa a él.
    const heading = await screen.findByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' });
    const main = screen.getByRole('main');
    expect(main).toContainElement(heading);
    expect(main).toHaveFocus();
  });
});
