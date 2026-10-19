package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.SentEmailResponse;
import com.emilio.streambox.email.MemoryEmailSender;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Bandeja de correo para los tests de punta a punta:
 * {@code GET /api/admin/e2e/emails?to=<email>}. <b>Solo existe con el perfil
 * {@code e2e}</b>.
 *
 * <p>
 * El E2E (Playwright) se registra por la interfaz y necesita el enlace del
 * correo para confirmar la cuenta, como haría una persona. Con el perfil
 * {@code e2e} los correos van a {@link MemoryEmailSender} en lugar de a un SMTP,
 * y este endpoint los devuelve, del más reciente al más antiguo.
 * </p>
 *
 * <p>
 * <b>Por qué es seguro.</b> (1) {@code @Profile("e2e")}: en {@code dev} y
 * {@code prod} la clase ni se registra. Aun así, la regla de
 * {@code /api/admin/**} de {@code SecurityConfig} se aplica antes de buscar
 * el controlador: un anónimo recibe 401 y un USER 403, y solo un ADMIN ve el
 * 404 de la ruta inexistente. El arranque falla si {@code e2e} va junto con
 * {@code prod}, o sin {@code prod} pero con un servidor SMTP o una base de
 * datos que no es H2 en memoria ({@code EmailSenderConfig#checkE2eEnvironment}),
 * señales de un despliegue real; y con {@code e2e} siempre queda un WARN en
 * el log. (2) Cuelga
 * de {@code /api/admin/**}, que {@code SecurityConfig} ya reserva a ADMIN: ni
 * siquiera en el E2E lo lee un usuario normal. (3) {@code @Hidden}: no aparece
 * en la documentación OpenAPI, que se genera con este mismo perfil.
 * </p>
 */
@RestController
@Profile("e2e")
@Hidden
@RequestMapping("/api/admin/e2e/emails")
public class E2eEmailInboxController {

    private final MemoryEmailSender inbox;

    /**
     * Crea el controlador.
     *
     * @param inbox bandeja en memoria del perfil {@code e2e}
     */
    public E2eEmailInboxController(MemoryEmailSender inbox) {
        this.inbox = inbox;
    }

    /**
     * Correos enviados a una dirección, del más reciente al más antiguo.
     *
     * @param to dirección del destinatario (sin distinguir mayúsculas)
     * @return los correos (lista vacía si no hay ninguno)
     */
    @GetMapping
    public List<SentEmailResponse> emailsTo(@RequestParam String to) {

        return inbox.sentTo(to).stream()
                .map(sent -> new SentEmailResponse(sent.to(), sent.subject(), sent.body(), sent.sentAt()))
                .toList();
    }
}
