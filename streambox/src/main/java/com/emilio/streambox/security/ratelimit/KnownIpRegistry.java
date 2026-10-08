package com.emilio.streambox.security.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recuerda, por cuenta, desde qué IPs se ha iniciado sesión <b>con éxito</b>
 * (las «IPs conocidas»). Lo usa {@link LoginAttemptService} para que el
 * bloqueo de una cuenta no deje fuera a su titular.
 *
 * <ul>
 * <li>Una IP solo entra tras una autenticación correcta (contraseña
 * verificada): los intentos fallidos nunca «conocen» una IP, así que un
 * atacante no puede añadir la suya y una cuenta que no existe nunca tiene
 * IPs.</li>
 * <li>Por cuenta se guardan como mucho {@code maxIpsPerAccount} IPs; al
 * superarlo se olvida la que lleva más tiempo sin un login correcto.</li>
 * <li>Cada IP caduca {@code ttl} después de su último login correcto (un login
 * correcto desde una IP conocida renueva su plazo).</li>
 * <li>Memoria acotada: solo hay entradas para cuentas con algún login
 * correcto, y como mucho {@code maxAccounts}; al llenarse se purgan las
 * caducadas y, si sigue lleno, se olvida la cuenta con el login correcto más
 * antiguo. No crece con intentos fallidos.</li>
 * </ul>
 *
 * <p>
 * Las claves de IP son las de {@link ClientAddress#counterKey(String)}. El estado
 * vive en memoria: se pierde al reiniciar y no se comparte entre réplicas. Es
 * seguro para uso concurrente.
 * </p>
 */
final class KnownIpRegistry {

    private final int maxAccounts;
    private final int maxIpsPerAccount;
    private final long ttlMillis;
    private final Clock clock;

    /**
     * Cuenta -> (IP -> instante de su último login correcto). Ambos mapas
     * se ordenan por ese instante (lo más antiguo primero): al recordar, la
     * entrada se saca y se vuelve a meter para llevarla al final.
     */
    private final Map<String, LinkedHashMap<String, Long>> byAccount = new LinkedHashMap<>();

    /**
     * @param maxAccounts      máximo de cuentas recordadas a la vez
     * @param maxIpsPerAccount máximo de IPs recordadas por cuenta
     * @param ttl              cuánto se recuerda una IP desde su último login correcto
     * @param clock            reloj de la aplicación
     * @throws IllegalArgumentException si algún límite o el plazo no son positivos
     */
    KnownIpRegistry(int maxAccounts, int maxIpsPerAccount, Duration ttl, Clock clock) {
        if (maxAccounts <= 0 || maxIpsPerAccount <= 0 || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Los límites y el plazo de las IPs conocidas deben ser positivos");
        }
        this.maxAccounts = maxAccounts;
        this.maxIpsPerAccount = maxIpsPerAccount;
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    /**
     * @param account cuenta (email normalizado)
     * @param ip      clave de la IP del cliente; puede ser {@code null}
     * @return {@code true} si desde esa IP hubo un login correcto de la cuenta
     *         hace menos de {@code ttl}
     */
    synchronized boolean isKnown(String account, String ip) {
        if (ip == null) {
            return false;
        }
        purgeExpiredAccounts();
        LinkedHashMap<String, Long> ips = byAccount.get(account);
        if (ips == null) {
            return false;
        }
        Long lastLogin = ips.get(ip);
        if (lastLogin == null) {
            return false;
        }
        if (isExpired(lastLogin, clock.millis())) {
            ips.remove(ip);
            if (ips.isEmpty()) {
                byAccount.remove(account);
            }
            return false;
        }
        return true;
    }

    /**
     * Anota un login correcto de la cuenta desde la IP (o renueva su plazo).
     *
     * @param account cuenta (email normalizado)
     * @param ip      clave de la IP del cliente; si es {@code null} no se hace nada
     */
    synchronized void remember(String account, String ip) {
        if (ip == null) {
            return;
        }
        long now = clock.millis();
        purgeExpiredAccounts();

        // Sacar la cuenta y volver a meterla la lleva al final del orden.
        LinkedHashMap<String, Long> ips = byAccount.remove(account);
        if (ips == null) {
            makeRoom();
            ips = new LinkedHashMap<>();
        } else {
            ips.values().removeIf(lastLogin -> isExpired(lastLogin, now));
        }
        ips.remove(ip);
        ips.put(ip, now);
        while (ips.size() > maxIpsPerAccount) {
            Iterator<Long> oldest = ips.values().iterator();
            oldest.next();
            oldest.remove();
        }
        byAccount.put(account, ips);
    }

    /**
     * @return número de cuentas con alguna IP recordada (para pruebas)
     */
    synchronized int accounts() {
        return byAccount.size();
    }

    private boolean isExpired(long lastLogin, long now) {
        return now - lastLogin >= ttlMillis;
    }

    /**
     * Elimina las cuentas del principio del orden cuyo último login correcto
     * ya caducó (todas sus IPs lo están), hasta la primera viva.
     */
    private void purgeExpiredAccounts() {
        long now = clock.millis();
        for (Iterator<LinkedHashMap<String, Long>> it = byAccount.values().iterator(); it.hasNext();) {
            LinkedHashMap<String, Long> ips = it.next();
            if (ips.isEmpty() || isExpired(ips.lastEntry().getValue(), now)) {
                it.remove();
            } else {
                return;
            }
        }
    }

    private void makeRoom() {
        while (byAccount.size() >= maxAccounts) {
            Iterator<LinkedHashMap<String, Long>> it = byAccount.values().iterator();
            it.next();
            it.remove();
        }
    }
}
