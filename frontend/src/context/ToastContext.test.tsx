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
  it('información y éxito se cierran a los 5 s; los errores NO se cierran solos (B5), solo con «Cerrar aviso»', () => {
    show('info', 'Guardado');
    show('error', 'Ha fallado');

    passTime(INFO_MS - 1);
    expect(screen.getByText('Guardado')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Guardado')).not.toBeInTheDocument();
    expect(screen.getByText('Ha fallado')).toBeInTheDocument();

    // Antes se iba a los 9 s: si el usuario miraba a otro sitio, ya no podía releer el motivo.
    passTime(10 * 60_000);
    expect(screen.getByText('Ha fallado')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Cerrar aviso' }));
    expect(screen.queryByText('Ha fallado')).not.toBeInTheDocument();
  });

  it('la zona de avisos es una región con nombre («Avisos») que contiene las dos regiones vivas', () => {
    const region = screen.getByRole('region', { name: 'Avisos' });
    expect(region).toContainElement(screen.getByRole('status'));
    expect(region).toContainElement(screen.getByRole('alert'));
  });

  it('un aviso igual a uno que ya está lo sustituye en lugar de apilarse', () => {
    show('error', 'No se pudo añadir a tu lista.');
    show('error', 'No se pudo añadir a tu lista.');
    show('error', 'Otro fallo');

    expect(screen.getAllByText('No se pudo añadir a tu lista.')).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Cerrar aviso' })).toHaveLength(2);
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
    show('success', 'Añadido');
    passTime(3000);

    setPageVisibility('hidden');
    passTime(INFO_MS * 10);
    expect(screen.getByText('Añadido')).toBeInTheDocument();

    setPageVisibility('visible');
    passTime(INFO_MS - 3000 - 1);
    expect(screen.getByText('Añadido')).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText('Añadido')).not.toBeInTheDocument();
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

/**
 * B4: cerrar con el teclado un aviso que tiene el foco no debe dejarlo en `<body>` (el botón pulsado desaparece).
 * Guía «persistent-toast-notifications»: al siguiente aviso de la pila o, si no queda ninguno, a donde estaba antes.
 */
describe('Foco al cerrar un aviso a mano', () => {
  /** Botón de la página desde el que se «entra» en la zona de avisos (como con Tab o F6). */
  function pageButton(): HTMLButtonElement {
    const button = document.createElement('button');
    button.textContent = 'Guardar';
    document.body.prepend(button);
    return button;
  }

  /** Lleva el foco de `from` a `to` como lo haría el navegador: con `relatedTarget` en el `focusin`. */
  function moveFocus(from: HTMLElement, to: HTMLElement) {
    to.focus();
    fireEvent.focusIn(to, { relatedTarget: from });
  }

  it('con más avisos en la pila, el foco pasa al «Cerrar aviso» del siguiente', () => {
    const origin = pageButton();
    origin.focus();
    show('error', 'Primer fallo');
    show('error', 'Segundo fallo');
    const [first, second] = screen.getAllByRole('button', { name: 'Cerrar aviso' });
    moveFocus(origin, first);

    fireEvent.click(first);

    expect(screen.queryByText('Primer fallo')).not.toBeInTheDocument();
    expect(second).toHaveFocus();
    origin.remove();
  });

  it('si era el último, el foco vuelve a donde estaba antes de entrar en los avisos', () => {
    const origin = pageButton();
    origin.focus();
    show('error', 'Ha fallado');
    const close = screen.getByRole('button', { name: 'Cerrar aviso' });
    moveFocus(origin, close);

    fireEvent.click(close);

    expect(screen.queryByText('Ha fallado')).not.toBeInTheDocument();
    expect(origin).toHaveFocus();
    origin.remove();
  });

  it('si ese elemento ya no está, va al contenido principal (`<main id="contenido">`)', () => {
    const main = document.createElement('main');
    main.id = 'contenido';
    main.tabIndex = -1;
    document.body.prepend(main);
    const origin = pageButton();
    show('error', 'Ha fallado');
    const close = screen.getByRole('button', { name: 'Cerrar aviso' });
    moveFocus(origin, close);
    origin.remove();

    fireEvent.click(close);

    expect(main).toHaveFocus();
    main.remove();
  });

  it('cerrar con el ratón un aviso SIN foco no mueve el foco de donde esté', () => {
    const origin = pageButton();
    origin.focus();
    show('info', 'Guardado');

    fireEvent.click(screen.getByRole('button', { name: 'Cerrar aviso' }));

    expect(origin).toHaveFocus();
    origin.remove();
  });
});
