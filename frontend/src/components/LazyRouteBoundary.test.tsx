/**
 * Tests de `LazyRouteBoundary`, el límite de errores de las pantallas que se
 * cargan bajo demanda: qué enseña según la causa (descarga fallida o error al
 * pintar), que «Recargar la página» llama a la recarga, que fuera de `AppShell`
 * el aviso tiene su propio `<main>` y que al cambiar de ruta se vuelve a
 * intentar pintar. El caso de punta a punta (las rutas reales con el trozo que
 * no llega) está en `App.lazyError.test.tsx` y en `e2e/lazy-chunks.spec.ts`.
 */
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChunkLoadError, importChunk } from '../lib/lazyChunks';
import { LazyRouteBoundary } from './LazyRouteBoundary';

/** Componente que falla al pintarse con el error que se le dé. */
function Throws({ error }: { error: unknown }): never {
  throw error;
}

beforeEach(() => {
  // React escribe en la consola cada error que captura un límite (es lo que se quiere en la app); aquí solo ensucia.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('LazyRouteBoundary', () => {
  it('si no se pudo descargar la pantalla, lo dice (conexión o versión nueva) y ofrece recargar la página', async () => {
    const onReload = vi.fn();
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <LazyRouteBoundary onReload={onReload}>
          <Throws error={new ChunkLoadError(new TypeError('Failed to fetch dynamically imported module'))} />
        </LazyRouteBoundary>
      </MemoryRouter>,
    );

    const alert = screen.getByRole('alert');
    expect(screen.getByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' })).toBeInTheDocument();
    expect(alert).toHaveTextContent(
      'Puede que se haya cortado la conexión o que StreamBox se haya actualizado mientras tenías la página abierta. Recarga la página para intentarlo de nuevo.',
    );
    // No es «Reintentar»: volver a pintar no sirve (React.lazy guarda el rechazo); el botón dice lo que hace.
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Recargar la página' }));
    expect(onReload).toHaveBeenCalledOnce();
  });

  it('un error al pintar de otra causa tampoco deja la página en blanco, y no se achaca a la conexión', () => {
    render(
      <MemoryRouter>
        <LazyRouteBoundary onReload={vi.fn()}>
          <Throws error={new Error('fallo de pintado')} />
        </LazyRouteBoundary>
      </MemoryRouter>,
    );

    const alert = screen.getByRole('alert');
    expect(screen.getByRole('heading', { level: 1, name: 'No se pudo cargar esta pantalla' })).toBeInTheDocument();
    expect(alert).toHaveTextContent('Ha ocurrido un error inesperado al mostrarla. Recarga la página para intentarlo de nuevo.');
    expect(alert).not.toHaveTextContent(/conexión/);
    expect(screen.getByRole('button', { name: 'Recargar la página' })).toBeInTheDocument();
  });

  it('el error no se esconde: React lo sigue escribiendo en la consola', () => {
    render(
      <MemoryRouter>
        <LazyRouteBoundary onReload={vi.fn()}>
          <Throws error={new Error('fallo visible en la consola')} />
        </LazyRouteBoundary>
      </MemoryRouter>,
    );

    const logged = vi.mocked(console.error).mock.calls.flat();
    expect(logged.some((arg) => arg instanceof Error && arg.message === 'fallo visible en la consola')).toBe(true);
  });

  it('en una pantalla pública (sin AppShell), el aviso va dentro de su propio <main>', () => {
    render(
      <MemoryRouter>
        <LazyRouteBoundary standalone onReload={vi.fn()}>
          <Throws error={new ChunkLoadError(new Error('404'))} />
        </LazyRouteBoundary>
      </MemoryRouter>,
    );

    const main = screen.getByRole('main');
    expect(main).toContainElement(screen.getByRole('alert'));
    // Destino de «Saltar al contenido» y del foco al cambiar de página, como el resto de pantallas.
    expect(main).toHaveAttribute('id', 'contenido');
    expect(main).toHaveAttribute('tabindex', '-1');
  });

  it('dentro del diseño (sin `standalone`) no añade otro <main>', () => {
    render(
      <MemoryRouter>
        <LazyRouteBoundary onReload={vi.fn()}>
          <Throws error={new ChunkLoadError(new Error('404'))} />
        </LazyRouteBoundary>
      </MemoryRouter>,
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('main')).not.toBeInTheDocument();
  });

  it('al cambiar de ruta vuelve a intentar pintar: el error de una pantalla no bloquea las demás', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/a']}>
        <Link to="/b">Ir a B</Link>
        <LazyRouteBoundary onReload={vi.fn()}>
          <Routes>
            <Route path="/a" element={<Throws error={new Error('la pantalla A falla')} />} />
            <Route path="/b" element={<p>Pantalla B pintada</p>} />
          </Routes>
        </LazyRouteBoundary>
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: 'No se pudo cargar esta pantalla' })).toBeInTheDocument();
    await user.click(screen.getByRole('link', { name: 'Ir a B' }));
    expect(screen.getByText('Pantalla B pintada')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('importChunk', () => {
  it('marca cualquier fallo del import() como ChunkLoadError, con el error original en `cause`', async () => {
    const original = new TypeError('Failed to fetch dynamically imported module');
    const error = await importChunk(() => Promise.reject(original)).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ChunkLoadError);
    expect((error as ChunkLoadError).cause).toBe(original);
  });

  it('si la descarga va bien, devuelve el módulo tal cual', async () => {
    const module = { Page: () => null };
    await expect(importChunk(() => Promise.resolve(module))).resolves.toBe(module);
  });
});
