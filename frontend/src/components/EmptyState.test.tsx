/**
 * Tests de `EmptyState`: el icono en círculo por defecto y la ilustración propia
 * (`visual`) que lo sustituye, siempre decorativa para los lectores de pantalla.
 */
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
  it('muestra título (h2), descripción y la acción', () => {
    render(
      <EmptyState
        icon={<img alt="icono" src="data:," />}
        title="Tu lista está vacía"
        description="Pulsa «Mi lista»."
        action={<a href="/">Explorar catálogo</a>}
      />,
    );

    expect(screen.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeInTheDocument();
    expect(screen.getByText('Pulsa «Mi lista».')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Explorar catálogo' })).toBeInTheDocument();
    // El icono es decorativo: está en el DOM, pero no en el árbol de accesibilidad.
    expect(screen.getByAltText('icono')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'icono' })).not.toBeInTheDocument();
  });

  it('con `visual`, la ilustración sustituye al icono y queda oculta a los lectores de pantalla', () => {
    render(
      <EmptyState
        icon={<img alt="icono" src="data:," />}
        visual={<img alt="ilustración" src="data:," />}
        title="Vacío"
        description="Nada por aquí."
      />,
    );

    expect(screen.queryByAltText('icono')).not.toBeInTheDocument();
    expect(screen.getByAltText('ilustración')).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'ilustración' })).not.toBeInTheDocument();
  });
});
