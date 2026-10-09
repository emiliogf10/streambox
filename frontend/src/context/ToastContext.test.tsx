/**
 * Tests del sistema de avisos (`ToastProvider` + `Toast`): cuándo se cierran solos.
 *
 * Protegen sobre todo el caso de la PESTAÑA OCULTA: con dos pestañas abiertas, la
 * de fondo recibe avisos («Se ha cerrado la sesión en otra pestaña…») que antes
 * caducaban sin que nadie pudiera leerlos. Ahora:
 * - un aviso que nace con la pestaña oculta no se pinta (ni empieza a contar)
 *   hasta que la pestaña se ve, y entonces dura sus segundos completos;
 * - uno que ya contaba cuando la pestaña se oculta se pausa y, al volver, sigue
 *   con el tiempo que le quedaba;
 * - al volver, cada aviso entra UNA vez en su región `aria-live` (un lector de
 *   pantalla lo anuncia una sola vez).
 *
 * Reloj falso manual con `Date` (la pausa mide el tiempo con `Date.now()`) y
 * visibilidad simulada con `setPageVisibility`.
 */
import { act, fireEvent, renderHook, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installManualTimers, passTime } from '../test/fakeTimers';
import { setPageVisibility } from '../test/pageVisibility';
import { ToastProvider, useToast } from './ToastContext';

/** Duraciones de `components/Toast` (se repiten a propósito: si cambian, el test lo dice). */
const INFO_MS = 5000;
const ERROR_MS = 9000;

let toast: ReturnType<typeof useToast>;

/** Monta el proveedor (con su zona de avisos) y guarda su API para lanzar avisos desde el test. */
function renderToasts() {
  const { result } = renderHook(() => useToast(), { wrapper: ToastProvider });
  toast = result.current;
}

/** Lanza un aviso (dentro de `act`, para que React lo pinte antes de volver al test). */
function show(kind: 'info' | 'error' | 'success', message: string) {
  act(() => toast[kind](message));
}

beforeEach(() => {
  installManualTimers({ withDate: true });
  renderToasts();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('Avisos sin cambios de visibilidad (como siempre)', () => {
  it('información y éxito se cierran a los 5 s; los errores, a los 9 s', () => {
    show('info', 'Guardado');
    show('error', 'Ha fallado');

    passTime(INFO_MS - 1);
    expect(screen.getByText('Guardado')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Guardado')).not.toBeInTheDocument();
    expect(screen.getByText('Ha fallado')).toBeInTheDocument();

    passTime(ERROR_MS - INFO_MS - 1);
    expect(screen.getByText('Ha fallado')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Ha fallado')).not.toBeInTheDocument();
  });

  it('el puntero encima lo pausa y, al salir, vuelve a contar la duración completa', () => {
    show('info', 'Guardado');
    passTime(4000);

    const card = screen.getByText('Guardado').closest('div')!;
    fireEvent.mouseEnter(card);
    passTime(60_000);
    expect(screen.getByText('Guardado')).toBeInTheDocument();

    fireEvent.mouseLeave(card);
    passTime(INFO_MS - 1);
    expect(screen.getByText('Guardado')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Guardado')).not.toBeInTheDocument();
  });

  it('se puede cerrar a mano', () => {
    show('info', 'Guardado');
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar aviso' }));
    expect(screen.queryByText('Guardado')).not.toBeInTheDocument();
  });
});

describe('Avisos con la pestaña oculta', () => {
  it('uno que nace con la pestaña oculta no caduca: al volver aparece y dura sus 5 s completos', () => {
    setPageVisibility('hidden');
    show('info', 'Se ha cerrado la sesión en otra pestaña.');

    // Mucho más que su duración, con la pestaña en segundo plano.
    passTime(INFO_MS * 10);

    setPageVisibility('visible');
    expect(screen.getByText('Se ha cerrado la sesión en otra pestaña.')).toBeInTheDocument();
    passTime(INFO_MS - 1);
    expect(screen.getByText('Se ha cerrado la sesión en otra pestaña.')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Se ha cerrado la sesión en otra pestaña.')).not.toBeInTheDocument();
  });

  it('uno visible que se oculta a mitad se pausa y, al volver, se cierra tras el tiempo que le quedaba', () => {
    show('error', 'Ha fallado');
    passTime(3000);

    setPageVisibility('hidden');
    passTime(ERROR_MS * 10);
    expect(screen.getByText('Ha fallado')).toBeInTheDocument();

    setPageVisibility('visible');
    passTime(ERROR_MS - 3000 - 1);
    expect(screen.getByText('Ha fallado')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Ha fallado')).not.toBeInTheDocument();
  });

  it('varias idas y vueltas descuentan solo el tiempo que la pestaña estuvo a la vista', () => {
    show('info', 'Guardado');
    passTime(1000);
    setPageVisibility('hidden');
    passTime(20_000);
    setPageVisibility('visible');
    passTime(1000);
    setPageVisibility('hidden');
    passTime(20_000);
    setPageVisibility('visible');

    passTime(INFO_MS - 2000 - 1);
    expect(screen.getByText('Guardado')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Guardado')).not.toBeInTheDocument();
  });

  it('cada aviso entra UNA vez en su región aria-live: los que esperaban, al volver; los ya visibles no se repintan', () => {
    show('success', 'Añadido a tu lista');
    const alreadyShown = screen.getByText('Añadido a tu lista');

    setPageVisibility('hidden');
    show('info', 'Se ha iniciado sesión en otra pestaña como «carlos».');
    show('error', 'No se pudo cargar tu lista.');
    // Con la pestaña oculta no se insertan: un lector de pantalla no los anunciaría.
    expect(screen.queryByText(/otra pestaña/)).not.toBeInTheDocument();
    expect(screen.queryByText('No se pudo cargar tu lista.')).not.toBeInTheDocument();

    setPageVisibility('visible');
    const polite = screen.getByRole('status');
    const assertive = screen.getByRole('alert');
    expect(polite).toHaveTextContent('Se ha iniciado sesión en otra pestaña como «carlos».');
    expect(assertive).toHaveTextContent('No se pudo cargar tu lista.');
    expect(screen.getAllByText(/otra pestaña/)).toHaveLength(1);
    // El que ya se veía es el MISMO nodo (no se desmontó ni se volvió a insertar): no se anuncia otra vez.
    expect(screen.getByText('Añadido a tu lista')).toBe(alreadyShown);

    // Si la pestaña se vuelve a ocultar, lo ya pintado se queda (no se anunciaría de nuevo al volver).
    const revealed = screen.getByText(/otra pestaña/);
    setPageVisibility('hidden');
    setPageVisibility('visible');
    expect(screen.getByText(/otra pestaña/)).toBe(revealed);
  });
});
