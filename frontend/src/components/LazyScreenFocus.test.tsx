/**
 * Tests de `FallbackFocusHandoff` + `RestoreContentFocus`: al navegar por
 * primera vez a una pantalla diferida que trae su propio `<main>`, el foco que
 * `RouteChangeFocus` puso en el `<main>` de la espera pasa al `<main>` nuevo en
 * lugar de caer en `<body>`; al entrar directamente con la dirección, no se
 * toca (el primer Tab sigue siendo «Saltar al contenido»).
 */
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { lazy, Suspense } from 'react';
import type { ComponentType, ReactNode } from 'react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { FallbackFocusHandoff, RestoreContentFocus } from './LazyScreenFocus';
import { RouteChangeFocus } from './RouteChangeFocus';
import { MAIN_CONTENT_ID } from './SkipLink';

/** `<main id="contenido">` enfocable por código, como el de `AuthLayout` o `GuardScreen`. */
function Main({ children }: { children: ReactNode }) {
  return (
    <main id={MAIN_CONTENT_ID} tabIndex={-1}>
      {children}
    </main>
  );
}

/** Pantalla diferida cuya descarga termina cuando el test llama a `finish`. */
function deferredScreen() {
  let finish: () => void = () => {};
  const Screen = lazy(
    () =>
      new Promise<{ default: ComponentType }>((resolve) => {
        finish = () =>
          resolve({
            default: () => (
              <Main>
                <h1>Pantalla nueva</h1>
              </Main>
            ),
          });
      }),
  );
  return { Screen, finish: () => finish() };
}

/** Rutas: `/` con un enlace y `/nueva`, la diferida, montada como `PublicPage` en `App.tsx`. */
function renderRoutes(Screen: ComponentType, initial: string, withRestore = true) {
  render(
    <MemoryRouter initialEntries={[initial]}>
      <Routes>
        <Route
          path="/"
          element={
            <Main>
              <Link to="/nueva">Ir</Link>
            </Main>
          }
        />
        <Route
          path="/nueva"
          element={
            <Suspense
              fallback={
                <FallbackFocusHandoff>
                  <Main>Cargando...</Main>
                </FallbackFocusHandoff>
              }
            >
              <Screen />
              {withRestore && <RestoreContentFocus />}
            </Suspense>
          }
        />
      </Routes>
      <RouteChangeFocus />
    </MemoryRouter>,
  );
}

describe('Foco al cargar una pantalla diferida', () => {
  it('al llegar navegando, el foco pasa del <main> de la espera al <main> de la pantalla', async () => {
    const { Screen, finish } = deferredScreen();
    const user = userEvent.setup();
    renderRoutes(Screen, '/');

    await user.click(screen.getByRole('link', { name: 'Ir' }));
    expect(await screen.findByText('Cargando...')).toHaveFocus();

    await act(async () => finish());
    await screen.findByRole('heading', { name: 'Pantalla nueva' });
    expect(screen.getByRole('main')).toHaveFocus();
  });

  it('control: sin RestoreContentFocus el foco se pierde en <body> (el fallo que se corrige)', async () => {
    const { Screen, finish } = deferredScreen();
    const user = userEvent.setup();
    renderRoutes(Screen, '/', false);

    await user.click(screen.getByRole('link', { name: 'Ir' }));
    await screen.findByText('Cargando...');
    await act(async () => finish());
    await screen.findByRole('heading', { name: 'Pantalla nueva' });
    expect(document.body).toHaveFocus();
  });

  it('al entrar directamente con la dirección no mueve el foco (el primer Tab sigue siendo «Saltar al contenido»)', async () => {
    const { Screen, finish } = deferredScreen();
    renderRoutes(Screen, '/nueva');

    await screen.findByText('Cargando...');
    await act(async () => finish());
    await screen.findByRole('heading', { name: 'Pantalla nueva' });
    expect(document.body).toHaveFocus();
  });
});
