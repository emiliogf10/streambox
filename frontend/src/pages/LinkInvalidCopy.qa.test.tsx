/**
 * Revisión independiente (QA) de los textos del estado «El enlace no es válido o
 * ha caducado» de `/restablecer-contrasena` y `/verificar-email`.
 *
 * Qué protege: las dos peticiones de un enlace nuevo
 * (`POST /api/auth/password-reset` y `POST /api/auth/email-verification/resend`)
 * responden SIEMPRE el mismo 202 y solo envían algo si hay una cuenta (pendiente,
 * en la verificación) con ese correo **y** no ha saltado el límite silencioso por
 * cuenta (3 por hora, 1 por minuto). Por eso ningún texto puede prometer el envío
 * sin condición: «escribe tu correo y te enviaremos un enlace nuevo» es falso para
 * un correo sin cuenta, para una cuenta ya confirmada (en la verificación) y para
 * quien ya pidió varios hace poco (justo quien suele acabar en «enlace no válido»).
 * El aviso que sale tras pedirlo (`RESET_REQUEST_DONE_MESSAGE`,
 * `RESEND_DONE_MESSAGE`) ya lo dice bien («Si hay una cuenta con…»); el párrafo
 * de encima debe ser igual de honesto (cap. 20.9 del manual).
 */
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppRoutes } from '../App';
import { renderWithProviders, routeFetch } from '../test/helpers';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Promesa de envío sin condición que el 202 «siempre igual» no permite hacer. */
const UNCONDITIONAL_PROMISE = /te enviaremos/i;

describe('Textos de «enlace no válido» (QA): no prometen un envío que el servidor no garantiza', () => {
  it.each([
    ['/restablecer-contrasena', false],
    ['/restablecer-contrasena', true],
    ['/verificar-email', false],
    ['/verificar-email', true],
  ])('%s sin token (con sesión: %s) no dice «te enviaremos un enlace nuevo»', async (route, session) => {
    routeFetch(fetchMock, {});
    renderWithProviders(<AppRoutes />, { route, session });

    const main = await screen.findByRole('main');
    await screen.findByRole('heading', { level: 1, name: 'El enlace no es válido o ha caducado' });
    // Sigue habiendo una forma de pedir otro (la salida no se pierde)...
    expect(screen.getByRole('button', { name: 'Enviar un enlace nuevo' })).toBeInTheDocument();
    // ...pero sin afirmar que vaya a llegar: puede no haber cuenta o haber saltado el límite silencioso.
    expect(main.textContent ?? '').not.toMatch(UNCONDITIONAL_PROMISE);
  });
});
