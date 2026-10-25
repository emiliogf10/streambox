/**
 * Tests de `ForgotPasswordPage` (`/recuperar-contrasena`, «He olvidado mi contraseña»).
 *
 * Protegen lo que hace segura y usable la petición del enlace: el correo puede
 * venir del login en el `state` de la navegación (nunca en la URL); se valida en
 * el cliente; tras el 202 la pantalla «Revisa tu correo» no afirma que la cuenta
 * exista y ofrece reenviar (con la espera de un minuto), corregir el correo e
 * ir al login; los 400, 429 y fallos de red se explican y dejan reintentar. Se
 * monta con las rutas reales (`AppRoutes`) para comprobar también que es pública
 * y no redirige a nadie. `fetch` está simulado.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { RESET_REQUEST_DONE_MESSAGE } from '../lib/passwordReset';
import { apiCalls, errorResponse, jsonResponse, renderWithProviders, routeFetch } from '../test/helpers';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

const REQUEST = 'POST /api/auth/password-reset';
const accepted = () => jsonResponse({ message: 'Texto fijo del servidor' }, 202);

/** Monta las rutas reales en `/recuperar-contrasena`, con el estado de navegación indicado. */
function renderForgot(options: { state?: unknown; session?: boolean } = {}) {
  return renderWithProviders(<AppRoutes />, {
    route: '/recuperar-contrasena',
    routeState: options.state,
    session: options.session,
  });
}

const emailField = () => screen.getByLabelText('Correo electrónico');
const submitButton = () => screen.getByRole('button', { name: 'Enviar el enlace' });
const requestBodies = () =>
  apiCalls(fetchMock)
    .filter(([url]) => url === '/api/auth/password-reset')
    .map(([, init]) => JSON.parse(String(init?.body)) as unknown);

/** Rellena el correo y envía el formulario. */
async function submitEmail(user: ReturnType<typeof userEvent.setup>, email: string) {
  await user.clear(emailField());
  await user.type(emailField(), email);
  await user.click(submitButton());
}

