import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installManualTimers, passTime } from '../../test/fakeTimers';
import { WIDE_IMAGE_FAILED_MESSAGE, WideImagePreview } from './WideImagePreview';

/** Retraso de la vista previa tras la última tecla (`PREVIEW_DEBOUNCE_MS` del componente). */
const DEBOUNCE_MS = 400;
const VALID = 'Así se verá a 16:9.';

/** Pinta la vista previa con una URL; `rerender` permite simular que el administrador escribe otra. */
function renderPreview(imageUrl: string) {
  const props = { label: 'la imagen de fondo', title: 'Sintel', emptyMessage: 'Vacía.', validMessage: VALID };
  const view = render(<WideImagePreview {...props} imageUrl={imageUrl} />);
  passTime(DEBOUNCE_MS);
  return { rerender: (url: string) => view.rerender(<WideImagePreview {...props} imageUrl={url} />) };
}

const previewImage = () => screen.getByRole('img', { name: 'Vista previa de la imagen de fondo' });

describe('WideImagePreview: imagen válida que no carga', () => {
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());

  it('no promete «Así se verá» si la imagen falla: avisa de que no se verá', () => {
    renderPreview('/covers/no-existe-fondo.webp');
    expect(screen.getByText(VALID)).toBeInTheDocument();

    fireEvent.error(previewImage());

    expect(screen.getByText(WIDE_IMAGE_FAILED_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByText(VALID)).not.toBeInTheDocument();
  });

  it('al escribir otra URL se vuelve a intentar y el texto vuelve a ser el de una imagen válida', () => {
    const { rerender } = renderPreview('/covers/no-existe-fondo.webp');
    fireEvent.error(previewImage());

    rerender('/covers/sintel-fondo.webp');
    passTime(DEBOUNCE_MS);

    expect(previewImage()).toHaveAttribute('src', '/covers/sintel-fondo.webp');
    expect(screen.getByText(VALID)).toBeInTheDocument();
    expect(screen.queryByText(WIDE_IMAGE_FAILED_MESSAGE)).not.toBeInTheDocument();
  });
});
