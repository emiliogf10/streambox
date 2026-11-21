/**
 * Tests de dónde se coloca la zona de avisos (`ToastViewport`) en móvil.
 *
 * El problema que protegen: los errores no se cierran solos (B5) y en móvil la
 * zona iba ARRIBA pegada al borde (`top-3`), así que un error de red tapaba la
 * barra entera (logo, pestañas, buscar y menú) hasta cerrarlo a mano, y el foco
 * podía quedar debajo. Ahora la zona de la página va justo debajo de la barra
 * (`--navbar-height`), sube con ella cuando se aparta (`navbar-away:`) y publica
 * su altura (`--toasts-height`) para que `index.css` la reserve al llevar el foco
 * a la vista. La de un diálogo sigue arriba del todo (la barra está tras el fondo).
 *
 * jsdom no maqueta: aquí se comprueban las clases y la variable; la posición real
 * a 375 px la mide el E2E `responsive.spec.ts`.
 */
import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeResizeObserver, installFakeResizeObserver } from '../test/fakeResizeObserver';
import { TOASTS_HEIGHT_VARIABLE, ToastViewport } from './ToastViewport';

const TOASTS = [{ id: 1, type: 'error' as const, message: 'No se pudo añadir a tu lista.' }];
const published = () => document.documentElement.style.getPropertyValue(TOASTS_HEIGHT_VARIABLE);
let height = 0;

beforeEach(() => {
  installFakeResizeObserver();
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(() => ({ height }) as DOMRect);
});

describe('ToastViewport: posición en móvil', () => {
  it('la de la página va DEBAJO de la barra (no encima) y sube con ella cuando se aparta', () => {
    render(<ToastViewport toasts={TOASTS} onDismiss={() => {}} />);
    const region = screen.getByRole('region', { name: 'Avisos' });

    expect(region).toHaveClass('top-[calc(var(--navbar-height,0px)+0.75rem)]');
    expect(region).not.toHaveClass('top-3');
    expect(region).toHaveClass('max-sm:navbar-away:-translate-y-(--navbar-height)');
    // Desde `sm`, abajo a la derecha como siempre.
    expect(region).toHaveClass('sm:top-auto', 'sm:bottom-4');
  });

  it('la de la página publica su altura en --toasts-height y la retira al desmontarse', () => {
    height = 92.2;
    const { unmount } = render(<ToastViewport toasts={TOASTS} onDismiss={() => {}} />);
    expect(published()).toBe('93px');

    height = 180;
    act(() => FakeResizeObserver.triggerAll());
    expect(published()).toBe('180px');

    unmount();
    expect(published()).toBe('');
  });

  it('la de un diálogo va arriba del todo y no publica su altura (no pisa la de la página)', () => {
    height = 92;
    render(<ToastViewport toasts={TOASTS} onDismiss={() => {}} inDialog />);
    const region = screen.getByRole('region', { name: 'Avisos' });

    expect(region).toHaveClass('top-3');
    expect(region).not.toHaveClass('max-sm:navbar-away:-translate-y-(--navbar-height)');
    expect(published()).toBe('');
  });
});
