/**
 * Revisión independiente (QA) de `ResetPasswordPage`: el caso que no cubre
 * `ResetPasswordPage.test.tsx`, cambiar la contraseña **mientras el arranque aún
 * está comprobando si hay sesión** (`GET /api/users/me` sin responder).
 *
 * Qué protege:
 * - La sesión que pudiera haber se cierra «por si acaso» (un `POST /auth/logout`),
 *   pero la página **no afirma** «También se ha cerrado la sesión que había abierta en
 *   este navegador»: quizá no había ninguna, y el texto mentiría.
 * - Se ofrece «Iniciar sesión» (no un callejón sin salida) y no aparece el aviso de
 *   «sesión caducada».
 * - La respuesta tardía de la comprobación (un 200 con el usuario, pedido antes del
 *   cierre) **no** resucita la sesión: la interfaz sigue sin sesión.
 * - Con las respuestas en cualquier otro orden el texto sigue siendo verdad: si la
 *   comprobación acaba en 401 antes que el cambio de contraseña, o si el cierre
 *   «por si acaso» no se confirma, tampoco se afirma nada. En cambio, una sesión
 *   conocida que cerró otra vía sí se da por cerrada.
 */
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { useAuth } from '../context/AuthContext';
import { RESET_SESSIONS_CLOSED } from '../lib/passwordReset';
import {
  CURRENT_USER,
  errorResponse,
  jsonResponse,
  makeUser,
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

/** Un token con el formato de los del backend (32 bytes en Base64URL). */
const TOKEN = 'dGhpcy1pcy1hLXRlc3QtdG9rZW4tb2YtMzItYnl0ZXM';
const PASSWORD = 'una-frase-larga-y-segura';
const CONFIRM = 'POST /api/auth/password-reset/confirm';
const LOGOUT = 'POST /api/auth/logout';
/** El texto que solo debe salir si de verdad había una sesión abierta. */
const SESSION_CLOSED_HERE = 'También se ha cerrado la sesión que había abierta en este navegador.';
const SESSION_STUCK =
  'No se ha podido cerrar la sesión abierta en este navegador. Ciérrala antes de entrar con tu contraseña nueva.';

/** Respuesta que el test entrega cuando quiere: así el orden se decide en el test, sin esperas de tiempo real. */
function deferredResponse() {
  let answer: (response: Response) => void = () => {};
  const promise = new Promise<Response>((resolve) => {
    answer = resolve;
  });
  return { promise, answer };
}

const logoutCalls = () => fetchMock.mock.calls.filter(([url]) => String(url) === '/api/auth/logout');

/** Otra vía de cierre con la página abierta, como la que usaría el resto de la aplicación. */
function CloseSessionProbe() {
  const { logout } = useAuth();
  return (
    <button type="button" onClick={() => void logout()}>
      Cerrar por otra vía
    </button>
  );
}

/** Abre el enlace del correo con las rutas reales y espera al formulario. */
async function openResetLink(options: { session?: boolean } = {}) {
  renderWithProviders(
    <>
      <AppRoutes />
      <UserStatusProbe />
      <CloseSessionProbe />
    </>,
    { route: `/restablecer-contrasena#token=${TOKEN}`, session: options.session },
  );
  await screen.findByRole('heading', { level: 1, name: 'Elige una contraseña nueva' });
}

/** Rellena el formulario y lo envía. */
async function submitNewPassword(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('Contraseña nueva'), PASSWORD);
  await user.type(screen.getByLabelText('Repite la contraseña nueva'), PASSWORD);
  await user.click(screen.getByRole('button', { name: 'Cambiar la contraseña' }));
}

