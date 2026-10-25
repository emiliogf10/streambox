/**
 * Tests de `ResetPasswordPage` (`/restablecer-contrasena#token=…`), la página a
 * la que lleva el enlace del correo «Restablece tu contraseña de StreamBox».
 *
 * Protegen lo que hace segura y usable la recuperación: el token se lee del
 * fragmento y se quita de la URL; un enlace nuevo con la página abierta vale
 * como enlace nuevo; las contraseñas se comprueban en el cliente antes de
 * gastar una petición; 204 lleva a «Contraseña cambiada» y, si había una sesión
 * en este navegador, la cierra sin el aviso de «sesión caducada»; 400
 * `INVALID_RESET_TOKEN` (o un enlace sin token) a «enlace no válido» con la
 * forma de pedir otro; 400 `VALIDATION_ERROR` deja el error junto al campo SIN
 * perder el token; y los fallos pasajeros (red, 429) dejan reintentar. Se monta
 * con las rutas reales (`AppRoutes`) para comprobar que es pública y no
 * redirige a nadie. `fetch` está simulado.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation, useNavigate, useNavigationType } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { LOGOUT_FAILED_OTHER } from '../lib/logout';
import { RESET_REQUEST_DONE_MESSAGE, RESET_SESSIONS_CLOSED } from '../lib/passwordReset';
import {
  apiCalls,
  errorResponse,
  jsonResponse,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

const CONFIRM = 'POST /api/auth/password-reset/confirm';
const REQUEST = 'POST /api/auth/password-reset';
const LOGOUT = 'POST /api/auth/logout';
/** Un token con el formato de los del backend (32 bytes en Base64URL). */
const TOKEN = 'dGhpcy1pcy1hLXRlc3QtdG9rZW4tb2YtMzItYnl0ZXM';
/** Otro token válido: el del enlace nuevo que se abre con la página montada. */
const NEW_TOKEN = 'b3Ryby10b2tlbi1kZS0zMi1ieXRlcy1wYXJhLWVsLXRlc3Q';
const PASSWORD = 'una-frase-larga-y-segura';

/** Enseña el fragmento y el tipo de la última navegación, para comprobar que el token sale de la URL. */
function HashProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return <p>{`hash:${location.hash} navegación:${navigationType}`}</p>;
}

/** Abre otro enlace con la página ya montada (solo cambia el fragmento; el navegador no recarga). */
function OpenLinkProbe() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(`/restablecer-contrasena#token=${NEW_TOKEN}`)}>
      Abrir el enlace nuevo
    </button>
  );
}

/** Monta las rutas reales en `/restablecer-contrasena` con el fragmento indicado. */
function renderReset(hash: string, options: { session?: boolean } = {}) {
  return renderWithProviders(
    <>
      <AppRoutes />
      <HashProbe />
      <OpenLinkProbe />
      <UserStatusProbe />
    </>,
    { route: `/restablecer-contrasena${hash}`, session: options.session },
  );
}

const newPasswordField = () => screen.getByLabelText('Contraseña nueva');
const confirmField = () => screen.getByLabelText('Repite la contraseña nueva');
const submitButton = () => screen.getByRole('button', { name: 'Cambiar la contraseña' });
const confirmBodies = () =>
  apiCalls(fetchMock)
    .filter(([url]) => url === '/api/auth/password-reset/confirm')
    .map(([, init]) => JSON.parse(String(init?.body)) as { token: string; newPassword: string });
const logoutCalls = () => apiCalls(fetchMock).filter(([url]) => url === '/api/auth/logout');

/** Escribe la contraseña nueva (y su repetición) y envía. */
async function submitPasswords(
  user: ReturnType<typeof userEvent.setup>,
  password = PASSWORD,
  repeated = password,
) {
  await user.clear(newPasswordField());
  await user.clear(confirmField());
  if (password) await user.type(newPasswordField(), password);
  if (repeated) await user.type(confirmField(), repeated);
  await user.click(submitButton());
}

