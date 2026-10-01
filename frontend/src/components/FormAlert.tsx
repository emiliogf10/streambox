/**
 * Aviso de error a nivel de formulario (credenciales incorrectas, 429, red
 * caída...). Usa `role="alert"` para que se anuncie al aparecer; no depende
 * solo del color porque incluye un texto explícito.
 */
export function FormAlert({ message }: { message: string }) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="mb-5 rounded-lg border border-red-500/40 bg-red-900/40 px-4 py-3 text-sm text-red-300"
    >
      {message}
    </div>
  );
}
