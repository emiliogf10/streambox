/**
 * Tests de `RegisterPage`.
 *
 * Protegen la validación por campo (accesible: `aria-invalid`, mensaje enlazado
 * con `aria-describedby`, foco al primer error), que los datos se recorten antes
 * de enviarse salvo la contraseña, que los errores del servidor (400 con
 * `validationErrors`, 409 de nombre de usuario repetido, 429) se muestren donde
 * corresponde, y la pantalla «Revisa tu correo» que sigue al registro (202):
 * sus textos (que no afirman que la cuenta exista), el reenvío y la vuelta al
 * formulario.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RESEND_DONE_MESSAGE } from '../lib/emailVerification';
import { apiCalls, errorResponse, jsonResponse, renderWithProviders, routeFetch } from '../test/helpers';
import { RegisterPage } from './RegisterPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

/** Respuesta del registro: 202 con un texto que es siempre el mismo, exista o no el correo. */
const accepted = () => jsonResponse({ message: 'Si los datos son correctos, recibirás un correo.' }, 202);

/** La página dentro de rutas reales, para comprobar que el registro ya NO navega a otra pantalla. */
function renderRegister() {
  return renderWithProviders(
    <Routes>
      <Route path="/registro" element={<RegisterPage />} />
      <Route path="/login" element={<h1>Pantalla de login</h1>} />
    </Routes>,
    { route: '/registro' },
  );
}

const username = () => screen.getByLabelText('Nombre de usuario');
const email = () => screen.getByLabelText('Correo electrónico');
const password = () => screen.getByLabelText('Contraseña');
const submit = () => screen.getByRole('button', { name: 'Crear cuenta' });

/** Ayuda permanente del campo de contraseña: anuncia la política (12-64) y las reglas que solo comprueba el servidor. */
const PASSWORD_HINT =
  'Entre 12 y 64 caracteres. Evita contraseñas comunes y no incluyas tu usuario ni tu correo.';