describe('ForgotPasswordPage: el formulario', () => {
  it('muestra el título, el campo con etiqueta y la vuelta al login, sin llamar al servidor', async () => {
    routeFetch(fetchMock, {});
    renderForgot();

    expect(screen.getByRole('heading', { level: 1, name: '¿Has olvidado tu contraseña?' })).toBeInTheDocument();
    expect(
      screen.getByText('Escribe el correo de tu cuenta y te llegará un enlace para elegir una nueva.'),
    ).toBeInTheDocument();
    expect(emailField()).toHaveAttribute('type', 'email');
    expect(emailField()).toHaveValue('');
    expect(screen.getByRole('link', { name: 'Inicia sesión' })).toHaveAttribute('href', '/login');
    expect(document.title).toBe('Recuperar la contraseña — StreamBox');
    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/refresh'));
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('rellena el correo que llega en el estado de la navegación (desde el login)', () => {
    routeFetch(fetchMock, {});
    renderForgot({ state: { email: 'ana@example.com' } });

    expect(emailField()).toHaveValue('ana@example.com');
  });

  it('ignora un estado de navegación que no es un correo válido', () => {
    routeFetch(fetchMock, {});
    renderForgot({ state: { email: '<no-es-un-correo>' } });

    expect(emailField()).toHaveValue('');
  });

  it('valida en el cliente: sin correo o mal formado no se envía y el foco va al campo', async () => {
    routeFetch(fetchMock, {});
    const user = userEvent.setup();
    renderForgot();

    await user.click(submitButton());
    expect(emailField()).toHaveAttribute('aria-invalid', 'true');
    expect(emailField()).toHaveAccessibleDescription('Introduce tu correo electrónico.');
    expect(emailField()).toHaveFocus();

    await submitEmail(user, 'ana.example.com');
    expect(emailField()).toHaveAccessibleDescription('Introduce un correo electrónico válido.');
    expect(requestBodies()).toEqual([]);
  });

  it('400 VALIDATION_ERROR: el motivo del servidor junto al campo', async () => {
    routeFetch(fetchMock, {
      [REQUEST]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
          validationErrors: { email: 'El email no tiene un formato válido.' },
        }),
    });
    const user = userEvent.setup();
    renderForgot();

    await submitEmail(user, 'ana@example');

    expect(await screen.findByText('El email no tiene un formato válido.')).toHaveAttribute('role', 'alert');
    expect(emailField()).toHaveAttribute('aria-invalid', 'true');
    expect(emailField()).toHaveFocus();
    expect(screen.getByRole('heading', { level: 1, name: '¿Has olvidado tu contraseña?' })).toBeInTheDocument();
  });

  it('429: avisa y bloquea el botón con la cuenta atrás de Retry-After', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    routeFetch(fetchMock, {
      [REQUEST]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
    });
    const user = userEvent.setup({ delay: null });
    renderForgot();

    await submitEmail(user, 'ana@example.com');

    expect(await screen.findByRole('button', { name: 'Reintentar en 30 s' })).toBeDisabled();
    expect(
      screen.getByText('Has pedido demasiados correos seguidos. Podrás pedir otro en 30 s.'),
    ).toHaveAttribute('role', 'alert');
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(submitButton()).toBeEnabled();
    expect(screen.queryByText(/demasiados correos/)).not.toBeInTheDocument();
  });

  it('red caída: lo dice y el mismo botón reintenta', async () => {
    let calls = 0;
    routeFetch(fetchMock, {
      [REQUEST]: () => {
        calls += 1;
        if (calls === 1) throw new TypeError('Failed to fetch');
        return accepted();
      },
    });
    const user = userEvent.setup();
    renderForgot();

    await submitEmail(user, 'ana@example.com');
    expect(await screen.findByText(/No se pudo conectar con el servidor/)).toHaveAttribute('role', 'alert');

    await user.click(submitButton());

    expect(await screen.findByRole('heading', { level: 1, name: 'Revisa tu correo' })).toBeInTheDocument();
    expect(requestBodies()).toEqual([{ email: 'ana@example.com' }, { email: 'ana@example.com' }]);
  });
});

