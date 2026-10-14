/**
 * Tests de `RouteChangeFocus`: qué pasa con el scroll y el foco al cambiar de página.
 *
 * Protegen: que una navegación nueva (`PUSH`/`REPLACE`) sube el scroll y lleva
 * el foco al `<main id="contenido">`; que «Atrás» (`POP`) no toca el scroll
 * (lo restaura el navegador) pero sí anuncia la página; que cambiar solo la
 * búsqueda (`?genero=`, `?temporada=`) no hace nada; que no actúa al montarse
 * (el primer Tab sigue siendo «Saltar al contenido»); y que respeta el foco
 * que la página nueva ya haya puesto dentro del contenido.
 *
 * jsdom no tiene maquetación ni scroll: `window.scrollTo` es un espía.
 */
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useLayoutEffect, useRef } from 'react';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RouteChangeFocus } from './RouteChangeFocus';
import { MAIN_CONTENT_ID } from './SkipLink';

/** Página que, como un formulario, pone el foco en su campo al montarse. */
function SelfFocusingPage() {
  const input = useRef<HTMLInputElement>(null);
  useLayoutEffect(() => input.current?.focus(), []);
  return <input ref={input} aria-label="Campo de la página" />;
}

/** Barra con botones que navegan (fuera del `<main>`, como la de verdad). */
function Nav() {
  const navigate = useNavigate();
  return (
    <nav>
      <button type="button" onClick={() => navigate('/b')}>
        Ir a B
      </button>
      <button type="button" onClick={() => navigate('/a?genero=3')}>
        Filtrar A
      </button>
      <button type="button" onClick={() => navigate('/b', { replace: true })}>
        Sustituir por B
      </button>
      <button type="button" onClick={() => navigate('/formulario')}>
        Ir al formulario
      </button>
      <button type="button" onClick={() => navigate(-1)}>
        Atrás
      </button>
    </nav>
  );
}

function renderApp(initial = '/a') {
  const user = userEvent.setup();
  render(
    <MemoryRouter initialEntries={[initial]}>
      <Nav />
      <main id={MAIN_CONTENT_ID} tabIndex={-1}>
        <Routes>
          <Route path="/a" element={<h1>Página A</h1>} />
          <Route path="/b" element={<h1>Página B</h1>} />
          <Route path="/formulario" element={<SelfFocusingPage />} />
        </Routes>
      </main>
      <RouteChangeFocus />
    </MemoryRouter>,
  );
  return user;
}

const main = () => screen.getByRole('main');
const scrollTo = vi.fn();

beforeEach(() => {
  scrollTo.mockReset();
  vi.stubGlobal('scrollTo', scrollTo);
});

describe('RouteChangeFocus', () => {
  it('al montarse (primera página de la visita) no mueve el scroll ni el foco', () => {
    renderApp();

    expect(scrollTo).not.toHaveBeenCalled();
    expect(document.body).toHaveFocus();
  });

  it('una navegación nueva sube el scroll y lleva el foco al contenido (no se queda en la barra)', async () => {
    const user = renderApp();

    await user.click(screen.getByRole('button', { name: 'Ir a B' }));

    expect(screen.getByRole('heading', { name: 'Página B' })).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(main()).toHaveFocus();
  });

  it('también con `replace` (una redirección o «Intro» del buscador sobre otra página)', async () => {
    const user = renderApp();

    await user.click(screen.getByRole('button', { name: 'Sustituir por B' }));

    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(main()).toHaveFocus();
  });

  it('cambiar solo la búsqueda (un filtro, una temporada) no sube el scroll ni mueve el foco', async () => {
    const user = renderApp();
    const filter = screen.getByRole('button', { name: 'Filtrar A' });

    await user.click(filter);

    expect(screen.getByRole('heading', { name: 'Página A' })).toBeInTheDocument();
    expect(scrollTo).not.toHaveBeenCalled();
    expect(filter).toHaveFocus();
  });

  it('«Atrás» (POP) no sube el scroll (lo restaura el navegador), pero sí lleva el foco al contenido', async () => {
    const user = renderApp();
    await user.click(screen.getByRole('button', { name: 'Ir a B' }));
    scrollTo.mockReset();

    const back = screen.getByRole('button', { name: 'Atrás' });
    await user.click(back);

    expect(screen.getByRole('heading', { name: 'Página A' })).toBeInTheDocument();
    expect(scrollTo).not.toHaveBeenCalled();
    expect(main()).toHaveFocus();
  });

  it('respeta el foco que la página nueva ya ha puesto dentro del contenido', async () => {
    const user = renderApp();

    await user.click(screen.getByRole('button', { name: 'Ir al formulario' }));

    expect(scrollTo).toHaveBeenCalledWith(0, 0);
    expect(screen.getByRole('textbox', { name: 'Campo de la página' })).toHaveFocus();
  });

  it('si la página no tiene `<main>`, sube el scroll igualmente y no falla', () => {
    render(
      <MemoryRouter initialEntries={['/a']}>
        <Nav />
        <Routes>
          <Route path="/a" element={<h1>Página A</h1>} />
          <Route path="/b" element={<h1>Página B</h1>} />
        </Routes>
        <RouteChangeFocus />
      </MemoryRouter>,
    );

    act(() => screen.getByRole('button', { name: 'Ir a B' }).click());

    expect(screen.getByRole('heading', { name: 'Página B' })).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith(0, 0);
  });
});