describe('RegisterPage: validación en el cliente', () => {
  it('con todo vacío marca los tres campos como inválidos, no llama al servidor y enfoca el primero', async () => {
    const user = userEvent.setup();
    renderRegister();

    await user.click(submit());

    for (const field of [username(), email(), password()]) {
      expect(field).toHaveAttribute('aria-invalid', 'true');
    }
    // El error sustituye a la ayuda (ya dice el requisito): se describe el campo solo con él.
    expect(username()).toHaveAccessibleDescription('El nombre de usuario debe tener entre 3 y 50 caracteres.');
    expect(email()).toHaveAccessibleDescription('Introduce tu correo electrónico.');
    expect(password()).toHaveAccessibleDescription('La contraseña debe tener entre 12 y 64 caracteres.');
    expect(username()).toHaveFocus();
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('los mensajes de error se anuncian (role="alert") junto al campo, no solo con color', async () => {
    const user = userEvent.setup();
    renderRegister();

    await user.type(username(), 'ana');
    await user.type(email(), 'no-es-un-correo');
    await user.type(password(), 'Faro-nube-2026');
    await user.click(submit());

    const alert = screen.getByText('Introduce un correo electrónico válido.');
    expect(alert).toHaveAttribute('role', 'alert');
    expect(email()).toHaveAttribute('aria-invalid', 'true');
    expect(email()).toHaveFocus();
    expect(username()).not.toHaveAttribute('aria-invalid');
    expect(password()).not.toHaveAttribute('aria-invalid');
  });

  it('la ayuda describe el campo; con error se sustituye por el error (no se repiten) y vuelve al corregir', async () => {
    const user = userEvent.setup();
    renderRegister();

    expect(username()).toHaveAccessibleDescription('Entre 3 y 50 caracteres.');
    expect(screen.getByText('Entre 3 y 50 caracteres.')).toBeVisible();

    await user.click(submit());
    expect(screen.queryByText('Entre 3 y 50 caracteres.')).not.toBeInTheDocument();
    expect(screen.queryByText(PASSWORD_HINT)).not.toBeInTheDocument();
    expect(screen.getByText('El nombre de usuario debe tener entre 3 y 50 caracteres.')).toBeVisible();

    await user.type(username(), 'a');
    expect(username()).toHaveAccessibleDescription('Entre 3 y 50 caracteres.');
    expect(screen.queryByText('El nombre de usuario debe tener entre 3 y 50 caracteres.')).not.toBeInTheDocument();
  });

  it('la ayuda de la contraseña anuncia la política nueva (12-64) y las reglas que comprueba el servidor', () => {
    renderRegister();

    expect(password()).toHaveAccessibleDescription(PASSWORD_HINT);
  });

  it('una contraseña de 11 caracteres (o de 8, como las antiguas) ya no basta y no llega al servidor', async () => {
    const user = userEvent.setup();
    renderRegister();
    await user.type(username(), 'ana');
    await user.type(email(), 'ana@example.com');

    for (const tooShort of ['Faro-nube-7', 'secreta1']) {
      await user.clear(password());
      await user.type(password(), tooShort);
      await user.click(submit());

      expect(password()).toHaveAttribute('aria-invalid', 'true');
      expect(password()).toHaveAccessibleDescription('La contraseña debe tener entre 12 y 64 caracteres.');
      expect(password()).toHaveFocus();
    }
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('una contraseña de 65 caracteres también se rechaza en el cliente', async () => {
    const user = userEvent.setup();
    renderRegister();
    await user.type(username(), 'ana');
    await user.type(email(), 'ana@example.com');
    await user.type(password(), 'x'.repeat(65));
    await user.click(submit());

    expect(password()).toHaveAccessibleDescription('La contraseña debe tener entre 12 y 64 caracteres.');
    expect(apiCalls(fetchMock)).toEqual([]);
  });

  it('empezar a corregir un campo borra su error al instante', async () => {
    const user = userEvent.setup();
    renderRegister();
    await user.click(submit());
    expect(username()).toHaveAttribute('aria-invalid', 'true');

    await user.type(username(), 'a');

    expect(username()).not.toHaveAttribute('aria-invalid');
    // El resto de errores siguen hasta que se corrijan.
    expect(email()).toHaveAttribute('aria-invalid', 'true');
  });

  it('recorta usuario y correo pero NO la contraseña (los espacios cuentan)', async () => {
    fetchMock.mockImplementation(async () => accepted());
    const user = userEvent.setup();
    renderRegister();

    await user.type(username(), '  ana  ');
    await user.type(email(), '  ana@example.com ');
    await user.type(password(), 'Faro-nube-7 '); // 11 caracteres + 1 espacio = 12: válida solo si no se recorta
    await user.click(submit());

    await screen.findByRole('heading', { name: 'Revisa tu correo' });
    const [url, init] = apiCalls(fetchMock)[0];
    expect(url).toBe('/api/users');
    expect(init?.method).toBe('POST');
    expect(JSON.parse(String(init?.body))).toEqual({
      username: 'ana',
      email: 'ana@example.com',
      password: 'Faro-nube-7 ',
    });
  });

  it('un usuario de 2 letras rodeado de espacios sigue siendo inválido tras recortar', async () => {
    const user = userEvent.setup();
    renderRegister();

    await user.type(username(), '  ab  ');
    await user.type(email(), 'ana@example.com');
    await user.type(password(), 'Faro-nube-2026');
    await user.click(submit());

    expect(username()).toHaveAttribute('aria-invalid', 'true');
    expect(apiCalls(fetchMock)).toEqual([]);
  });
});

describe('RegisterPage: respuesta del servidor', () => {
  async function fillValid(user: ReturnType<typeof userEvent.setup>) {
    await user.type(username(), 'ana');
    await user.type(email(), 'ana@example.com');
    await user.type(password(), 'Faro-nube-2026');
  }

  it('éxito (202): pantalla «Revisa tu correo» con el correo escrito, sin navegar ni afirmar que la cuenta existe', async () => {
    fetchMock.mockImplementation(async () => accepted());
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    const heading = await screen.findByRole('heading', { level: 1, name: 'Revisa tu correo' });
    // El formulario ya no existe: el foco va al título para que se anuncie la pantalla nueva.
    expect(heading).toHaveFocus();
    expect(document.title).toBe('Revisa tu correo — StreamBox');
    expect(screen.getByText('ruta:/registro')).toBeInTheDocument();
    expect(screen.getByText('ana@example.com')).toBeInTheDocument();
    expect(
      screen.getByText(
        /es correcta, en unos minutos te llegará un correo con un enlace para confirmar tu cuenta\. El enlace caduca en 24 horas\./,
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByText('Si ya tenías una cuenta con ese correo, te lo recordaremos en ese mensaje: no hace falta crear otra.'),
    ).toBeInTheDocument();
    // Una cuenta que ya existía conserva SU contraseña (verificada, pendiente con el enlace vigente o heredada de
    // antes de la verificación, que nunca recibió enlace): se avisa para que nadie intente entrar con la nueva.
    // La única excepción es la que el servidor sustituye: un registro cuyo enlace de alta (y los posteriores)
    // ya caducó. Una cuenta heredada no tuvo enlace de alta, así que la condición no la incluye.
    expect(
      screen.getByText(
        'Si este correo ya tenía una cuenta, la contraseña que vale es la de esa cuenta, no la que acabas de escribir. ' +
          'Solo hay una excepción: si ya te habías registrado, nunca confirmaste el correo y el enlace que te enviamos ' +
          'al registrarte (y cualquier otro que pidieras) ya ha caducado, aquel registro se sustituye por este y vale ' +
          'la contraseña nueva.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/la de entonces/)).not.toBeInTheDocument();
    expect(screen.getByText(/Mira en la carpeta de spam/)).toBeInTheDocument();
    // La respuesta es igual exista o no el correo: nada puede decir que la cuenta se ha creado.
    expect(screen.queryByText(/cuenta creada/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/te hemos enviado/i)).not.toBeInTheDocument();
    // Ni se enseña tal cual el texto del servidor.
    expect(screen.queryByText('Si los datos son correctos, recibirás un correo.')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Inicia sesión' })).toHaveAttribute('href', '/login');
  });

  it('«Corregir el correo» vuelve al formulario con lo escrito y el foco en el correo', async () => {
    fetchMock.mockImplementation(async () => accepted());
    const user = userEvent.setup();
    renderRegister();
    await fillValid(user);
    await user.click(submit());
    await screen.findByRole('heading', { name: 'Revisa tu correo' });

    await user.click(screen.getByRole('button', { name: 'Corregir el correo' }));

    expect(screen.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeInTheDocument();
    expect(email()).toHaveValue('ana@example.com');
    expect(username()).toHaveValue('ana');
    expect(email()).toHaveFocus();
    expect(document.title).toBe('Crear cuenta — StreamBox');
  });

  it('mientras envía bloquea el botón', async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByRole('button', { name: 'Creando cuenta...' })).toBeDisabled();
  });

  it('400 con validationErrors: cada mensaje va junto a su campo y el foco al primero', async () => {
    fetchMock.mockImplementation(async () =>
      errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
        validationErrors: { email: 'El correo ya tiene un formato inválido', password: 'Contraseña demasiado débil' },
      }),
    );
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByText('El correo ya tiene un formato inválido')).toHaveAttribute('role', 'alert');
    expect(email()).toHaveAttribute('aria-invalid', 'true');
    expect(email()).toHaveAccessibleDescription('El correo ya tiene un formato inválido');
    expect(password()).toHaveAccessibleDescription(/Contraseña demasiado débil/);
    expect(username()).not.toHaveAttribute('aria-invalid');
    expect(email()).toHaveFocus();
    // Aviso general de formulario con instrucciones.
    expect(screen.getByText('Revisa los campos marcados.')).toBeInTheDocument();
  });

  it('contraseña común (o que contiene el usuario/correo): el MOTIVO del servidor va junto al campo de contraseña', async () => {
    // El cliente no conoce la lista de contraseñas comunes: el servidor la rechaza y explica por qué.
    const reason = 'La contraseña es demasiado común. Elige otra más difícil de adivinar.';
    fetchMock.mockImplementation(async () =>
      errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', { validationErrors: { password: reason } }),
    );
    const user = userEvent.setup();
    renderRegister();

    await user.type(username(), 'ana');
    await user.type(email(), 'ana@example.com');
    await user.type(password(), 'contraseña123');
    await user.click(submit());

    expect(await screen.findByText(reason)).toHaveAttribute('role', 'alert');
    expect(password()).toHaveAttribute('aria-invalid', 'true');
    expect(password()).toHaveAccessibleDescription(reason);
    expect(password()).toHaveFocus();
    expect(username()).not.toHaveAttribute('aria-invalid');
    expect(screen.getByText('ruta:/registro')).toBeInTheDocument();
    // Al corregirla, el error del servidor desaparece y vuelve la ayuda.
    await user.type(password(), 'x');
    expect(password()).toHaveAccessibleDescription(PASSWORD_HINT);
  });

  it('400 con un error de un campo que no existe en el formulario: se muestra como aviso del formulario', async () => {
    fetchMock.mockImplementation(async () =>
      errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
        validationErrors: { role: 'El rol no se puede elegir' },
      }),
    );
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByText('El rol no se puede elegir')).toHaveAttribute('role', 'alert');
    expect(screen.queryByText('Revisa los campos marcados.')).not.toBeInTheDocument();
  });

  it('409 USER_ALREADY_EXISTS (nombre de usuario repetido): el mensaje va junto al nombre y se conserva lo escrito', async () => {
    fetchMock.mockImplementation(async () =>
      errorResponse(409, 'USER_ALREADY_EXISTS', 'El nombre de usuario ya está en uso'),
    );
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByText('El nombre de usuario ya está en uso')).toHaveAttribute('role', 'alert');
    expect(username()).toHaveAttribute('aria-invalid', 'true');
    expect(username()).toHaveAccessibleDescription('El nombre de usuario ya está en uso');
    expect(username()).toHaveFocus();
    expect(email()).not.toHaveAttribute('aria-invalid');
    expect(screen.getByText('Revisa los campos marcados.')).toBeInTheDocument();
    expect(screen.getByText('ruta:/registro')).toBeInTheDocument(); // no navega
    expect(email()).toHaveValue('ana@example.com');
    expect(submit()).toBeEnabled();
  });

  /** El primer registro se acepta (202) y el siguiente da 409 de nombre, como tras «Corregir el correo». */
  function acceptThenUsernameTaken() {
    let registrations = 0;
    routeFetch(fetchMock, {
      'POST /api/users': () => {
        registrations += 1;
        return registrations === 1
          ? accepted()
          : errorResponse(409, 'USER_ALREADY_EXISTS', 'El nombre de usuario ya está en uso');
      },
    });
  }

  it('409 de nombre tras «Corregir el correo» con el MISMO nombre: explica que lo reserva el registro anterior', async () => {
    acceptThenUsernameTaken();
    const user = userEvent.setup();
    renderRegister();
    await fillValid(user);
    await user.click(submit());
    await user.click(await screen.findByRole('button', { name: 'Corregir el correo' }));

    await user.clear(email());
    await user.type(email(), 'ana@ejemplo.com');
    await user.click(submit());

    const message =
      'El nombre de usuario ya está en uso. Si es el que usaste hace un momento, lo tiene reservado aquel registro ' +
      'hasta que caduque su enlace (24 h): elige otro.';
    expect(await screen.findByText(message)).toHaveAttribute('role', 'alert');
    expect(username()).toHaveAccessibleDescription(message);
    expect(username()).toHaveFocus();
  });

  it('409 de nombre tras «Corregir el correo» con OTRO nombre: solo el mensaje del servidor (la pista no sería verdad)', async () => {
    acceptThenUsernameTaken();
    const user = userEvent.setup();
    renderRegister();
    await fillValid(user);
    await user.click(submit());
    await user.click(await screen.findByRole('button', { name: 'Corregir el correo' }));

    await user.type(username(), '_bis');
    await user.click(submit());

    expect(await screen.findByText('El nombre de usuario ya está en uso')).toHaveAttribute('role', 'alert');
    expect(username()).toHaveAccessibleDescription('El nombre de usuario ya está en uso');
    expect(screen.queryByText(/reservado/)).not.toBeInTheDocument();
  });

  it('un 409 con otro código no se atribuye al nombre: se enseña como aviso del formulario', async () => {
    fetchMock.mockImplementation(async () => errorResponse(409, 'CONFLICT', 'Conflicto inesperado'));
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByText('Conflicto inesperado')).toHaveAttribute('role', 'alert');
    expect(username()).not.toHaveAttribute('aria-invalid');
  });

  it('429: bloquea el botón con cuenta atrás y lo reactiva al terminar', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    fetchMock.mockImplementation(async () =>
      errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '5' }),
    );
    const user = userEvent.setup({ delay: null });
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByRole('button', { name: 'Reintentar en 5 s' })).toBeDisabled();

    act(() => {
      vi.advanceTimersByTime(5_000);
    });
    expect(screen.getByRole('button', { name: 'Crear cuenta' })).toBeEnabled();
  });
});

