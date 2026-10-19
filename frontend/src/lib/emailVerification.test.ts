/**
 * Tests de `lib/emailVerification.ts`: la lectura del token del fragmento del
 * enlace (qué se acepta y qué se trata como «enlace no válido» sin preguntar al
 * servidor) y los textos de los avisos del reenvío.
 */
import { describe, expect, it } from 'vitest';
import { RESEND_DONE_MESSAGE, RESEND_RATE_LIMITED_MESSAGE, readVerificationToken } from './emailVerification';

describe('readVerificationToken', () => {
  it('lee el token de `#token=…` (con o sin la almohadilla)', () => {
    expect(readVerificationToken('#token=abc_DEF-123')).toBe('abc_DEF-123');
    expect(readVerificationToken('token=abc_DEF-123')).toBe('abc_DEF-123');
  });

  it('admite otros parámetros en el fragmento', () => {
    expect(readVerificationToken('#utm=x&token=abc123')).toBe('abc123');
  });

  it.each([
    ['vacío', ''],
    ['solo la almohadilla', '#'],
    ['sin token', '#otra=cosa'],
    ['token vacío', '#token='],
    ['con espacios', '#token=abc%20def'],
    ['con caracteres fuera de Base64URL', '#token=abc%3Cscript%3E'],
    ['con relleno de Base64 (=)', '#token=abc%3D'],
    ['demasiado largo', `#token=${'a'.repeat(513)}`],
  ])('devuelve null si es %s', (_case, hash) => {
    expect(readVerificationToken(hash)).toBeNull();
  });

  it('acepta un token de 512 caracteres (el margen del formato)', () => {
    expect(readVerificationToken(`#token=${'a'.repeat(512)}`)).toBe('a'.repeat(512));
  });
});

describe('textos del reenvío', () => {
  it('el aviso de «enviado» no afirma que exista la cuenta', () => {
    const text = RESEND_DONE_MESSAGE('ana@example.com');
    expect(text).toMatch(/^Si hay una cuenta pendiente de confirmar con ana@example\.com/);
    expect(text).not.toMatch(/te hemos enviado/i);
  });

  it('el aviso de «enviado» es verdad aunque el límite silencioso del servidor frene el correo', () => {
    // Con registro + 2 reenvíos en la misma hora (o tras recargar, que reinicia
    // la espera del cliente) el servidor responde 202 y NO envía nada: el enlace
    // anterior sigue valiendo. El texto no puede prometer el correo ni dar por
    // muertos los enlaces anteriores.
    const text = RESEND_DONE_MESSAGE('ana@example.com');
    expect(text).toBe(
      'Si hay una cuenta pendiente de confirmar con ana@example.com, te llegará un enlace nuevo en unos minutos, ' +
        'salvo que ya hayas pedido varios hace poco. Usa siempre el del correo más reciente.',
    );
    expect(text).not.toMatch(/ya no sirven|dejan de (servir|funcionar)/i);
  });

  it('el aviso del 429 dice la espera si se conoce y no la inventa si no', () => {
    expect(RESEND_RATE_LIMITED_MESSAGE('2 min')).toBe('Has pedido demasiados correos seguidos. Podrás pedir otro en 2 min.');
    expect(RESEND_RATE_LIMITED_MESSAGE()).toBe('Has pedido demasiados correos seguidos. Inténtalo de nuevo más tarde.');
  });
});
