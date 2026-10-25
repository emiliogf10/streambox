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
 */
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { RESET_SESSIONS_CLOSED } from '../lib/passwordReset';
import { CURRENT_USER, jsonResponse, makeUser, noContentResponse, renderWithProviders, routeFetch } from '../test/helpers';
import { UserStatusProbe } from '../test/UserStatusProbe';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Un token con el formato de los del backend (32 bytes en Base64URL). */
const TOKEN = 'dGhpcy1pcy1hLXRlc3QtdG9rZW4tb2YtMzItYnl0ZXM';
const PASSWORD = 'una-frase-larga-y-segura';

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
});
