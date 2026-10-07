/**
 * Tests de `RegisterPage`.
 *
 * Protegen la validación por campo (accesible: `aria-invalid`, mensaje enlazado
 * con `aria-describedby`, foco al primer error), que los datos se recorten antes
 * de enviarse salvo la contraseña, y que los errores del servidor (400 con
 * `validationErrors`, 409 de duplicado, 429) se muestren donde corresponde.
 */
import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiCalls, errorResponse, jsonResponse, renderWithProviders } from '../test/helpers';
import { RegisterPage } from './RegisterPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
});

/** La página dentro de rutas reales, para poder comprobar la redirección tras registrarse. */
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
    fetchMock.mockImplementation(async () => jsonResponse({ id: 1 }, 201));
    const user = userEvent.setup();
    renderRegister();

    await user.type(username(), '  ana  ');
    await user.type(email(), '  ana@example.com ');
    await user.type(password(), 'Faro-nube-7 '); // 11 caracteres + 1 espacio = 12: válida solo si no se recorta
    await user.click(submit());

    await screen.findByRole('heading', { name: 'Pantalla de login' });
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

  it('éxito: avisa con un toast y lleva a /login', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({ id: 1 }, 201));
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByRole('heading', { name: 'Pantalla de login' })).toBeInTheDocument();
    expect(screen.getByText('ruta:/login')).toBeInTheDocument();
    expect(screen.getByText('Cuenta creada, ya puedes iniciar sesión.')).toBeInTheDocument();
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

  it('409 (usuario o correo duplicado): muestra el mensaje del servidor y conserva lo escrito', async () => {
    fetchMock.mockImplementation(async () =>
      errorResponse(409, 'USER_ALREADY_EXISTS', 'El correo ya está registrado'),
    );
    const user = userEvent.setup();
    renderRegister();

    await fillValid(user);
    await user.click(submit());

    expect(await screen.findByText('El correo ya está registrado')).toHaveAttribute('role', 'alert');
    expect(screen.getByText('ruta:/registro')).toBeInTheDocument(); // no navega
    expect(email()).toHaveValue('ana@example.com');
    expect(submit()).toBeEnabled();
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
