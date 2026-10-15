/**
 * Tests de `PosterRow`: las flechas «Ver anteriores/siguientes» de una fila
 * horizontal solo aparecen cuando la fila desborda (hay algo que desplazar).
 *
 * Origen: en una pantalla ultraancha la fila «Novedades» (12 pósters) cabía
 * entera y aun así enseñaba las flechas. jsdom no maqueta, así que aquí se
 * simulan las medidas de la fila (`scrollWidth`, `clientWidth`, `scrollLeft`)
 * y el `ResizeObserver` (`test/fakeResizeObserver.ts`). Lo mismo en un
 * navegador de verdad, con 3440 y 1280 px, lo comprueba `e2e/catalog.spec.ts`.
 *
 * Protegen: sin desbordamiento no hay flechas (al montar y al cambiar el
 * tamaño); con él, sí, desactivadas en cada extremo; el alto reservado en la
 * cabecera (el título no salta); que se mida otra vez al cambiar los títulos;
 * y que el foco no se pierda si la flecha enfocada desaparece.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FakeResizeObserver, installFakeResizeObserver } from '../test/fakeResizeObserver';
import { PosterRow } from './PosterRow';

/** Medidas que «da» el navegador simulado para la fila; cada test las cambia a su gusto. */
const size = { scrollWidth: 0, clientWidth: 0, scrollLeft: 0 };

/** Cambia las medidas de la fila (lo que harían el ancho de la ventana, las tarjetas o el desplazamiento). */
function setSize(next: Partial<typeof size>) {
  Object.assign(size, next);
}

interface Item {
  id: number;
  title: string;
}

const items = (count: number): Item[] =>
  Array.from({ length: count }, (_, index) => ({ id: index + 1, title: `Título ${index + 1}` }));

function renderRow(rowItems: Item[] = items(3)) {
  const view = render(
    <PosterRow
      title="Novedades"
      items={rowItems}
      renderItem={(item) => <a href={`/peliculas/${item.id}`}>{item.title}</a>}
    />,
  );
  return { ...view, list: screen.getByRole('list') };
}

const previous = () => screen.queryByRole('button', { name: 'Ver anteriores en Novedades' });
const next = () => screen.queryByRole('button', { name: 'Ver siguientes en Novedades' });

beforeEach(() => {
  installFakeResizeObserver();
  setSize({ scrollWidth: 0, clientWidth: 0, scrollLeft: 0 });
  vi.spyOn(Element.prototype, 'scrollWidth', 'get').mockImplementation(() => size.scrollWidth);
  vi.spyOn(Element.prototype, 'clientWidth', 'get').mockImplementation(() => size.clientWidth);
  vi.spyOn(Element.prototype, 'scrollLeft', 'get').mockImplementation(() => size.scrollLeft);
});

describe('PosterRow: flechas según el desbordamiento', () => {
  it('si todas las tarjetas caben no pinta flechas, pero sí el título y las tarjetas', () => {
    setSize({ scrollWidth: 2400, clientWidth: 2400 });

    renderRow();

    expect(screen.getByRole('heading', { level: 2, name: 'Novedades' })).toBeInTheDocument();
    expect(screen.getAllByRole('link')).toHaveLength(3);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('una diferencia de un píxel (redondeo con zoom) no cuenta como desbordamiento', () => {
    setSize({ scrollWidth: 1001, clientWidth: 1000 });

    renderRow();

    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('si desborda pinta las dos flechas desde el montaje; al principio «anteriores» está desactivada', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });

    renderRow();

    expect(previous()).toHaveAttribute('aria-disabled', 'true');
    expect(next()).toHaveAttribute('aria-disabled', 'false');
  });

  it('al desplazar hasta el final se desactiva «siguientes» y se activa «anteriores»', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    const { list } = renderRow();

    setSize({ scrollLeft: 1200 });
    fireEvent.scroll(list);

    expect(previous()).toHaveAttribute('aria-disabled', 'false');
    expect(next()).toHaveAttribute('aria-disabled', 'true');
  });

  it('al ensanchar la ventana hasta que todo cabe desaparecen, y al estrecharla vuelven', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    renderRow();
    expect(next()).toBeInTheDocument();

    setSize({ scrollWidth: 2400, clientWidth: 2400 });
    act(() => FakeResizeObserver.triggerAll());
    expect(screen.queryAllByRole('button')).toHaveLength(0);

    setSize({ scrollWidth: 2000, clientWidth: 1000 });
    act(() => FakeResizeObserver.triggerAll());
    expect(previous()).toBeInTheDocument();
    expect(next()).toHaveAttribute('aria-disabled', 'false');
  });

  it('la cabecera reserva desde `md` el alto de las flechas: el título no salta al ocultarlas', () => {
    setSize({ scrollWidth: 800, clientWidth: 800 });
    renderRow();

    const header = screen.getByRole('heading', { name: 'Novedades' }).parentElement!.parentElement!;
    // `md:min-h-10` = 40 px, el alto de las flechas (`size-10`).
    expect(header).toHaveClass('md:min-h-10');
  });

  it('observa la fila y cada tarjeta, y al cambiar los títulos vuelve a medir y a observar', () => {
    setSize({ scrollWidth: 800, clientWidth: 800 });
    const { list, rerender } = renderRow(items(2));

    const [first] = FakeResizeObserver.instances;
    expect(first.observed.map(({ target }) => target)).toEqual([list, ...list.children]);
    expect(screen.queryAllByRole('button')).toHaveLength(0);

    // Llegan más títulos: ahora desbordan, sin esperar a ningún aviso de tamaño.
    setSize({ scrollWidth: 2000 });
    rerender(
      <PosterRow
        title="Novedades"
        items={items(8)}
        renderItem={(item) => <a href={`/peliculas/${item.id}`}>{item.title}</a>}
      />,
    );

    expect(next()).toBeInTheDocument();
    expect(first.disconnected).toBe(true);
    const latest = FakeResizeObserver.instances.at(-1)!;
    expect(latest.observed).toHaveLength(1 + 8);
  });

  it('si la flecha enfocada desaparece, el foco pasa a la primera tarjeta (no se pierde en el <body>)', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    renderRow();
    next()!.focus();
    expect(next()).toHaveFocus();

    setSize({ scrollWidth: 2400, clientWidth: 2400 });
    act(() => FakeResizeObserver.triggerAll());

    expect(next()).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Título 1' })).toHaveFocus();
  });

  it('si el foco estaba en otro sitio, ocultar las flechas no lo mueve', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    renderRow();
    const third = screen.getByRole('link', { name: 'Título 3' });
    third.focus();

    setSize({ scrollWidth: 2400, clientWidth: 2400 });
    act(() => FakeResizeObserver.triggerAll());

    expect(third).toHaveFocus();
  });

  it('«Ver siguientes» desplaza el 80 % del ancho visible; una flecha desactivada no hace nada', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    const { list } = renderRow();
    const scrollBy = vi.fn();
    list.scrollBy = scrollBy;

    fireEvent.click(next()!);
    fireEvent.click(previous()!);

    expect(scrollBy).toHaveBeenCalledTimes(1);
    expect(scrollBy).toHaveBeenCalledWith({ left: 640, behavior: 'smooth' });
  });

  it('sin títulos no pinta nada (ni título ni flechas)', () => {
    const { container } = render(<PosterRow title="Novedades" items={[]} renderItem={() => null} />);

    expect(container).toBeEmptyDOMElement();
  });
});