describe('ResetPasswordPage: con un token en el enlace', () => {
  it('muestra el formulario con las reglas y el autocompletado de contraseña nueva, sin llamar al servidor', async () => {
    routeFetch(fetchMock, {});
    renderReset(`#token=${TOKEN}`);

    expect(screen.getByRole('heading', { level: 1, name: 'Elige una contraseña nueva' })).toBeInTheDocument();
    expect(screen.getByText('Escríbela dos veces. Después podrás iniciar sesión con ella.')).toBeInTheDocument();
    expect(newPasswordField()).toHaveAttribute('type', 'password');
    expect(newPasswordField()).toHaveAttribute('autocomplete', 'new-password');
    expect(confirmField()).toHaveAttribute('autocomplete', 'new-password');
    expect(newPasswordField()).toHaveAccessibleDescription(
      'Entre 12 y 64 caracteres. Evita contraseñas comunes y no incluyas tu usuario ni tu correo.',
    );
    expect(
      screen.getByText(
        'Al cambiarla se cerrarán todas las sesiones abiertas con tu cuenta, también en otros dispositivos. Si aún no habías confirmado tu correo, quedará confirmado.',
      ),
    ).toBeInTheDocument();
    expect(document.title).toBe('Restablecer la contraseña — StreamBox');
    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/refresh'));
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('quita el token de la barra de direcciones (sustituyendo la entrada del historial) y lo conserva en memoria', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    expect(await screen.findByText('hash: navegación:REPLACE')).toBeInTheDocument();
    expect(screen.getByText('ruta:/restablecer-contrasena')).toBeInTheDocument();

    await submitPasswords(user);

    await screen.findByRole('heading', { name: 'Contraseña cambiada' });
    expect(confirmBodies()).toEqual([{ token: TOKEN, newPassword: PASSWORD }]);
  });

  it('valida en el cliente: longitud y que coincidan, sin llamar al servidor y con el foco en el primer error', async () => {
    routeFetch(fetchMock, {});
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    await user.click(submitButton());
    expect(newPasswordField()).toHaveAccessibleDescription('La contraseña debe tener entre 12 y 64 caracteres.');
    expect(confirmField()).toHaveAccessibleDescription('Repite la contraseña nueva.');
    expect(newPasswordField()).toHaveFocus();

    await submitPasswords(user, PASSWORD, `${PASSWORD}x`);
    expect(newPasswordField()).not.toHaveAttribute('aria-invalid');
    expect(confirmField()).toHaveAccessibleDescription('Las contraseñas no coinciden.');
    expect(confirmField()).toHaveFocus();
    expect(confirmBodies()).toEqual([]);
  });

  it('204 sin sesión: «Contraseña cambiada», con el foco en el título y el camino a «Iniciar sesión»', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);
    await screen.findByText('estado:idle');

    await submitPasswords(user);

    const heading = await screen.findByRole('heading', { level: 1, name: 'Contraseña cambiada' });
    expect(heading).toHaveFocus();
    expect(document.title).toBe('Contraseña cambiada — StreamBox');
    expect(screen.getByText('Ya puedes iniciar sesión con tu contraseña nueva.')).toBeInTheDocument();
    expect(screen.getByText(RESET_SESSIONS_CLOSED)).toBeInTheDocument();
    expect(screen.queryByText(/este navegador/)).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    // Sin sesión no hay nada que cerrar.
    expect(logoutCalls()).toEqual([]);
    const [[url, init]] = apiCalls(fetchMock);
    expect(url).toBe('/api/auth/password-reset/confirm');
    expect(init?.method).toBe('POST');
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
  });

  it('204 con sesión en este navegador: la cierra en silencio (sin «sesión caducada») y lleva al login', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse(), [LOGOUT]: () => noContentResponse() });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`, { session: true });
    await screen.findByText('estado:ready');

    await submitPasswords(user);

    await screen.findByRole('heading', { level: 1, name: 'Contraseña cambiada' });
    expect(
      await screen.findByText('También se ha cerrado la sesión que había abierta en este navegador.'),
    ).toBeInTheDocument();
    expect(screen.getByText('estado:idle')).toBeInTheDocument();
    expect(logoutCalls()).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(screen.queryByText(/Tu sesión ha caducado/)).not.toBeInTheDocument();
    // Sigue en la página (no la ha echado ninguna guarda).
    expect(screen.getByText('ruta:/restablecer-contrasena')).toBeInTheDocument();
  });

  it('204 con sesión y el cierre falla: lo dice y «Cerrar sesión» lo reintenta', async () => {
    let logouts = 0;
    routeFetch(fetchMock, {
      [CONFIRM]: () => noContentResponse(),
      [LOGOUT]: () => {
        logouts += 1;
        return logouts === 1 ? errorResponse(403, 'CSRF_REJECTED', 'x') : noContentResponse();
      },
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`, { session: true });
    await screen.findByText('estado:ready');

    await submitPasswords(user);

    expect(
      await screen.findByText(
        'No se ha podido cerrar la sesión abierta en este navegador. Ciérrala antes de entrar con tu contraseña nueva.',
      ),
    ).toHaveAttribute('role', 'alert');
    // El aviso general del cierre fallido (de `AuthContext`) también sale.
    expect(screen.getByText(LOGOUT_FAILED_OTHER)).toBeInTheDocument();
    // Con la sesión aún abierta, «Iniciar sesión» llevaría al inicio: no se ofrece.
    expect(screen.queryByRole('link', { name: 'Iniciar sesión' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(
      await screen.findByText('También se ha cerrado la sesión que había abierta en este navegador.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(logoutCalls()).toHaveLength(2);
  });

  it('mientras cambia la contraseña, el botón queda bloqueado (no se envía dos veces)', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => new Promise<Response>(() => {}) });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    await submitPasswords(user);

    expect(await screen.findByRole('button', { name: 'Cambiando la contraseña...' })).toBeDisabled();
  });

  it('400 INVALID_RESET_TOKEN: «El enlace no es válido o ha caducado», con el foco en el título y la forma de pedir otro', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: () => errorResponse(400, 'INVALID_RESET_TOKEN', 'Texto interno del servidor'),
      [REQUEST]: () => jsonResponse({ message: 'x' }, 202),
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    await submitPasswords(user);

    const heading = await screen.findByRole('heading', { level: 1, name: 'El enlace no es válido o ha caducado' });
    expect(heading).toHaveFocus();
    expect(screen.getByText('Cada enlace sirve una sola vez y caduca en 1 hora.')).toBeInTheDocument();
    expect(screen.getByText(/Si ya cambiaste la contraseña con este enlace, no tienes que hacer nada más/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Inicia sesión' })).toHaveAttribute('href', '/login');
    expect(screen.queryByText('Texto interno del servidor')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('Correo electrónico'), ' ana@example.com ');
    await user.click(screen.getByRole('button', { name: 'Enviar un enlace nuevo' }));

    const done = await screen.findByText(RESET_REQUEST_DONE_MESSAGE('ana@example.com'));
    expect(done.closest('[role="status"]')).not.toBeNull();
    const requests = apiCalls(fetchMock).filter(([url]) => url === '/api/auth/password-reset');
    expect(requests.map(([, init]) => JSON.parse(String(init?.body)))).toEqual([{ email: 'ana@example.com' }]);
    expect(screen.getByRole('button', { name: /^Pedir otro en/ })).toBeDisabled();
  });

  it('400 VALIDATION_ERROR de la contraseña: el motivo junto al campo y el token sigue valiendo para reintentar', async () => {
    let calls = 0;
    routeFetch(fetchMock, {
      [CONFIRM]: () => {
        calls += 1;
        return calls === 1
          ? errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
              validationErrors: { newPassword: 'La contraseña es demasiado común.' },
            })
          : noContentResponse();
      },
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    await submitPasswords(user, 'contraseña123');

    expect(await screen.findByText('La contraseña es demasiado común.')).toHaveAttribute('role', 'alert');
    expect(newPasswordField()).toHaveAttribute('aria-invalid', 'true');
    expect(newPasswordField()).toHaveFocus();
    expect(screen.getByRole('heading', { level: 1, name: 'Elige una contraseña nueva' })).toBeInTheDocument();

    await submitPasswords(user);

    expect(await screen.findByRole('heading', { name: 'Contraseña cambiada' })).toBeInTheDocument();
    expect(confirmBodies()).toEqual([
      { token: TOKEN, newPassword: 'contraseña123' },
      { token: TOKEN, newPassword: PASSWORD },
    ]);
  });

  it('400 VALIDATION_ERROR del token (mal formado según el servidor) también es «enlace no válido»', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'x', { validationErrors: { token: 'El token no es válido.' } }),
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    await submitPasswords(user);

    expect(await screen.findByRole('heading', { name: 'El enlace no es válido o ha caducado' })).toBeInTheDocument();
  });

  it('red caída: lo dice y el mismo botón reintenta con el token (que ya no está en la URL)', async () => {
    let calls = 0;
    routeFetch(fetchMock, {
      [CONFIRM]: () => {
        calls += 1;
        if (calls === 1) throw new TypeError('Failed to fetch');
        return noContentResponse();
      },
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);

    await submitPasswords(user);
    expect(await screen.findByText(/No se pudo conectar con el servidor/)).toHaveAttribute('role', 'alert');
    // Lo escrito se conserva: no hay que volver a teclearlo.
    expect(newPasswordField()).toHaveValue(PASSWORD);

    await user.click(submitButton());

    expect(await screen.findByRole('heading', { name: 'Contraseña cambiada' })).toBeInTheDocument();
    expect(confirmBodies().map((body) => body.token)).toEqual([TOKEN, TOKEN]);
  });

  it('429: avisa y bloquea el botón con la cuenta atrás de Retry-After', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    routeFetch(fetchMock, {
      [CONFIRM]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
    });
    const user = userEvent.setup({ delay: null });
    renderReset(`#token=${TOKEN}`);

    await submitPasswords(user);

    expect(await screen.findByRole('button', { name: 'Reintentar en 30 s' })).toBeDisabled();
    expect(screen.getByText('Demasiados intentos. Inténtalo de nuevo en 30 s.')).toHaveAttribute('role', 'alert');
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(submitButton()).toBeEnabled();
    expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
  });
});

