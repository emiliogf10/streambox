package com.emilio.streambox.security.refresh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Programa la limpieza periódica de la tabla {@code refresh_tokens}.
 *
 * <p>
 * Sin limpieza, la tabla crecería con cada login y cada refresh (unas 100
 * filas por usuario activo al mes con tokens de 15 minutos). Cada
 * {@code streambox.auth.refresh.cleanup.interval} (1 h) se borran los tokens
 * de familias caducadas y los caducados hace más de
 * {@code cleanup.retention}; ver {@link RefreshTokenService#deleteExpired()}.
 * </p>
 *
 * <p>
 * Se registra con {@link SchedulingConfigurer} y no con
 * {@code @Scheduled(fixedDelayString = "${...}")} para que el intervalo salga
 * de {@link RefreshTokenProperties} ya validado, sin repetir el valor por
 * defecto en la anotación. {@code @EnableScheduling} va aquí y no en la clase
 * principal para que {@code streambox.auth.refresh.cleanup.enabled=false}
 * (perfil {@code test}) no deje ningún planificador en marcha.
 * </p>
 *
 * <p>
 * Con varias réplicas cada una ejecuta su limpieza; es inofensivo (el
 * {@code DELETE} es idempotente), solo hace trabajo repetido.
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "streambox.auth.refresh.cleanup", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RefreshTokenCleanupConfig implements SchedulingConfigurer {

    private static final Logger LOGGER = LoggerFactory.getLogger(RefreshTokenCleanupConfig.class);

    private final RefreshTokenService refreshTokenService;

    private final RefreshTokenProperties.Cleanup cleanup;

    /**
     * Crea la configuración.
     *
     * @param refreshTokenService servicio que hace el borrado
     * @param properties          intervalo, espera inicial y retención
     */
    public RefreshTokenCleanupConfig(RefreshTokenService refreshTokenService, RefreshTokenProperties properties) {
        this.refreshTokenService = refreshTokenService;
        this.cleanup = properties.cleanup();
    }

    /**
     * Registra la tarea con retardo fijo (la siguiente empieza
     * {@code interval} después de que acabe la anterior: nunca se solapan).
     *
     * @param registrar registro de tareas de Spring
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new FixedDelayTask(this::cleanUp, cleanup.interval(), cleanup.initialDelay()));
    }

    /**
     * Una pasada de limpieza. Si falla (base de datos caída), Spring registra
     * el error y la siguiente pasada se intenta igualmente.
     */
    void cleanUp() {
        int deleted = refreshTokenService.deleteExpired();
        if (deleted > 0) {
            LOGGER.info("Limpieza de refresh tokens: {} borrados", deleted);
        }
    }
}