describe('RegisterPage: «Revisa tu correo» y reenviar el correo', () => {
  /** Solo se falsean el intervalo y `Date` de la cuenta atrás; el resto sigue en tiempo real para `findBy*`. */
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
  });

  const RESEND_URL = '/api/auth/email-verification/resend';

  /** Registra (202) y deja la pantalla «Revisa tu correo»; `resend` responde al reenvío. */
  async function registerAndWait(resend: () => Response | Promise<Response>) {
    fetchMock.mockImplementation(async (input) => (String(input) === RESEND_URL ? resend() : accepted()));
    const user = userEvent.setup({ delay: null });
    renderRegister();
    await user.type(username(), 'ana');
    await user.type(email(), ' ana@example.com ');
    await user.type(password(), 'Faro-nube-2026');
    await user.click(submit());
    await screen.findByRole('heading', { name: 'Revisa tu correo' });
    return user;
  }

  const resendCalls = () => apiCalls(fetchMock).filter(([url]) => url === RESEND_URL);

  it('el botón empieza en espera un minuto (acaba de salir un correo) y luego reenvía al correo escrito', async () => {
    const user = await registerAndWait(() => jsonResponse({ message: 'x' }, 202));

    // Reenviar al instante invalidaría el enlace que va de camino.
    expect(screen.getByRole('button', { name: 'Reenviar en 1 min' })).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(15_000);
    });
    expect(screen.getByRole('button', { name: 'Reenviar en 45 s' })).toBeDisabled();
    act(() => {
      vi.advanceTimersByTime(45_000);
    });
    expect(resendCalls()).toEqual([]);

    await user.click(screen.getByRole('button', { name: 'Reenviar el correo' }));

    const done = await screen.findByText(RESEND_DONE_MESSAGE('ana@example.com'));
    expect(done.closest('[role="status"]')).not.toBeNull();
    expect(RESEND_DONE_MESSAGE('ana@example.com')).toBe(
      'Si hay una cuenta pendiente de confirmar con ana@example.com, te llegará un enlace nuevo en unos minutos, ' +
        'salvo que ya hayas pedido varios hace poco. Usa siempre el del correo más reciente.',
    );
    const [[url, init]] = resendCalls();
    expect(url).toBe(RESEND_URL);
    expect(init?.method).toBe('POST');
    // El correo recortado, el mismo que se registró.
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@example.com' });
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
    // Y vuelve a esperar otro minuto antes de dejar pedir otro.
    expect(screen.getByRole('button', { name: 'Reenviar en 1 min' })).toBeDisabled();
  });

  it('429: avisa de la espera de Retry-After, bloquea el botón y retira el aviso al terminar', async () => {
    const user = await registerAndWait(() =>
      errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '120' }),
    );
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    await user.click(screen.getByRole('button', { name: 'Reenviar el correo' }));

    expect(
      await screen.findByText('Has pedido demasiados correos seguidos. Podrás pedir otro en 2 min.'),
    ).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('button', { name: 'Reenviar en 2 min' })).toBeDisabled();
    expect(screen.queryByText(/te llegará un enlace nuevo/)).not.toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(120_000);
    });
    expect(screen.getByRole('button', { name: 'Reenviar el correo' })).toBeEnabled();
    expect(screen.queryByText(/demasiados correos/)).not.toBeInTheDocument();
  });

  it('429 sin Retry-After: lo explica sin inventar una espera y deja volver a intentarlo', async () => {
    const user = await registerAndWait(() => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x'));
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    await user.click(screen.getByRole('button', { name: 'Reenviar el correo' }));

    expect(
      await screen.findByText('Has pedido demasiados correos seguidos. Inténtalo de nuevo más tarde.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reenviar el correo' })).toBeEnabled();
  });

  it('red caída al reenviar: lo dice y deja reintentar', async () => {
    const user = await registerAndWait(() => {
      throw new TypeError('Failed to fetch');
    });
    act(() => {
      vi.advanceTimersByTime(60_000);
    });

    await user.click(screen.getByRole('button', { name: 'Reenviar el correo' }));

    expect(await screen.findByText(/No se pudo conectar con el servidor/)).toHaveAttribute('role', 'alert');
    expect(screen.getByRole('button', { name: 'Reenviar el correo' })).toBeEnabled();
  });
});
