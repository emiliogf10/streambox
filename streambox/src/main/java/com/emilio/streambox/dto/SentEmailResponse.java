package com.emilio.streambox.dto;

import java.time.Instant;

/**
 * Un correo de la bandeja en memoria, tal como lo devuelve
 * {@code GET /api/admin/e2e/emails} (solo con el perfil {@code e2e}).
 *
 * @param to      destinatario
 * @param subject asunto
 * @param body    cuerpo en texto plano (con el enlace y su token)
 * @param sentAt  instante del envío
 */
public record SentEmailResponse(String to, String subject, String body, Instant sentAt) {
}