/**
 * Revisión QA: casos límite de las flechas que no cubrían los tests de arriba.
 * - Sin fugas: al desmontar, el `ResizeObserver` se desconecta y el oyente de
 *   `scroll` se quita (si no, una fila desmontada seguiría midiendo y llamando
 *   a `setState` sobre un componente que ya no existe).
 * - Datos que llegan después: la fila monta vacía (no pinta nada) y, al llegar
 *   los títulos, mide y enseña las flechas si desborda.
 * - Una sola tarjeta que cabe: sin flechas.
 * - La fila deja de desbordar porque LLEGAN MENOS títulos (no por un cambio de
 *   tamaño) con el foco en una flecha: el foco tampoco se pierde.
 */
describe('PosterRow: casos límite (revisión QA)', () => {
  it('al desmontar desconecta el ResizeObserver y deja de escuchar el desplazamiento', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    const { list, unmount } = renderRow();
    const removeListener = vi.spyOn(list, 'removeEventListener');
    const [observer] = FakeResizeObserver.instances;
    expect(observer.disconnected).toBe(false);

    unmount();

    expect(observer.disconnected).toBe(true);
    expect(removeListener).toHaveBeenCalledWith('scroll', expect.any(Function));
    expect(FakeResizeObserver.instances.every((instance) => instance.disconnected)).toBe(true);
  });

  it('monta sin títulos (datos aún cargando) y, al llegar y desbordar, enseña las flechas sin esperar a un aviso de tamaño', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800 });
    const { container, rerender } = render(
      <PosterRow title="Novedades" items={[] as Item[]} renderItem={(item) => <a href="#">{item.title}</a>} />,
    );
    expect(container).toBeEmptyDOMElement();

    rerender(
      <PosterRow title="Novedades" items={items(10)} renderItem={(item) => <a href={`/peliculas/${item.id}`}>{item.title}</a>} />,
    );

    expect(next()).toHaveAttribute('aria-disabled', 'false');
    expect(previous()).toHaveAttribute('aria-disabled', 'true');
    // Observa la fila ya montada y sus 10 tarjetas.
    const latest = FakeResizeObserver.instances.at(-1)!;
    expect(latest.observed).toHaveLength(1 + 10);
    expect(latest.disconnected).toBe(false);
  });

  it('una fila de un solo título que cabe no tiene flechas', () => {
    setSize({ scrollWidth: 176, clientWidth: 1280 });

    renderRow(items(1));

    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.queryAllByRole('button')).toHaveLength(0);
  });

  it('si la fila deja de desbordar porque llegan menos títulos y el foco estaba en una flecha, pasa a la primera tarjeta', () => {
    setSize({ scrollWidth: 2000, clientWidth: 800, scrollLeft: 1200 });
    const { rerender } = renderRow(items(8));
    previous()!.focus();
    expect(previous()).toHaveFocus();

    setSize({ scrollWidth: 800, clientWidth: 800, scrollLeft: 0 });
    rerender(
      <PosterRow title="Novedades" items={items(2)} renderItem={(item) => <a href={`/peliculas/${item.id}`}>{item.title}</a>} />,
    );

    expect(previous()).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Título 1' })).toHaveFocus();
  });
});
