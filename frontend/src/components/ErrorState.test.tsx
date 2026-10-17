/**
 * Tests de `ErrorState` + `useRetryFocus`: adónde va el foco al pulsar «Reintentar».
 *
 * Al reintentar, el aviso se sustituye por la silueta de carga y el botón
 * desaparece. Sin el arreglo, el foco caía en `<body>` y quien usa teclado o
 * lector de pantalla perdía su sitio (WCAG 2.4.3). Se comprueba:
 * - que va a un elemento estable de la misma zona (el que se indique con
 *   `retryFocusRef`, o el antecesor enfocable más cercano; en último término, el `<main>`);
 * - que si el reintento vuelve a fallar, va al nuevo «Reintentar»;
 * - que se queda en la zona cuando llega el contenido;
 * - y que nunca roba el foco si el usuario ya se ha ido a otro sitio.
 *
 * Un componente de prueba (`Zone`) hace lo mismo que las páginas reales: pinta
 * el aviso, la carga o el contenido según su estado, y el test decide cuándo
 * termina el reintento y cómo.
 */
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useEffect, useRef, useState } from 'react';
import { describe, expect, it } from 'vitest';
import { ErrorState } from './ErrorState';

type Status = 'error' | 'loading' | 'ready';

/** Cambia el estado de la zona desde el test (el "resultado" del reintento). */
let setZoneStatus: (status: Status) => void = () => {};

interface ZoneProps {
  /** Cómo es la zona: con su propio contenedor enfocable, solo dentro del `<main>`, o con un encabezado explícito. */
  target: 'region' | 'main' | 'heading';
  /** Si `true`, «Reintentar» no quita el aviso (el test lo quita después). */
  deferLoading?: boolean;
}

function Zone({ target, deferLoading = false }: ZoneProps) {
  const [status, setStatus] = useState<Status>('error');
  const headingRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    setZoneStatus = setStatus;
  }, []);

  const body =
    status === 'error' ? (
      <ErrorState
        compact
        title="No se pudo cargar la zona"
        message="Comprueba tu conexión."
        onRetry={() => {
          if (!deferLoading) setStatus('loading');
        }}
        retryFocusRef={target === 'heading' ? headingRef : undefined}
      />
    ) : status === 'loading' ? (
      <p role="status">Cargando...</p>
    ) : (
      <a href="#detalle">Contenido cargado</a>
    );

  return (
    <main tabIndex={-1} aria-label="Contenido principal">
      <button type="button">Otro control</button>
      <h2 ref={headingRef} tabIndex={-1}>
        Mi zona
      </h2>
      {target === 'region' ? (
        <section aria-label="Zona estable" tabIndex={-1}>
          {body}
        </section>
      ) : (
        <div>{body}</div>
      )}
    </main>
  );
}

/** Pulsa «Reintentar» con el teclado (Tab hasta él e Intro), como quien no usa ratón. */
async function retryWithKeyboard(user: ReturnType<typeof userEvent.setup>) {
  const retry = screen.getByRole('button', { name: 'Reintentar' });
  retry.focus();
  await user.keyboard('{Enter}');
}

describe('ErrorState: el foco tras «Reintentar»', () => {
  it('va al contenedor estable más cercano de la zona (no a <body>) y se queda ahí cuando llega el contenido', async () => {
    const user = userEvent.setup();
    render(<Zone target="region" />);

    await retryWithKeyboard(user);

    expect(screen.getByRole('status')).toHaveTextContent('Cargando...');
    const region = screen.getByRole('region', { name: 'Zona estable' });
    await waitFor(() => expect(region).toHaveFocus());

    act(() => setZoneStatus('ready'));
    expect(screen.getByRole('link', { name: 'Contenido cargado' })).toBeInTheDocument();
    expect(region).toHaveFocus();
    // El siguiente Tab sigue desde la zona: entra en su contenido, no vuelve al principio de la página.
    await user.tab();
    expect(screen.getByRole('link', { name: 'Contenido cargado' })).toHaveFocus();
  });

  it('con `retryFocusRef`, va a ese elemento (p. ej. el encabezado de la sección)', async () => {
    const user = userEvent.setup();
    render(<Zone target="heading" />);

    await retryWithKeyboard(user);

    await waitFor(() => expect(screen.getByRole('heading', { name: 'Mi zona' })).toHaveFocus());
  });

  it('sin contenedor propio, va al <main> (también con un clic de ratón)', async () => {
    const user = userEvent.setup();
    render(<Zone target="main" />);

    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    await waitFor(() => expect(screen.getByRole('main')).toHaveFocus());
    expect(document.body).not.toHaveFocus();
  });

  it.each([['region' as const], ['heading' as const], ['main' as const]])(
    'si el reintento vuelve a fallar (zona: %s), el foco va al nuevo «Reintentar»',
    async (target) => {
      const user = userEvent.setup();
      render(<Zone target={target} />);

      await retryWithKeyboard(user);
      await waitFor(() => expect(document.body).not.toHaveFocus());
      act(() => setZoneStatus('error'));

      expect(screen.getByRole('button', { name: 'Reintentar' })).toHaveFocus();

      // Y vuelve a funcionar igual una segunda vez.
      await user.keyboard('{Enter}');
      expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
      await waitFor(() => expect(document.body).not.toHaveFocus());
      act(() => setZoneStatus('error'));
      expect(screen.getByRole('button', { name: 'Reintentar' })).toHaveFocus();
    },
  );

  it('no roba el foco: si el usuario se ha ido a otro control mientras carga, ni el fallo ni el éxito lo mueven', async () => {
    const user = userEvent.setup();
    render(<Zone target="region" />);

    await retryWithKeyboard(user);
    await waitFor(() => expect(screen.getByRole('region', { name: 'Zona estable' })).toHaveFocus());

    const other = screen.getByRole('button', { name: 'Otro control' });
    other.focus();
    act(() => setZoneStatus('error'));
    expect(other).toHaveFocus();

    // Tampoco más tarde: el aviso nuevo no "recuerda" el reintento anterior.
    await user.click(other);
    act(() => setZoneStatus('loading'));
    act(() => setZoneStatus('error'));
    expect(other).toHaveFocus();
  });

  it('no roba el foco: si el usuario se va antes de que desaparezca el botón, se queda donde está', async () => {
    const user = userEvent.setup();
    render(<Zone target="region" deferLoading />);

    await retryWithKeyboard(user);
    const other = screen.getByRole('button', { name: 'Otro control' });
    other.focus();
    act(() => setZoneStatus('loading'));
    await act(async () => {});

    expect(other).toHaveFocus();
  });

  it('un aviso que desaparece sin haber pulsado «Reintentar» no mueve el foco', async () => {
    render(<Zone target="region" />);
    const retry = screen.getByRole('button', { name: 'Reintentar' });
    retry.focus();

    act(() => setZoneStatus('ready'));
    await act(async () => {});

    expect(screen.getByRole('region', { name: 'Zona estable' })).not.toHaveFocus();
  });
});