describe('ForgotPasswordPage: «Revisa tu correo» (202)', () => {
  it('no afirma que la cuenta exista, explica el enlace y ofrece las salidas', async () => {
    routeFetch(fetchMock, { [REQUEST]: accepted });
    const user = userEvent.setup();
    renderForgot();

    await submitEmail(user, '  ana@example.com ');

    const heading = await screen.findByRole('heading', { level: 1, name: 'Revisa tu correo' });
    expect(heading).toHaveFocus();
    expect(document.title).toBe('Revisa tu correo — StreamBox');
    // Lo que se envía: el correo recortado, a la ruta pública, con la cabecera anti-CSRF.
    const [[url, init]] = apiCalls(fetchMock);
    expect(url).toBe('/api/auth/password-reset');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@example.com' });
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');

    const main = screen.getByRole('main');
    expect(main).toHaveTextContent(
      'Si hay una cuenta con ana@example.com, en unos minutos te llegará un correo con un enlace para elegir una ' +
        'contraseña nueva. El enlace caduca en 1 hora y solo sirve una vez.',
    );
    expect(main).toHaveTextContent('Si ya habías pedido varios hace poco, puede que no llegue otro: usa el del correo más reciente.');
    expect(main).toHaveTextContent(
      'Si aún no habías confirmado tu correo, al elegir la contraseña nueva quedará confirmado y podrás entrar.',
    );
    expect(main).toHaveTextContent('Si no tienes ninguna cuenta con ella, no te llegará nada: puedes crear una cuenta.');
    expect(main).not.toHaveTextContent(/te hemos enviado/i);
    // El texto del 202 del servidor no se enseña: la pantalla pone el suyo.
    expect(screen.queryByText('Texto fijo del servidor')).not.toBeInTheDocument();

    expect(screen.getByRole('button', { name: /^Reenviar en / })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Corregir el correo' })).toBeEnabled();
    expect(screen.getByRole('link', { name: 'crear una cuenta' })).toHaveAttribute('href', '/registro');
    expect(screen.getByRole('link', { name: 'Inicia sesión' })).toHaveAttribute('href', '/login');
  });

  it('«Reenviar el enlace»: en espera el primer minuto; después pide otro y avisa sin afirmar nada', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    routeFetch(fetchMock, { [REQUEST]: accepted });
    const user = userEvent.setup({ delay: null });
    renderForgot();
    await submitEmail(user, 'ana@example.com');
    expect(await screen.findByRole('button', { name: 'Reenviar en 1 min' })).toBeDisabled();

    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    await user.click(screen.getByRole('button', { name: 'Reenviar el enlace' }));

    const done = await screen.findByText(RESET_REQUEST_DONE_MESSAGE('ana@example.com'));
    expect(done.closest('[role="status"]')).not.toBeNull();
    expect(requestBodies()).toEqual([{ email: 'ana@example.com' }, { email: 'ana@example.com' }]);
    expect(screen.getByRole('button', { name: 'Reenviar en 1 min' })).toBeDisabled();
  });

  it('«Reenviar el enlace» con 429: avisa de la espera y bloquea el botón', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    let calls = 0;
    routeFetch(fetchMock, {
      [REQUEST]: () => {
        calls += 1;
        return calls === 1 ? accepted() : errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '120' });
      },
    });
    const user = userEvent.setup({ delay: null });
    renderForgot();
    await submitEmail(user, 'ana@example.com');
    await screen.findByRole('heading', { name: 'Revisa tu correo' });
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    await user.click(screen.getByRole('button', { name: 'Reenviar el enlace' }));

    expect(
      await screen.findByText('Has pedido demasiados correos seguidos. Podrás pedir otro en 2 min.'),
    ).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('button', { name: 'Reenviar en 2 min' })).toBeDisabled();
  });

  it('«Corregir el correo» vuelve al formulario con lo escrito y el foco en el campo', async () => {
    routeFetch(fetchMock, { [REQUEST]: accepted });
    const user = userEvent.setup();
    renderForgot();
    await submitEmail(user, 'ana@exampel.com');
    await screen.findByRole('heading', { name: 'Revisa tu correo' });

    await user.click(screen.getByRole('button', { name: 'Corregir el correo' }));

    expect(screen.getByRole('heading', { level: 1, name: '¿Has olvidado tu contraseña?' })).toBeInTheDocument();
    expect(emailField()).toHaveValue('ana@exampel.com');
    expect(emailField()).toHaveFocus();
    expect(document.title).toBe('Recuperar la contraseña — StreamBox');

    await submitEmail(user, 'ana@example.com');
    expect(await screen.findByRole('main')).toHaveTextContent('Si hay una cuenta con ana@example.com,');
    expect(requestBodies()).toEqual([{ email: 'ana@exampel.com' }, { email: 'ana@example.com' }]);
  });
});

describe('ForgotPasswordPage: es pública y funciona con o sin sesión', () => {
  it('con sesión abierta no redirige y el pie lleva al inicio (el login redirigiría allí)', async () => {
    routeFetch(fetchMock, { [REQUEST]: accepted });
    const user = userEvent.setup();
    renderForgot({ session: true });

    expect(await screen.findByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('link', { name: 'Inicia sesión' })).not.toBeInTheDocument();
    await submitEmail(user, 'ana@example.com');

    await screen.findByRole('heading', { name: 'Revisa tu correo' });
    expect(screen.getByText('ruta:/recuperar-contrasena')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    // Con sesión no tiene sentido «crear una cuenta».
    expect(screen.queryByRole('link', { name: 'crear una cuenta' })).not.toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveTextContent('Si no tienes ninguna cuenta con ella, no te llegará nada.');
  });
});
