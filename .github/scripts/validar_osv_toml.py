"""Valida osv-scanner.toml antes del escaneo (job "osv" de seguridad.yml).

Por qué existe: OSV-Scanner acepta excepciones que el proyecto no quiere
permitir, y un comentario en el .toml no lo impide:

  - [[IgnoredVulns]] sin "reason" ni "ignoreUntil": ignora el aviso PARA
    SIEMPRE y sin explicar por qué.
  - "ignoreUntil" entre comillas: es una cadena, no una fecha, y no
    caduca como se esperaba.
  - [[PackageOverrides]] (con "ignore = true" o "vulnerability.ignore"):
    ignora un paquete ENTERO, incluidas las vulnerabilidades que se
    publiquen mañana.

Este script hace obligatorio lo que el .toml documenta. Cada
[[IgnoredVulns]] debe tener exactamente:

  id           el identificador del aviso (cadena no vacía, sin espacios).
  reason       el motivo, con al menos 20 letras o cifras (los espacios,
               la puntuación y los caracteres invisibles, como el espacio
               de ancho cero U+200B, no cuentan: no se puede rellenar).
  ignoreUntil  una FECHA de TOML (AAAA-MM-DD, sin comillas) a 90 días
               como mucho desde hoy (UTC).

Funciona por LISTA BLANCA: cualquier clave que no sea IgnoredVulns en la
raíz, o que no sea id/reason/ignoreUntil dentro de una excepción, es un
error. Así se rechazan PackageOverrides y cualquier opción que OSV añada en
el futuro para ignorar en bloque, y también las variantes con otras
mayúsculas ("packageOverrides", "ignoreuntil"...), que OSV acepta porque su
lector de TOML no distingue mayúsculas en los nombres de los campos.

Uso: python3 validar_osv_toml.py <ruta a osv-scanner.toml>
Sale con 0 si el archivo es válido y con 1 si no (cada problema sale como
anotación "::error::" de GitHub Actions). Solo usa la biblioteca estándar
(tomllib, Python 3.11 o mayor), que ya trae ubuntu-latest.

Los textos sacados del .toml (claves, ids) se muestran con ascii(): entre
comillas y con los caracteres raros a la vista (\\n, \\u200b, \\u212a...),
y cada anotación se escapa como pide GitHub (ver escapar_mensaje). Si no, un
id con un salto de línea partiría la anotación y su segunda línea, si
empieza por "::", sería un comando del flujo de trabajo inyectado desde el
archivo.
"""

import datetime
import sys
import tomllib

# Máximo de días que puede durar una excepción: obliga a revisarla cada
# trimestre como mucho, en lugar de olvidarla.
MAX_DIAS = 90
# Un motivo con menos letras o cifras que esto no explica nada ("no
# aplica", "falso positivo"). Se cuentan solo letras y cifras (Unicode, así
# que las tildes valen) para que no se pueda llegar al mínimo con espacios,
# puntos o caracteres invisibles.
MIN_ALFANUMERICOS_MOTIVO = 20
CLAVES_RAIZ = {"IgnoredVulns"}
CLAVES_EXCEPCION = {"id", "reason", "ignoreUntil"}


def mostrar(valor):
    """Representa un valor del .toml para un mensaje, sin que pueda engañar.

    ascii() lo pone entre comillas y escribe como secuencias de escape los
    saltos de línea, los caracteres invisibles y los que se parecen a una
    letra normal (el signo Kelvin U+212A parece una K), así que se ve
    exactamente qué hay en el archivo.
    """
    return ascii(valor)


def escapar_mensaje(texto):
    """Escapa el mensaje de una anotación ::error::/::warning:: de GitHub.

    Es la codificación que espera el runner: sin ella, un salto de línea
    terminaría la anotación y lo que viniera detrás se leería como otra
    línea del log, o como otro comando si empieza por "::". El % va primero
    para no escapar dos veces los que se añaden.
    """
    return texto.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escapar_propiedad(texto):
    """Escapa el valor de una propiedad de la anotación (file=...).

    Además de lo del mensaje, ":" y "," separan las propiedades.
    """
    return escapar_mensaje(texto).replace(":", "%3A").replace(",", "%2C")


