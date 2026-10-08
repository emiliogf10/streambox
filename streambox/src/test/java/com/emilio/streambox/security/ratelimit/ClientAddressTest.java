package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests unitarios de {@link ClientAddress}: la clave con la que se cuentan las IPs. */
class ClientAddressTest {

    @ParameterizedTest
    @ValueSource(strings = { "192.0.2.1", "10.0.0.255", "127.0.0.1", "255.255.255.255" })
    void laIpv4SeDevuelveTalCual(String ip) {
        assertEquals(ip, ClientAddress.counterKey(ip));
    }

    @Test
    void lasIpv6DelMismo64TienenLaMismaClave() {
        String key = ClientAddress.counterKey("2001:db8:1:2:aaaa:bbbb:cccc:dddd");

        assertEquals(key, ClientAddress.counterKey("2001:db8:1:2::1"));
        assertEquals(key, ClientAddress.counterKey("2001:0DB8:0001:0002:0:0:0:0"));
        assertEquals(key, ClientAddress.counterKey("2001:db8:1:2:ffff:ffff:ffff:ffff"));
        assertEquals("2001:db8:1:2::/64", key);
    }

    @Test
    void losPrefijos64DistintosTienenClavesDistintas() {
        assertNotEquals(ClientAddress.counterKey("2001:db8:1:2::1"), ClientAddress.counterKey("2001:db8:1:3::1"));
        assertNotEquals(ClientAddress.counterKey("2001:db8:1:2::1"), ClientAddress.counterKey("2001:db9:1:2::1"));
    }

    @Test
    void unaIpv6ConCerosAlPrincipioYLaFormaCompletaCoinciden() {
        assertEquals(ClientAddress.counterKey("::1"), ClientAddress.counterKey("0:0:0:0:0:0:0:1"));
        assertEquals("0:0:0:0::/64", ClientAddress.counterKey("::1"));
    }

    @Test
    void unaIpv4MapeadaEnIpv6SeTrataComoIpv4() {
        assertEquals("192.0.2.9", ClientAddress.counterKey("::ffff:192.0.2.9"));
        assertEquals("192.0.2.9", ClientAddress.counterKey("::ffff:c000:209"));
    }

    @Test
    void laZonaDeUnaIpv6LocalNoCambiaElPrefijo() {
        assertEquals(ClientAddress.counterKey("fe80::1"), ClientAddress.counterKey("fe80::2%1"));
    }

    /** La zona con nombre de interfaz (que el JDK no puede resolver aquí) se descarta igual que la numérica. */
    @Test
    void laZonaConNombreDeInterfazSeDescartaYElLiteralSigueFuncionando() {
        assertEquals("fe80:0:0:0::/64", ClientAddress.counterKey("fe80::1%eth0"));
        assertEquals(ClientAddress.counterKey("fe80::1"), ClientAddress.counterKey("fe80::1%eth0"));
        assertEquals(ClientAddress.counterKey("fe80::1"), ClientAddress.counterKey("fe80::1%wlan-0.1_x"));
    }

    @Test
    void loQueNoEsUnaIpSeDevuelveSinCambios() {
        assertEquals("no-es-una-ip", ClientAddress.counterKey("no-es-una-ip"));
        assertEquals("zz::zz::zz", ClientAddress.counterKey("zz::zz::zz"));
        assertEquals("", ClientAddress.counterKey(""));
    }

    /**
     * Texto con {@code :} que NO es un literal IPv6. {@code InetAddress.getByName}
     * lo mandaría al DNS si empieza por otro carácter que un hexadecimal (p. ej.
     * {@code zz::zz}) o si empieza por un hexadecimal pero no se puede analizar
     * (p. ej. {@code 1::2::3}). La comprobación de caracteres previa y los
     * corchetes lo evitan; aquí se demuestra que el texto vuelve intacto.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "zz::zz", "host:name", "evil.example.com:1", "localhost:8080", "example.com:",
            "1::2::3", "dead:beef::cafe.example:1", "face::b.ad::", "1:2", ":", "::g",
            "fe80::1%", "fe80::1%eth 0", "fe80::1%eth0%1", "fe80::1%ñ", "%eth0", "[::1]", "::1 ",
            "2001:db8::1/64" })
    void loQueTieneDosPuntosPeroNoEsUnLiteralIpv6SeDevuelveTalCual(String text) {
        assertEquals(text, ClientAddress.counterKey(text));
    }

    @Test
    void nullDevuelveNull() {
        assertNull(ClientAddress.counterKey(null));
    }
}
