/**
 * Tests de `VerifyEmailPage` (`/verificar-email#token=…`), la página a la que
 * lleva el enlace del correo de confirmación.
 *
 * Protegen lo que hace segura y usable la confirmación: el token se lee del
 * fragmento y se quita de la URL; NO se envía al cargar (los antivirus del
 * correo abren los enlaces y lo gastarían), solo al pulsar el botón; 204 lleva a
 * «Correo confirmado», 400 (o un enlace sin token) a «enlace no válido» con la
 * forma de pedir otro, y los fallos pasajeros (red, 429) dejan reintentar. Se
 * monta con las rutas reales (`AppRoutes`) para comprobar también que la página
 * es pública y no redirige a nadie, haya sesión o no. `fetch` está simulado.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLocation, useNavigate, useNavigationType } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { RESEND_DONE_MESSAGE } from '../lib/emailVerification';
import {
  apiCalls,
  errorResponse,
  jsonResponse,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

const CONFIRM = 'POST /api/auth/email-verification';
const RESEND = 'POST /api/auth/email-verification/resend';
/** Un token con el formato de los del backend (32 bytes en Base64URL). */
const TOKEN = 'dGhpcy1pcy1hLXRlc3QtdG9rZW4tb2YtMzItYnl0ZXM';

/** Enseña el fragmento y el tipo de la última navegación, para comprobar que el token sale de la URL. */
function HashProbe() {
  const location = useLocation();
  const navigationType = useNavigationType();
  return <p>{`hash:${location.hash} navegación:${navigationType}`}</p>;
}

/** Otro token válido: el del enlace nuevo que se pide desde «enlace no válido». */
const NEW_TOKEN = 'b3Ryby10b2tlbi1kZS0zMi1ieXRlcy1wYXJhLWVsLXRlc3Q';

/**
 * Abre otro enlace con la página ya montada, como pegar `…/verificar-email#token=…`
 * en la misma pestaña: solo cambia el fragmento y el navegador no recarga
 * (React Router recibe un `popstate`; aquí, un `navigate` normal).
 */
function OpenLinkProbe() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(`/verificar-email#token=${NEW_TOKEN}`)}>
      Abrir el enlace nuevo
    </button>
  );
}

/** Monta las rutas reales en `/verificar-email` con el fragmento indicado. */
function renderVerify(hash: string, options: { session?: boolean } = {}) {
  return renderWithProviders(
    <>
      <AppRoutes />
      <HashProbe />
      <OpenLinkProbe />
    </>,
    { route: `/verificar-email${hash}`, session: options.session },
  );
}

const confirmButton = () => screen.getByRole('button', { name: 'Confirmar mi correo' });
const confirmCalls = () => apiCalls(fetchMock).filter(([url]) => url === '/api/auth/email-verification');

