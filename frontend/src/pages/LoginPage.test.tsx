/**
 * Tests de `LoginPage`.
 *
 * Se consulta la pantalla como lo haría una persona o un lector de pantalla
 * (por etiqueta, rol y texto). Protegen que un 401 de login sea "credenciales
 * incorrectas" (y no cierre ninguna sesión), que un 429 bloquee el botón con
 * cuenta atrás en vez de dejar martillear al servidor, y que solo se guarde el
 * token si el servidor lo concede. `fetch` está simulado.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { errorResponse, jsonResponse, renderWithProviders, routeFetch } from '../test/helpers';
import { LoginPage } from './LoginPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

/** Rellena el formulario y pulsa "Iniciar sesión". */
async function fillAndSubmit(user: ReturnType<typeof userEvent.setup>, email = 'ana@example.com', password = 'secreta123') {
  await user.type(screen.getByLabelText('Correo electrónico'), email);
  await user.type(screen.getByLabelText('Contraseña'), password);
  await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
}

describe('LoginPage', () => {
  it('muestra el formulario accesible: título, campos con etiqueta y enlace al registro', () => {
    renderWithProviders(<LoginPage />, { route: '/login' });

    expect(screen.getByRole('heading', { level: 1, name: 'Bienvenido de nuevo' })).toBeInTheDocument();
    expect(screen.getByLabelText('Correo electrónico')).toHaveAttribute('type', 'email');
    expect(screen.getByLabelText('Contraseña')).toHaveAttribute('type', 'password');
    expect(screen.getByRole('link', { name: 'Regístrate' })).toHaveAttribute('href', '/registro');
    expect(document.title).toBe('Iniciar sesión — StreamBox');
  });

  it('con campos vacíos avisa y no llama al servidor', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));

    expect(screen.getByText('Introduce tu correo electrónico y tu contraseña.')).toHaveAttribute('role', 'alert');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('un login correcto envía el correo recortado SIN token y guarda el token recibido', async () => {
    routeFetch(fetchMock, { 'POST /api/auth/login': () => jsonResponse({ token: 'jwt-nuevo' }) });
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user, '  ana@example.com  ');

    await vi.waitFor(() => expect(localStorage.getItem('token')).toBe('jwt-nuevo'));
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/auth/login');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@example.com', password: 'secreta123' });
    expect(new Headers(init?.headers).has('Authorization')).toBe(false);
  });

  it('tras un login correcto la sesión pide quién es el usuario (/users/me) con el token NUEVO', async () => {
    routeFetch(fetchMock, { 'POST /api/auth/login': () => jsonResponse({ token: 'jwt-nuevo' }) });
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user);

    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/users/me'));
    const me = fetchMock.mock.calls.find(([url]) => url === '/api/users/me');
    expect(new Headers(me?.[1]?.headers).get('Authorization')).toBe('Bearer jwt-nuevo');
  });

  it('mientras espera la respuesta bloquea el botón (evita enviar dos veces)', async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user);

    expect(await screen.findByRole('button', { name: 'Entrando...' })).toBeDisabled();
  });

  it('credenciales incorrectas (401): mensaje genérico en un alert, sin guardar nada', async () => {
    // El texto del servidor no se muestra tal cual: la pantalla decide el suyo por `status`.
    fetchMock.mockImplementation(async () => errorResponse(401, 'BAD_CREDENTIALS', 'Texto interno del servidor'));
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user);

    expect(await screen.findByText('Correo o contraseña incorrectos.')).toHaveAttribute('role', 'alert');
    expect(screen.queryByText('Texto interno del servidor')).not.toBeInTheDocument();
    expect(localStorage.getItem('token')).toBeNull();
    // Sigue pudiendo reintentar.
    expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
  });

  describe('intentos restantes (401 con remainingAttempts)', () => {
    it('en plural: avisa de cuántos intentos quedan DENTRO del mismo aviso de error', async () => {
      fetchMock.mockImplementation(async () =>
        errorResponse(401, 'INVALID_CREDENTIALS', 'Email o contraseña incorrectos', { remainingAttempts: 3 }),
      );
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      const notice = await screen.findByText('Te quedan 3 intentos antes de que la cuenta se bloquee 15 minutos.');
      // Un solo aviso (role="alert") con el error y los intentos: se anuncian juntos.
      expect(notice.closest('[role="alert"]')).toHaveTextContent('Correo o contraseña incorrectos.');
      expect(notice.querySelector('svg')).toBeNull();
      expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
    });

    it('en singular y más visible con el último intento (icono y texto destacado)', async () => {
      fetchMock.mockImplementation(async () =>
        errorResponse(401, 'INVALID_CREDENTIALS', 'Email o contraseña incorrectos', { remainingAttempts: 1 }),
      );
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      const notice = await screen.findByText('Te queda 1 intento antes de que la cuenta se bloquee 15 minutos.');
      expect(notice.closest('[role="alert"]')).not.toBeNull();
      // No depende solo del color: lleva un icono (decorativo, oculto a los lectores) y va en negrita.
      expect(notice.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
      expect(notice).toHaveClass('font-semibold');
    });

    it('sin remainingAttempts (o con un valor que no es un entero ≥ 1) no inventa ningún aviso', async () => {
      fetchMock.mockImplementation(async () =>
        errorResponse(401, 'INVALID_CREDENTIALS', 'Email o contraseña incorrectos', { remainingAttempts: 0 }),
      );
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      expect(await screen.findByText('Correo o contraseña incorrectos.')).toBeInTheDocument();
      expect(screen.queryByText(/intentos? antes de que la cuenta se bloquee/)).not.toBeInTheDocument();
    });

    it('al volver a enviar, el aviso de intentos anterior desaparece', async () => {
      fetchMock.mockImplementationOnce(async () =>
        errorResponse(401, 'INVALID_CREDENTIALS', 'x', { remainingAttempts: 2 }),
      );
      fetchMock.mockImplementationOnce(() => new Promise<Response>(() => {}));
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);
      expect(await screen.findByText(/Te quedan 2 intentos/)).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
      expect(screen.queryByText(/Te quedan 2 intentos/)).not.toBeInTheDocument();
    });
  });

  it('red caída: muestra el mensaje de conexión', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user);

    expect(await screen.findByText(/No se pudo conectar con el servidor/)).toHaveAttribute('role', 'alert');
  });

  describe('429 (demasiados intentos)', () => {
    /** Solo se falsean el intervalo y `Date` de la cuenta atrás; el resto sigue en tiempo real para `findBy*`. */
    beforeEach(() => {
      vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    });

    it('deshabilita el botón con cuenta atrás y lo reactiva al terminar', async () => {
      fetchMock.mockImplementation(async () =>
        errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
      );
      const user = userEvent.setup({ delay: null });
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      const blocked = await screen.findByRole('button', { name: 'Reintentar en 30 s' });
      expect(blocked).toBeDisabled();
      expect(screen.getByText('Demasiados intentos. Inténtalo de nuevo en 30 s.')).toHaveAttribute('role', 'alert');

      act(() => {
        vi.advanceTimersByTime(10_000);
      });
      expect(screen.getByRole('button', { name: 'Reintentar en 20 s' })).toBeDisabled();

      act(() => {
        vi.advanceTimersByTime(20_000);
      });
      expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
      // Al acabar la espera desaparece el aviso de "demasiados intentos".
      expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
    });

    it('el límite por IP (RATE_LIMIT_EXCEEDED) NO se presenta como cuenta bloqueada', async () => {
      fetchMock.mockImplementation(async () =>
        errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
      );
      const user = userEvent.setup({ delay: null });
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      expect(await screen.findByText('Demasiados intentos. Inténtalo de nuevo en 30 s.')).toBeInTheDocument();
      expect(screen.queryByText(/bloqueada/)).not.toBeInTheDocument();
    });

    it('cuenta bloqueada (ACCOUNT_LOCKED): mensaje propio y cuenta atrás legible en minutos y segundos', async () => {
      fetchMock.mockImplementation(async () =>
        errorResponse(429, 'ACCOUNT_LOCKED', 'Texto interno del servidor', {}, { 'Retry-After': '900' }),
      );
      const user = userEvent.setup({ delay: null });
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      const message =
        'Tu cuenta está bloqueada temporalmente por demasiados intentos fallidos. Podrás volver a intentarlo en 15 min.';
      expect(await screen.findByText(message)).toHaveAttribute('role', 'alert');
      expect(screen.queryByText(/Demasiados intentos\./)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Reintentar en 15 min' })).toBeDisabled();

      act(() => {
        vi.advanceTimersByTime(1_000);
      });
      expect(screen.getByRole('button', { name: 'Reintentar en 14 min 59 s' })).toBeDisabled();

      act(() => {
        vi.advanceTimersByTime(834_000); // quedan 65 s
      });
      expect(screen.getByRole('button', { name: 'Reintentar en 1 min 5 s' })).toBeDisabled();
      // El aviso es estático: no se reescribe con cada segundo (se volvería a leer entero).
      expect(screen.getByText(message)).toBeInTheDocument();

      act(() => {
        vi.advanceTimersByTime(65_000);
      });
      expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
      expect(screen.queryByText(/bloqueada/)).not.toBeInTheDocument();
    });

    it('cuenta bloqueada sin Retry-After: lo explica sin inventar una espera', async () => {
      fetchMock.mockImplementation(async () => errorResponse(429, 'ACCOUNT_LOCKED', 'x'));
      const user = userEvent.setup({ delay: null });
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      expect(
        await screen.findByText(
          'Tu cuenta está bloqueada temporalmente por demasiados intentos fallidos. Inténtalo de nuevo más tarde.',
        ),
      ).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
    });

    it('sin cabecera Retry-After avisa pero no inventa una cuenta atrás', async () => {
      fetchMock.mockImplementation(async () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x'));
      const user = userEvent.setup({ delay: null });
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      expect(await screen.findByText(/Inténtalo de nuevo en unos instantes/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled();
    });
  });
});
