package com.emilio.streambox.security.ratelimit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Convierte la dirección remota de una petición en la <b>clave</b> con la que
 * se cuentan los intentos por IP.
 *
 * <ul>
 * <li><b>IPv4:</b> una clave por dirección (sin cambios).</li>
 * <li><b>IPv6:</b> se agrupa por prefijo <b>/64</b>. Un abonado o un servidor
 * recibe como mínimo un /64 entero (2<sup>64</sup> direcciones), así que
 * contar por dirección completa permitiría a un atacante saltarse el límite
 * rotando de dirección sin coste alguno, y llenar la memoria de los contadores
 * con una clave nueva por petición. Agrupando por /64, rotar dentro del mismo
 * prefijo no multiplica ni los intentos ni las claves.</li>
 * <li>Una IPv6 que en realidad es una IPv4 (<code>::ffff:a.b.c.d</code>) se
 * trata como esa IPv4.</li>
 * </ul>
 *
 * <p>
 * La dirección sale de {@code request.getRemoteAddr()} (nunca de
 * {@code X-Forwarded-For} a mano, que es falsificable), que es siempre un
 * literal numérico.
 * </p>
 *
 * <p>
 * <b>Sin consultas DNS.</b> {@link InetAddress#getByName(String)} no distingue
 * por sí solo un literal de un nombre: solo trata el texto como IPv6 si empieza
 * por un dígito hexadecimal o por {@code :}/{@code [}, y si no lo consigue
 * analizar lo manda al DNS. Por eso, antes de llamarlo, el texto con {@code :}
 * debe pasar una comprobación conservadora de caracteres (solo hexadecimales,
 * {@code :} y {@code .}, más un sufijo de zona opcional
 * {@code %[A-Za-z0-9._-]+}, que se descarta porque no forma parte del
 * prefijo), y se entrega entre corchetes: con corchetes el JDK <i>espera</i>
 * un literal IPv6 y, si no lo es, lanza {@link UnknownHostException} en lugar
 * de resolverlo como nombre. Lo que no pasa la comprobación se devuelve tal
 * cual, sin llamar al JDK. Un texto sin {@code :} (IPv4 u otra cosa) tampoco
 * se analiza nunca.
 * </p>
 *
 * <p>
 * Contrapartida: dos usuarios distintos dentro de un mismo /64 (poco habitual
 * fuera de redes móviles o de hosting mal repartido) comparten límite.
 * </p>
 */
public final class ClientAddress {

    private ClientAddress() {
    }

    /**
     * @param remoteAddr dirección remota tal como la entrega el contenedor;
     *                   puede ser {@code null}
     * @return clave del contador: la IPv4 tal cual, el prefijo /64 para una
     *         IPv6 (por ejemplo {@code 2001:db8:0:1::/64}), o el mismo texto si
     *         no se reconoce como una IP; {@code null} si {@code remoteAddr}
     *         es {@code null}
     */
    public static String counterKey(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) {
            return remoteAddr;
        }
        String literal = ipv6LiteralWithoutZone(remoteAddr);
        if (literal == null) {
            return remoteAddr;
        }
        try {
            // Entre corchetes: el JDK exige un literal IPv6 y nunca consulta el DNS.
            InetAddress address = InetAddress.getByName('[' + literal + ']');
            if (address instanceof Inet6Address ipv6) {
                return prefix64(ipv6.getAddress());
            }
            // IPv4 escrita como ::ffff:a.b.c.d: el JDK ya la devuelve como IPv4.
            return address.getHostAddress();
        } catch (UnknownHostException notAnIp) {
            return remoteAddr;
        }
    }

    /**
     * Comprueba que el texto solo contiene caracteres de un literal IPv6
     * (hexadecimales, {@code :} y {@code .}, con un sufijo de zona
     * {@code %[A-Za-z0-9._-]+} opcional) y devuelve el literal sin la zona;
     * {@code null} si no los cumple. No valida la sintaxis completa: eso lo hace
     * el JDK, que con el texto entre corchetes nunca recurre al DNS.
     */
    private static String ipv6LiteralWithoutZone(String text) {
        int zone = text.indexOf('%');
        String literal = zone < 0 ? text : text.substring(0, zone);
        if (literal.isEmpty() || (zone >= 0 && !isValidZone(text, zone + 1))) {
            return null;
        }
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
            if (!allowed) {
                return null;
            }
        }
        return literal;
    }

    private static boolean isValidZone(String text, int start) {
        if (start >= text.length()) {
            return false;
        }
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '.' || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static String prefix64(byte[] address) {
        StringBuilder key = new StringBuilder(24);
        for (int group = 0; group < 4; group++) {
            if (group > 0) {
                key.append(':');
            }
            int value = ((address[2 * group] & 0xff) << 8) | (address[2 * group + 1] & 0xff);
            key.append(Integer.toHexString(value));
        }
        return key.append("::/64").toString();
    }
}