describe('VerifyEmailPage: con un token en el enlace', () => {
  it('pide pulsar el botón y NO confirma al cargar (un robot que abra el enlace no gasta el token)', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    renderVerify(`#token=${TOKEN}`);

    expect(screen.getByRole('heading', { level: 1, name: 'Confirma tu correo' })).toBeInTheDocument();
    expect(screen.getByText('Pulsa el botón para activar tu cuenta y poder iniciar sesión.')).toBeInTheDocument();
    expect(screen.getByText(/Algunos programas de correo abren los enlaces por su cuenta/)).toBeInTheDocument();
    expect(document.title).toBe('Confirmar correo — StreamBox');
    // Se deja terminar el arranque (chequeo de la sesión) y no ha salido ninguna confirmación.
    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/refresh'));
    expect(confirmCalls()).toEqual([]);
    expect(confirmButton()).toBeEnabled();
  });

  it('quita el token de la barra de direcciones (sustituyendo la entrada del historial) y lo conserva en memoria', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);

    expect(await screen.findByText('hash: navegación:REPLACE')).toBeInTheDocument();
    expect(screen.getByText('ruta:/verificar-email')).toBeInTheDocument();

    await user.click(confirmButton());

    await screen.findByRole('heading', { name: 'Correo confirmado' });
    const [[, init]] = confirmCalls();
    expect(JSON.parse(String(init?.body))).toEqual({ token: TOKEN });
  });

  it('204: «Correo confirmado», con el foco en el título y el camino a «Iniciar sesión»', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);

    await user.click(confirmButton());

    const heading = await screen.findByRole('heading', { level: 1, name: 'Correo confirmado' });
    expect(heading).toHaveFocus();
    expect(document.title).toBe('Correo confirmado — StreamBox');
    expect(screen.getByText('Tu cuenta ya está activa.')).toBeInTheDocument();
    expect(screen.getByText('Ya puedes iniciar sesión con tu correo y tu contraseña.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Iniciar sesión' })).toHaveAttribute('href', '/login');
    const [[url, init]] = confirmCalls();
    expect(url).toBe('/api/auth/email-verification');
    expect(init?.method).toBe('POST');
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
  });

  it('mientras confirma, el botón queda bloqueado (no se envía dos veces)', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => new Promise<Response>(() => {}) });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);

    await user.click(confirmButton());

    expect(await screen.findByRole('button', { name: 'Confirmando...' })).toBeDisabled();
  });

  it('400 INVALID_VERIFICATION_TOKEN: «El enlace no es válido o ha caducado», con el foco en el título y salidas', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: () => errorResponse(400, 'INVALID_VERIFICATION_TOKEN', 'Texto interno del servidor'),
    });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);

    await user.click(confirmButton());

    const heading = await screen.findByRole('heading', { level: 1, name: 'El enlace no es válido o ha caducado' });
    expect(heading).toHaveFocus();
    expect(screen.getByText('Cada enlace sirve una sola vez y caduca a las 24 horas.')).toBeInTheDocument();
    // Quien ya confirmó (el enlace está usado) no tiene que hacer nada: solo entrar.
    expect(screen.getByText(/Si ya habías confirmado tu correo, no tienes que hacer nada más/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'inicia sesión' })).toHaveAttribute('href', '/login');
    expect(screen.getByLabelText('Correo electrónico')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Enviar un enlace nuevo' })).toBeEnabled();
    expect(screen.getByRole('link', { name: 'Regístrate' })).toHaveAttribute('href', '/registro');
    expect(screen.queryByText('Texto interno del servidor')).not.toBeInTheDocument();
  });

  it('400 VALIDATION_ERROR (token mal formado según el servidor) también es «enlace no válido»', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => errorResponse(400, 'VALIDATION_ERROR', 'x') });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);

    await user.click(confirmButton());

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
    renderVerify(`#token=${TOKEN}`);

    await user.click(confirmButton());
    expect(await screen.findByText(/No se pudo conectar con el servidor/)).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('heading', { name: 'Confirma tu correo' })).toBeInTheDocument();

    await user.click(confirmButton());

    expect(await screen.findByRole('heading', { name: 'Correo confirmado' })).toBeInTheDocument();
    expect(confirmCalls().map(([, init]) => JSON.parse(String(init?.body)))).toEqual([{ token: TOKEN }, { token: TOKEN }]);
  });

  it('429: avisa y bloquea el botón con la cuenta atrás de Retry-After', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    routeFetch(fetchMock, {
      [CONFIRM]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
    });
    const user = userEvent.setup({ delay: null });
    renderVerify(`#token=${TOKEN}`);

    await user.click(confirmButton());

    expect(await screen.findByRole('button', { name: 'Reintentar en 30 s' })).toBeDisabled();
    expect(screen.getByText('Demasiados intentos. Inténtalo de nuevo en 30 s.')).toHaveAttribute('role', 'alert');
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(confirmButton()).toBeEnabled();
    expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
  });
});