describe('ResetPasswordPage (QA): cambiar la contraseña mientras se comprueba la sesión', () => {
  it('cierra la posible sesión sin afirmar que la hubiera, ofrece «Iniciar sesión» y la respuesta tardía no la resucita', async () => {
    let answerSessionCheck: (response: Response) => void = () => {};
    const sessionCheck = new Promise<Response>((resolve) => {
      answerSessionCheck = resolve;
    });
    routeFetch(fetchMock, {
      [CURRENT_USER]: () => sessionCheck,
      'POST /api/auth/password-reset/confirm': () => noContentResponse(),
      'POST /api/auth/logout': () => noContentResponse(),
    });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <AppRoutes />
        <UserStatusProbe />
      </>,
      { route: `/restablecer-contrasena#token=${TOKEN}` },
    );
    await screen.findByRole('heading', { level: 1, name: 'Elige una contraseña nueva' });
    expect(screen.getByText('estado:loading')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Contraseña nueva'), PASSWORD);
    await user.type(screen.getByLabelText('Repite la contraseña nueva'), PASSWORD);
    await user.click(screen.getByRole('button', { name: 'Cambiar la contraseña' }));

    await screen.findByRole('heading', { level: 1, name: 'Contraseña cambiada' });
    const link = await screen.findByRole('link', { name: 'Iniciar sesión' });
    expect(link).toHaveAttribute('href', '/login');
    const logouts = fetchMock.mock.calls.filter(([url]) => String(url) === '/api/auth/logout');
    expect(logouts).toHaveLength(1);
    expect(screen.getByText(RESET_SESSIONS_CLOSED)).toBeInTheDocument();
    expect(screen.queryByText('También se ha cerrado la sesión que había abierta en este navegador.')).toBeNull();

    // La comprobación pedida antes del cierre responde ahora con un usuario: no debe contar.
    answerSessionCheck(jsonResponse(makeUser()));
    await vi.waitFor(() => expect(screen.getByText('estado:idle')).toBeInTheDocument());
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(screen.queryByText(/Tu sesión ha caducado/)).toBeNull();
  });

  it('la comprobación da 401 antes de que responda el cambio: no había sesión, así que ni se cierra nada ni se afirma', async () => {
    const sessionCheck = deferredResponse();
    const confirm = deferredResponse();
    routeFetch(fetchMock, {
      [CURRENT_USER]: () => sessionCheck.promise,
      [CONFIRM]: () => confirm.promise,
      [LOGOUT]: () => noContentResponse(),
    });
    const user = userEvent.setup();
    await openResetLink();
    expect(screen.getByText('estado:loading')).toBeInTheDocument();

    await submitNewPassword(user);
    await screen.findByRole('button', { name: 'Cambiando la contraseña...' });
    sessionCheck.answer(errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'));
    await screen.findByText('estado:idle');
    confirm.answer(noContentResponse());

    await screen.findByRole('heading', { level: 1, name: 'Contraseña cambiada' });
    expect(await screen.findByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(logoutCalls()).toHaveLength(0);
    expect(screen.getByText(RESET_SESSIONS_CLOSED)).toBeInTheDocument();
    expect(screen.queryByText(SESSION_CLOSED_HERE)).toBeNull();
    expect(screen.queryByText(SESSION_STUCK)).toBeNull();
    expect(screen.queryByText(/Tu sesión ha caducado/)).toBeNull();
  });

  it('el cierre «por si acaso» falla y la comprobación acaba en 401: tampoco había sesión y no se afirma', async () => {
    const sessionCheck = deferredResponse();
    routeFetch(fetchMock, {
      [CURRENT_USER]: () => sessionCheck.promise,
      [CONFIRM]: () => noContentResponse(),
      [LOGOUT]: () => errorResponse(403, 'CSRF_REJECTED', 'x'),
    });
    const user = userEvent.setup();
    await openResetLink();

    await submitNewPassword(user);

    expect(await screen.findByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(logoutCalls()).toHaveLength(1);
    expect(screen.queryByText(SESSION_CLOSED_HERE)).toBeNull();

    sessionCheck.answer(errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'));
    await screen.findByText('estado:idle');
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(screen.queryByText(SESSION_CLOSED_HERE)).toBeNull();
    expect(screen.queryByText(SESSION_STUCK)).toBeNull();
  });

  it('el cierre «por si acaso» falla y resulta que sí había sesión: avisa, y al reintentar ya puede afirmar el cierre', async () => {
    const sessionCheck = deferredResponse();
    let logouts = 0;
    routeFetch(fetchMock, {
      [CURRENT_USER]: () => sessionCheck.promise,
      [CONFIRM]: () => noContentResponse(),
      [LOGOUT]: () => {
        logouts += 1;
        return logouts === 1 ? errorResponse(403, 'CSRF_REJECTED', 'x') : noContentResponse();
      },
    });
    const user = userEvent.setup();
    await openResetLink();

    await submitNewPassword(user);
    await screen.findByRole('link', { name: 'Iniciar sesión' });
    expect(screen.queryByText(SESSION_CLOSED_HERE)).toBeNull();

    sessionCheck.answer(jsonResponse(makeUser()));

    expect(await screen.findByText(SESSION_STUCK)).toHaveAttribute('role', 'alert');
    expect(screen.queryByRole('link', { name: 'Iniciar sesión' })).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByText(SESSION_CLOSED_HERE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(logoutCalls()).toHaveLength(2);
  });
});

describe('ResetPasswordPage (QA): sesión conocida que se cierra por otra vía', () => {
  it('el cierre de la página no hace nada porque ya estaba cerrada: sí afirma el cierre, y es verdad', async () => {
    const confirm = deferredResponse();
    routeFetch(fetchMock, { [CONFIRM]: () => confirm.promise, [LOGOUT]: () => noContentResponse() });
    const user = userEvent.setup();
    await openResetLink({ session: true });
    await screen.findByText('estado:ready');

    await submitNewPassword(user);
    await screen.findByRole('button', { name: 'Cambiando la contraseña...' });
    await user.click(screen.getByRole('button', { name: 'Cerrar por otra vía' }));
    await screen.findByText('estado:idle');
    confirm.answer(noContentResponse());

    expect(await screen.findByText(SESSION_CLOSED_HERE)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    expect(logoutCalls()).toHaveLength(1);
  });
});
