/**
 * Tests de `AvatarForm` (selector del icono de perfil) montado solo, con la
 * sesión y los avisos reales y `fetch` simulado.
 *
 * Lo básico del selector (las 13 opciones, el teclado, guardar un avatar o «Tu
 * inicial», cerrar sin petición si no cambia, los errores 400/red/429/500 y que
 * la barra se actualice) ya lo prueba `pages/ProfilePage.test.tsx` a través de
 * la página: aquí no se repite. Estos tests cubren lo que allí no se ve:
 *
 * - **Clave desconocida** (p. ej. un avatar que añadió una versión más nueva de
 *   la interfaz): se marca «Tu inicial», que es lo que se pinta, y «Guardar
 *   icono» sin tocar nada cierra SIN petición. Antes enviaba `{ avatar: null }`:
 *   borraba la clave en el servidor y avisaba de un cambio que no se veía.
 * - Mientras guarda, «Guardando...» y los dos botones desactivados (no hay doble envío).
 * - Con la sesión caducada (401 y refresh fallido) el formulario no pinta su
 *   propio error ni se cierra: lo resuelve `apiFetch` con su aviso.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiCalls, errorResponse, jsonResponse, makeUser, renderWithProviders, routeFetch } from '../../test/helpers';
import { AvatarForm } from './AvatarForm';

const fetchMock = vi.fn<typeof fetch>();
const PATCH_ME = 'PATCH /api/users/me';

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/**
 * Llamadas `PATCH /api/users/me` hechas hasta ahora. Se filtra sobre todas las
 * llamadas y no sobre `apiCalls`, que descarta cualquier `/api/users/me` (el
 * chequeo de sesión del arranque) y dejaría vacías estas comprobaciones.
 */
const patchCalls = () =>
  fetchMock.mock.calls.filter(([url, init]) => String(url) === '/api/users/me' && init?.method === 'PATCH');

/**
 * Monta el formulario con el avatar guardado `currentAvatar` y las rutas extra de
 * `fetch`; devuelve user-event y el `onClose` espiado.
 */
function renderForm(currentAvatar: string | null, routes: Parameters<typeof routeFetch>[1] = {}) {
  routeFetch(fetchMock, routes);
  const onClose = vi.fn();
  const user = userEvent.setup();
  renderWithProviders(<AvatarForm id="avatar-form" username="ana" currentAvatar={currentAvatar} onClose={onClose} />, {
    session: true,
  });
  return { user, onClose, group: screen.getByRole('group', { name: 'Elige tu icono' }) };
}

describe('AvatarForm: clave guardada que la interfaz no conoce', () => {
  it('marca «Tu inicial» (lo que se ve) y «Guardar icono» sin tocar nada cierra SIN petición ni aviso', async () => {
    const { user, onClose, group } = renderForm('dragon');

    const initial = within(group).getByRole('radio', { name: 'Tu inicial' });
    expect(initial).toBeChecked();
    expect(initial).toHaveFocus();
    expect(screen.getByText('Vista previa:', { exact: false })).toHaveTextContent('Vista previa: Tu inicial');

    await user.click(screen.getByRole('button', { name: 'Guardar icono' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    // Ni `{ avatar: null }` (que borraría «dragon» en el servidor) ni ninguna otra petición.
    expect(patchCalls()).toHaveLength(0);
    expect(apiCalls(fetchMock)).toHaveLength(0);
    expect(screen.queryByText(/Icono de perfil cambiado/)).not.toBeInTheDocument();
  });

  it('probar otra opción y volver a «Tu inicial» tampoco envía nada: no hay cambio visible', async () => {
    const { user, onClose, group } = renderForm('dragon');

    await user.click(within(group).getByRole('radio', { name: 'Gato' }));
    await user.click(within(group).getByRole('radio', { name: 'Tu inicial' }));
    await user.click(screen.getByRole('button', { name: 'Guardar icono' }));

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(patchCalls()).toHaveLength(0);
  });

  it('elegir un avatar conocido sí lo guarda (envía solo { avatar: "cat" }) y avisa', async () => {
    const { user, onClose, group } = renderForm('dragon', {
      [PATCH_ME]: () => jsonResponse(makeUser({ avatar: 'cat' })),
    });

    await user.click(within(group).getByRole('radio', { name: 'Gato' }));
    await user.click(screen.getByRole('button', { name: 'Guardar icono' }));

    expect(await screen.findByText('Icono de perfil cambiado a «Gato».')).toBeInTheDocument();
    const [call] = patchCalls();
    expect(JSON.parse(String(call[1]?.body))).toEqual({ avatar: 'cat' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('AvatarForm: envío', () => {
  it('mientras guarda dice «Guardando...», desactiva los dos botones y no envía dos veces', async () => {
    let answer: (response: Response) => void = () => {};
    const { user, onClose, group } = renderForm('sun', {
      [PATCH_ME]: () =>
        new Promise<Response>((resolve) => {
          answer = resolve;
        }),
    });

    await user.click(within(group).getByRole('radio', { name: 'Luna' }));
    await user.click(screen.getByRole('button', { name: 'Guardar icono' }));

    const saving = await screen.findByRole('button', { name: 'Guardando...' });
    expect(saving).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled();
    await user.click(saving);
    expect(patchCalls()).toHaveLength(1);
    expect(onClose).not.toHaveBeenCalled();

    answer(jsonResponse(makeUser({ avatar: 'moon' })));

    await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Icono de perfil cambiado a «Luna».')).toBeInTheDocument();
  });

  it('401 con la sesión caducada: ni error propio ni cierre; el aviso de sesión caducada lo da apiFetch', async () => {
    const { user, onClose, group } = renderForm('sun', {
      [PATCH_ME]: () => errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'),
    });

    await user.click(within(group).getByRole('radio', { name: 'Hoja' }));
    await user.click(screen.getByRole('button', { name: 'Guardar icono' }));

    expect(await screen.findByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toBeInTheDocument();
    const form = screen.getByRole('form', { name: 'Cambiar icono de perfil' });
    expect(within(form).queryByRole('alert')).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByText(/Icono de perfil cambiado/)).not.toBeInTheDocument();
  });
});