describe('VerifyEmailPage: sin token válido en el enlace', () => {
  it.each([
    ['sin fragmento', ''],
    ['fragmento sin token', '#otra=cosa'],
    ['token vacío', '#token='],
    ['token con caracteres que un token nuestro no tiene', '#token=abc%3Cscript%3E'],
  ])('%s: «enlace no válido» directamente, sin llamar al servidor', async (_case, hash) => {
    routeFetch(fetchMock, {});
    renderVerify(hash);

    expect(screen.getByRole('heading', { level: 1, name: 'El enlace no es válido o ha caducado' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirmar mi correo' })).not.toBeInTheDocument();
    expect(screen.getByText(/comprueba también que esté completo/)).toBeInTheDocument();
    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/refresh'));
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('pedir un enlace nuevo: valida el correo en el cliente, lo envía recortado y avisa sin afirmar nada', async () => {
    routeFetch(fetchMock, { [RESEND]: () => jsonResponse({ message: 'x' }, 202) });
    const user = userEvent.setup();
    renderVerify('');
    const field = screen.getByLabelText('Correo electrónico');

    await user.click(screen.getByRole('button', { name: 'Enviar un enlace nuevo' }));
    expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAccessibleDescription('Introduce tu correo electrónico.');
    expect(field).toHaveFocus();
    expect(apiCalls(fetchMock)).toEqual([]);

    await user.type(field, '  ana@example.com ');
    expect(field).not.toHaveAttribute('aria-invalid');
    await user.click(screen.getByRole('button', { name: 'Enviar un enlace nuevo' }));

    const done = await screen.findByText(RESEND_DONE_MESSAGE('ana@example.com'));
    expect(done.closest('[role="status"]')).not.toBeNull();
    const resend = apiCalls(fetchMock).filter(([url]) => url === '/api/auth/email-verification/resend');
    expect(resend).toHaveLength(1);
    expect(JSON.parse(String(resend[0][1]?.body))).toEqual({ email: 'ana@example.com' });
    expect(screen.getByRole('button', { name: /^Pedir otro en/ })).toBeDisabled();
  });

  it('pedir un enlace nuevo con 429: avisa de la espera y bloquea el botón', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    routeFetch(fetchMock, {
      [RESEND]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '45' }),
    });
    const user = userEvent.setup({ delay: null });
    renderVerify('');

    await user.type(screen.getByLabelText('Correo electrónico'), 'ana@example.com');
    await user.click(screen.getByRole('button', { name: 'Enviar un enlace nuevo' }));

    expect(
      await screen.findByText('Has pedido demasiados correos seguidos. Podrás pedir otro en 45 s.'),
    ).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('button', { name: 'Pedir otro en 45 s' })).toBeDisabled();
  });
});

describe('VerifyEmailPage: otro enlace con la página ya abierta', () => {
  const openNewLink = () => screen.getByRole('button', { name: 'Abrir el enlace nuevo' });
  const confirmedTokens = () => confirmCalls().map(([, init]) => JSON.parse(String(init?.body)).token as string);

  it('desde «enlace no válido», el enlace nuevo vuelve a «Confirma tu correo» (foco en el título), sin confirmar solo', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: ({ init }) =>
        JSON.parse(String(init?.body)).token === TOKEN
          ? errorResponse(400, 'INVALID_VERIFICATION_TOKEN', 'x')
          : noContentResponse(),
    });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);
    await user.click(confirmButton());
    await screen.findByRole('heading', { name: 'El enlace no es válido o ha caducado' });

    await user.click(openNewLink());

    const heading = await screen.findByRole('heading', { level: 1, name: 'Confirma tu correo' });
    expect(heading).toHaveFocus();
    // El token nuevo también sale de la URL; al quedar vacío el fragmento NO se vuelve a «no válido».
    expect(await screen.findByText('hash: navegación:REPLACE')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Confirma tu correo' })).toBeInTheDocument();
    expect(confirmButton()).toBeEnabled();
    expect(confirmedTokens()).toEqual([TOKEN]);

    await user.click(confirmButton());

    expect(await screen.findByRole('heading', { name: 'Correo confirmado' })).toHaveFocus();
    expect(confirmedTokens()).toEqual([TOKEN, NEW_TOKEN]);
  });

  it('desde «enlace no válido» sin token al cargar, el enlace nuevo también se puede confirmar', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderVerify('');
    expect(screen.getByRole('heading', { name: 'El enlace no es válido o ha caducado' })).toBeInTheDocument();

    await user.click(openNewLink());
    await user.click(await screen.findByRole('button', { name: 'Confirmar mi correo' }));

    expect(await screen.findByRole('heading', { name: 'Correo confirmado' })).toBeInTheDocument();
    expect(confirmedTokens()).toEqual([NEW_TOKEN]);
  });

  it('no arrastra el error ni la cuenta atrás (429) del enlace anterior', async () => {
    routeFetch(fetchMock, {
      [CONFIRM]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
    });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);
    await user.click(confirmButton());
    expect(await screen.findByRole('button', { name: 'Reintentar en 30 s' })).toBeDisabled();

    await user.click(openNewLink());

    expect(await screen.findByRole('heading', { name: 'Confirma tu correo' })).toHaveFocus();
    expect(confirmButton()).toBeEnabled();
    expect(screen.queryByText(/Demasiados intentos/)).not.toBeInTheDocument();
  });

  it('la respuesta tardía del enlace anterior no pisa la pantalla del nuevo', async () => {
    let answerOld: (response: Response) => void = () => {};
    routeFetch(fetchMock, {
      [CONFIRM]: ({ init }) =>
        JSON.parse(String(init?.body)).token === TOKEN
          ? new Promise<Response>((resolve) => {
              answerOld = resolve;
            })
          : noContentResponse(),
    });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`);
    await user.click(confirmButton());
    expect(await screen.findByRole('button', { name: 'Confirmando...' })).toBeDisabled();

    await user.click(openNewLink());
    await screen.findByRole('heading', { name: 'Confirma tu correo' });
    await act(async () => {
      answerOld(errorResponse(400, 'INVALID_VERIFICATION_TOKEN', 'x'));
    });

    expect(screen.getByRole('heading', { name: 'Confirma tu correo' })).toBeInTheDocument();
    expect(confirmButton()).toBeEnabled();
  });
});

describe('VerifyEmailPage: es pública y funciona con o sin sesión', () => {
  it('sin sesión no manda al login', async () => {
    routeFetch(fetchMock, {});
    renderVerify(`#token=${TOKEN}`);

    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/refresh'));
    expect(screen.getByText('ruta:/verificar-email')).toBeInTheDocument();
    expect(confirmButton()).toBeInTheDocument();
  });

  it('con sesión abierta no redirige a la portada y, tras confirmar, ofrece «Ir al inicio»', async () => {
    routeFetch(fetchMock, { [CONFIRM]: () => noContentResponse() });
    const user = userEvent.setup();
    renderVerify(`#token=${TOKEN}`, { session: true });

    // Espera a que la sesión se conozca: con ella, la página sigue aquí.
    await vi.waitFor(() =>
      expect(fetchMock.mock.calls.filter(([url]) => url === '/api/users/me')).toHaveLength(1),
    );
    await user.click(confirmButton());

    await screen.findByRole('heading', { name: 'Correo confirmado' });
    expect(screen.getByText('ruta:/verificar-email')).toBeInTheDocument();
    expect(screen.getByText('Ya hay una sesión abierta en este navegador: puedes seguir donde estabas.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('link', { name: 'Iniciar sesión' })).not.toBeInTheDocument();
  });

  it('con sesión abierta y enlace no válido: no ofrece registrarse y lleva al inicio', async () => {
    routeFetch(fetchMock, {});
    renderVerify('', { session: true });

    expect(await screen.findByRole('link', { name: 'Ir al inicio' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('link', { name: 'Regístrate' })).not.toBeInTheDocument();
    expect(screen.getByText('ruta:/verificar-email')).toBeInTheDocument();
  });
});
