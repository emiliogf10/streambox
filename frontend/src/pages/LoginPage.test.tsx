/**
 * Tests de `LoginPage`.
 *
 * Se consulta la pantalla como lo haría una persona o un lector de pantalla
 * (por etiqueta, rol y texto). Protegen que un 401 de login sea "credenciales
 * incorrectas" (y no cierre ninguna sesión), que un 429 bloquee el botón con
 * cuenta atrás en vez de dejar martillear al servidor, y que el login no deje
 * ningún token en el navegador (la sesión es una cookie HttpOnly). También que
 * todo 401 y la cuenta bloqueada lleven la pista «¿aún no has confirmado tu
 * correo?» (una cuenta pendiente recibe el mismo 401 que una contraseña
 * incorrecta) y que ningún 403 se trate ya como «cuenta sin confirmar».
 * `fetch` está simulado.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiCalls, errorResponse, jsonResponse, makeUser, noContentResponse, renderWithProviders, routeFetch } from '../test/helpers';
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
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('un login correcto envía el correo recortado, con la cabecera anti-CSRF y SIN token en el navegador', async () => {
    routeFetch(fetchMock, { 'POST /api/auth/login': () => noContentResponse() });
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user, '  ana@example.com  ');

    await vi.waitFor(() => expect(fetchMock.mock.calls.map(([url]) => url)).toContain('/api/auth/login'));
    const [url, init] = apiCalls(fetchMock)[0];
    expect(url).toBe('/api/auth/login');
    expect(init?.method).toBe('POST');
    expect(init?.credentials).toBe('same-origin');
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@example.com', password: 'secreta123' });
    const headers = new Headers(init?.headers);
    expect(headers.has('Authorization')).toBe(false);
    expect(headers.get('X-Requested-With')).toBe('StreamBox');
    expect(localStorage.length).toBe(0);
  });

  it('tras un login correcto la sesión carga quién es el usuario (/users/me) con la cookie, sin Authorization', async () => {
    let loggedIn = false;
    routeFetch(fetchMock, {
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
      'GET /api/users/me': () =>
        loggedIn ? jsonResponse(makeUser()) : errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'),
    });
    const user = userEvent.setup();
    renderWithProviders(<LoginPage />, { route: '/login' });

    await fillAndSubmit(user);

    await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url === '/api/users/me')).toHaveLength(2));
    const me = fetchMock.mock.calls.filter(([url]) => url === '/api/users/me')[1];
    expect(new Headers(me[1]?.headers).has('Authorization')).toBe(false);
    expect(me[1]?.credentials).toBe('same-origin');
    expect(localStorage.length).toBe(0);
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
      // Primer envío: 401 con intentos; el segundo se queda pendiente. (El chequeo inicial de la sesión también es una llamada.)
      let attempts = 0;
      routeFetch(fetchMock, {
        'POST /api/auth/login': () => {
          attempts += 1;
          return attempts === 1
            ? errorResponse(401, 'INVALID_CREDENTIALS', 'x', { remainingAttempts: 2 })
            : new Promise<Response>(() => {});
        },
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);
      expect(await screen.findByText(/Te quedan 2 intentos/)).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
      expect(screen.queryByText(/Te quedan 2 intentos/)).not.toBeInTheDocument();
    });
  });

  /**
   * Pista «¿aún no has confirmado tu correo?». El servidor responde a una cuenta pendiente con la
   * contraseña correcta el MISMO 401 que a una contraseña incorrecta (así el login no revela qué correos
   * tienen cuenta), así que la pista acompaña a todo 401 y a la cuenta bloqueada, y nunca a lo demás.
   */
  describe('pista de correo sin confirmar (401 y cuenta bloqueada)', () => {
    const RESEND_URL = '/api/auth/email-verification/resend';
    const HINT_TITLE = '¿Te acabas de registrar o aún no has confirmado tu correo?';
    const HINT_TEXT = (email: string) =>
      'Hasta que lo confirmes no podrás entrar, aunque la contraseña sea correcta. Pulsa el enlace del correo ' +
      'de confirmación de StreamBox (mira también en spam); si no tienes ninguno o ha caducado, pide uno nuevo ' +
      `para ${email}.`;
    const RESEND_BUTTON = 'Reenviar el correo de confirmación';

    /** Llamadas al reenvío del correo de confirmación. */
    const resendCalls = () => apiCalls(fetchMock).filter(([url]) => url === RESEND_URL);

    it('en un 401 sale DEBAJO del error (que conserva los intentos), en tono neutro y como región cortés', async () => {
      routeFetch(fetchMock, {
        'POST /api/auth/login': () =>
          errorResponse(401, 'INVALID_CREDENTIALS', 'Texto interno del servidor', { remainingAttempts: 3 }),
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      const alert = (await screen.findByText('Correo o contraseña incorrectos.')).closest('[role="alert"]');
      expect(alert).toHaveTextContent('Te quedan 3 intentos antes de que la cuenta se bloquee 15 minutos.');
      const title = screen.getByText(HINT_TITLE);
      const hint = title.closest('[role="status"]');
      expect(hint).not.toBeNull();
      expect(hint).toHaveTextContent(`${HINT_TITLE}${HINT_TEXT('ana@example.com')}`);
      // Es una pista, no parte del error: fuera de la alerta, después de ella y sin el rojo de los errores.
      expect(alert?.contains(title)).toBe(false);
      expect(alert!.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
      expect(hint?.parentElement?.className).not.toMatch(/red|accent|danger/);
      // El botón va después del texto y FUERA de la región viva (no se leería como parte del mensaje).
      const button = screen.getByRole('button', { name: RESEND_BUTTON });
      expect(hint?.contains(button)).toBe(false);
      expect(title.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).not.toBe(0);
      // Se puede pedir al momento (no se acaba de enviar ninguno) y no se ha pedido nada solo.
      expect(button).toBeEnabled();
      expect(resendCalls()).toHaveLength(0);
      expect(screen.queryByText('Texto interno del servidor')).not.toBeInTheDocument();
    });

    it('no afirma que la cuenta exista ni que se haya enviado nada', async () => {
      routeFetch(fetchMock, { 'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x') });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      const hint = (await screen.findByText(HINT_TITLE)).closest('[role="status"]');
      expect(hint).not.toHaveTextContent(/tu cuenta|hemos enviado|te hemos|está pendiente/i);
    });

    it('«Reenviar el correo de confirmación» lo pide para el correo del intento, recortado', async () => {
      routeFetch(fetchMock, {
        'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
        [`POST ${RESEND_URL}`]: () => jsonResponse({ message: 'x' }, 202),
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });
      await fillAndSubmit(user, '  ana@example.com ');
      expect(screen.getByText(HINT_TITLE).closest('[role="status"]')).toHaveTextContent('para ana@example.com.');

      await user.click(screen.getByRole('button', { name: RESEND_BUTTON }));

      expect(
        await screen.findByText(
          'Si hay una cuenta pendiente de confirmar con ana@example.com, te llegará un enlace nuevo en unos minutos, ' +
            'salvo que ya hayas pedido varios hace poco. Usa siempre el del correo más reciente.',
        ),
      ).toBeInTheDocument();
      expect(resendCalls()).toHaveLength(1);
      expect(JSON.parse(String(resendCalls()[0][1]?.body))).toEqual({ email: 'ana@example.com' });
      expect(screen.getByRole('button', { name: /^Reenviar en/ })).toBeDisabled();
    });

    it('la pista usa el correo del intento aunque después se edite el campo', async () => {
      routeFetch(fetchMock, {
        'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
        [`POST ${RESEND_URL}`]: () => jsonResponse({ message: 'x' }, 202),
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });
      await fillAndSubmit(user);
      await screen.findByText(HINT_TITLE);

      await user.type(screen.getByLabelText('Correo electrónico'), 'x');
      await user.click(screen.getByRole('button', { name: RESEND_BUTTON }));

      // El texto decía «para ana@example.com»: se envía a ese, no a lo que haya ahora en el campo.
      await vi.waitFor(() => expect(resendCalls()).toHaveLength(1));
      expect(JSON.parse(String(resendCalls()[0][1]?.body))).toEqual({ email: 'ana@example.com' });
    });

    it('la espera del reenvío sobrevive a otro intento de entrar (no se puede reenviar al momento)', async () => {
      routeFetch(fetchMock, {
        'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
        [`POST ${RESEND_URL}`]: () => jsonResponse({ message: 'x' }, 202),
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });
      await fillAndSubmit(user);
      await user.click(await screen.findByRole('button', { name: RESEND_BUTTON }));
      expect(await screen.findByRole('button', { name: /^Reenviar en/ })).toBeDisabled();

      // Vuelve a intentarlo antes de confirmar: otro 401 y la pista vuelve, pero el botón sigue esperando.
      await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));
      await vi.waitFor(() =>
        expect(apiCalls(fetchMock).filter(([url]) => url === '/api/auth/login')).toHaveLength(2),
      );
      expect(await screen.findByText(HINT_TITLE)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /^Reenviar en/ })).toBeDisabled();
      expect(screen.queryByRole('button', { name: RESEND_BUTTON })).not.toBeInTheDocument();
      expect(resendCalls()).toHaveLength(1);
    });

    it('si el reenvío da 429, dice cuánto esperar (Retry-After) y bloquea el botón', async () => {
      routeFetch(fetchMock, {
        'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
        [`POST ${RESEND_URL}`]: () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' }),
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });
      await fillAndSubmit(user);

      await user.click(await screen.findByRole('button', { name: RESEND_BUTTON }));

      expect(
        await screen.findByText('Has pedido demasiados correos seguidos. Podrás pedir otro en 30 s.'),
      ).toHaveAttribute('role', 'alert');
      expect(screen.getByRole('button', { name: /^Reenviar en (30|29) s$/ })).toBeDisabled();
      // El error del login sigue en su sitio: el aviso del reenvío no lo sustituye.
      expect(screen.getByText('Correo o contraseña incorrectos.')).toBeInTheDocument();
    });

    it('con un correo mal formado no hay pista ni botón: no hay cuenta posible a la que enviar nada', async () => {
      routeFetch(fetchMock, { 'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x') });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user, 'ana-sin-arroba');

      expect(await screen.findByText('Correo o contraseña incorrectos.')).toBeInTheDocument();
      expect(screen.queryByText(HINT_TITLE)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: RESEND_BUTTON })).not.toBeInTheDocument();
      expect(resendCalls()).toHaveLength(0);
    });

    it.each([
      ['red caída', () => Promise.reject(new TypeError('Failed to fetch')), /No se pudo conectar con el servidor/],
      ['500', () => errorResponse(500, 'INTERNAL_ERROR', 'x'), /El servidor ha tenido un problema/],
      ['400 de validación', () => errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos.'), /Datos no válidos\./],
      ['límite por IP (429 RATE_LIMIT_EXCEEDED)', () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x'), /Demasiados intentos/],
    ])('no sale con un error que no tiene que ver con la cuenta: %s', async (_name, response, message) => {
      routeFetch(fetchMock, { 'POST /api/auth/login': response });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });

      await fillAndSubmit(user);

      expect(await screen.findByText(message)).toHaveAttribute('role', 'alert');
      expect(screen.queryByText(HINT_TITLE)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: RESEND_BUTTON })).not.toBeInTheDocument();
    });

    it('al volver a enviar el formulario, la pista desaparece mientras espera la respuesta', async () => {
      let attempts = 0;
      routeFetch(fetchMock, {
        'POST /api/auth/login': () => {
          attempts += 1;
          return attempts === 1 ? errorResponse(401, 'INVALID_CREDENTIALS', 'x') : new Promise<Response>(() => {});
        },
      });
      const user = userEvent.setup();
      renderWithProviders(<LoginPage />, { route: '/login' });
      await fillAndSubmit(user);
      expect(await screen.findByText(HINT_TITLE)).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }));

      expect(screen.queryByText(HINT_TITLE)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: RESEND_BUTTON })).not.toBeInTheDocument();
    });

    it.each(['EMAIL_NOT_VERIFIED', 'CSRF_REJECTED'])(
      'un 403 (%s) es «sin permisos», sin pista ni el aviso antiguo de cuenta sin confirmar',
      async (code) => {
        // El backend ya no envía `EMAIL_NOT_VERIFIED`; si un servidor antiguo lo hiciera, es un 403 más.
        routeFetch(fetchMock, { 'POST /api/auth/login': () => errorResponse(403, code, 'Texto interno del servidor') });
        const user = userEvent.setup();
        renderWithProviders(<LoginPage />, { route: '/login' });

        await fillAndSubmit(user);

        expect(await screen.findByText('No tienes permisos para realizar esta acción.')).toHaveAttribute('role', 'alert');
        expect(screen.queryByText('Tienes que confirmar tu correo antes de entrar.')).not.toBeInTheDocument();
        expect(screen.queryByText(/confirmar tu correo|confirmado tu correo/)).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: /Reenviar/ })).not.toBeInTheDocument();
        // Un 403 no cierra ni renueva la sesión: solo el refresh del arranque sin sesión.
        expect(fetchMock.mock.calls.filter(([url]) => String(url) === '/api/auth/refresh')).toHaveLength(1);
        expect(screen.getByText('ruta:/login')).toBeInTheDocument();
      },
    );
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
      // Una cuenta pendiente que reintenta con la contraseña correcta acaba aquí: también lleva la pista,
      // y su botón de reenvío no depende del bloqueo del login.
      expect(screen.getByText('¿Te acabas de registrar o aún no has confirmado tu correo?')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Reenviar el correo de confirmación' })).toBeEnabled();

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
      // La pista se va con el aviso al que acompaña: sola no tendría contexto.
      expect(screen.queryByText(/aún no has confirmado tu correo/)).not.toBeInTheDocument();
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