describe('ResetPasswordPage: sin token válido en el enlace', () => {
  it.each([
    ['sin fragmento', ''],
    ['fragmento sin token', '#otra=cosa'],
    ['token vacío', '#token='],
    ['token con caracteres que un token nuestro no tiene', '#token=abc%3Cscript%3E'],
  ])('%s: «enlace no válido» directamente, sin llamar al servidor', async (_case, hash) => {
    routeFetch(fetchMock, {});
    renderReset(hash);

    expect(screen.getByRole('heading', { level: 1, name: 'El enlace no es válido o ha caducado' })).toBeInTheDocument();
    expect(screen.queryByLabelText('Contraseña nueva')).not.toBeInTheDocument();
    expect(screen.getByText(/comprueba también que esté completo/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enviar un enlace nuevo' })).toBeEnabled();
    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/refresh'));
    expect(apiCalls(fetchMock)).toEqual([]);
  });
});

describe('ResetPasswordPage: otro enlace con la página ya abierta', () => {
  const openNewLink = () => screen.getByRole('button', { name: 'Abrir el enlace nuevo' });

  it('desde «enlace no válido», el enlace nuevo vuelve al formulario (foco en el título) y usa el token nuevo', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: ({ init }) =>
        JSON.parse(String(init?.body)).token === TOKEN
          ? errorResponse(400, 'INVALID_RESET_TOKEN', 'x')
          : noContentResponse(),
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);
    await submitPasswords(user);
    await screen.findByRole('heading', { name: 'El enlace no es válido o ha caducado' });

    await user.click(openNewLink());

    const heading = await screen.findByRole('heading', { level: 1, name: 'Elige una contraseña nueva' });
    expect(heading).toHaveFocus();
    // El token nuevo también sale de la URL; al quedar vacío el fragmento NO se vuelve a «no válido».
    expect(await screen.findByText('hash: navegación:REPLACE')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Elige una contraseña nueva' })).toBeInTheDocument();
    expect(newPasswordField()).toHaveValue('');

    await submitPasswords(user);

    expect(await screen.findByRole('heading', { name: 'Contraseña cambiada' })).toHaveFocus();
    expect(confirmBodies().map((body) => body.token)).toEqual([TOKEN, NEW_TOKEN]);
  });

  it('desde «enlace no válido» sin token al cargar, el enlace nuevo también sirve', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderReset('');
    expect(screen.getByRole('heading', { name: 'El enlace no es válido o ha caducado' })).toBeInTheDocument();

    await user.click(openNewLink());
    await screen.findByRole('heading', { name: 'Elige una contraseña nueva' });
    await submitPasswords(user);

    expect(await screen.findByRole('heading', { name: 'Contraseña cambiada' })).toBeInTheDocument();
    expect(confirmBodies().map((body) => body.token)).toEqual([NEW_TOKEN]);
  });

  it('no arrastra el error ni la cuenta atrás (429) del enlace anterior', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
    });
    const user = userEvent.setup();
    renderReset(`#token=${TOKEN}`);
    await submitPasswords(user);
    expect(await screen.findByRole('button', { name: 'Reintentar en 30 s' })).toBeDisabled();

    await user.click(openNewLink());

    expect(await screen.findByRole('heading', { name: 'Elige una contraseña nueva' })).toHaveFocus();
    expect(submitButton()).toBeEnabled();
    expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
  });
});

describe('ResetPasswordPage: es pública y funciona con o sin sesión', () => {
  it('con sesión abierta no redirige a la portada; con el enlace no válido, el pie lleva al inicio', async () => {
    routeFetch(fetchMock, {});
    renderReset('', { session: true });

    expect(await screen.findByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('link', { name: 'Inicia sesión' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/restablecer-contrasena')).toBeInTheDocument();
  });
});