def validar(config, hoy):
    """Devuelve la lista de errores (vacía si todo es correcto) y de avisos."""
    errores = []
    avisos = []

    for clave in sorted(set(config) - CLAVES_RAIZ):
        errores.append(
            f"Clave no permitida en la raíz: {mostrar(clave)}. Solo se admiten "
            "excepciones concretas con [[IgnoredVulns]] (nunca un paquete "
            "entero con PackageOverrides)."
        )

    excepciones = config.get("IgnoredVulns", [])
    if not isinstance(excepciones, list):
        return errores + ['"IgnoredVulns" debe escribirse como [[IgnoredVulns]].'], avisos

    limite = hoy + datetime.timedelta(days=MAX_DIAS)
    for numero, excepcion in enumerate(excepciones, start=1):
        if not isinstance(excepcion, dict):
            errores.append(f"Excepción {numero}: debe escribirse como [[IgnoredVulns]].")
            continue
        id_mostrado = mostrar(excepcion["id"]) if "id" in excepcion else "sin id"
        nombre = f"Excepción {numero} ({id_mostrado})"

        for clave in sorted(set(excepcion) - CLAVES_EXCEPCION):
            errores.append(
                f"{nombre}: clave no permitida {mostrar(clave)}. Solo se admiten "
                "id, reason e ignoreUntil."
            )

        identificador = excepcion.get("id")
        if not isinstance(identificador, str) or not identificador.strip() or any(
            c.isspace() for c in identificador
        ):
            errores.append(f'{nombre}: falta "id" o no es un identificador válido (GHSA-..., CVE-...).')

        motivo = excepcion.get("reason")
        if not isinstance(motivo, str) or (
            sum(c.isalnum() for c in motivo) < MIN_ALFANUMERICOS_MOTIVO
        ):
            errores.append(
                f'{nombre}: falta "reason" o es demasiado corto (al menos '
                f"{MIN_ALFANUMERICOS_MOTIVO} letras o cifras): explica qué parte "
                "del aviso no nos afecta y por qué, o qué versión se espera."
            )

        caducidad = excepcion.get("ignoreUntil")
        # datetime.datetime hereda de datetime.date: se excluye a propósito
        # para que el formato sea siempre el documentado (AAAA-MM-DD).
        if caducidad is None:
            errores.append(
                f'{nombre}: falta "ignoreUntil". Sin caducidad, OSV ignora el '
                "aviso para siempre."
            )
        elif not isinstance(caducidad, datetime.date) or isinstance(caducidad, datetime.datetime):
            errores.append(
                f'{nombre}: "ignoreUntil" debe ser una fecha AAAA-MM-DD sin '
                "comillas y sin hora (entre comillas es una cadena y la "
                "excepción no caduca como se espera)."
            )
        elif caducidad > limite:
            errores.append(
                f"{nombre}: caduca el {caducidad.isoformat()}, a más de "
                f"{MAX_DIAS} días (máximo: {limite.isoformat()}). Pon una "
                "fecha más cercana y renuévala si sigue haciendo falta."
            )
        elif caducidad < hoy:
            # No es un error: OSV ya no la aplica, y si el aviso sigue
            # presente el escaneo fallará solo. Pero sobra en el archivo.
            avisos.append(
                f"{nombre}: caducó el {caducidad.isoformat()} y OSV ya no la "
                "aplica. Bórrala o, si sigue haciendo falta, renuévala."
            )

    return errores, avisos


def main(argumentos):
    if len(argumentos) != 2:
        print("Uso: python3 validar_osv_toml.py <ruta a osv-scanner.toml>", file=sys.stderr)
        return 2
    ruta = argumentos[1]
    try:
        with open(ruta, "rb") as archivo:
            config = tomllib.load(archivo)
    except (OSError, tomllib.TOMLDecodeError) as error:
        mensaje = f"No se puede leer {ruta}: {error}"
        print(f"::error file={escapar_propiedad(ruta)}::{escapar_mensaje(mensaje)}")
        return 1

    errores, avisos = validar(config, datetime.datetime.now(datetime.timezone.utc).date())
    for aviso in avisos:
        print(f"::warning file={escapar_propiedad(ruta)}::{escapar_mensaje(aviso)}")
    for error in errores:
        print(f"::error file={escapar_propiedad(ruta)}::{escapar_mensaje(error)}")
    if errores:
        return 1
    print(f"{ruta}: correcto. Excepciones: {len(config.get('IgnoredVulns', []))}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
