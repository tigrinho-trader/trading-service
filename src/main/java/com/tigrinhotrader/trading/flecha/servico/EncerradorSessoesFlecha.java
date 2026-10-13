package com.tigrinhotrader.trading.flecha.servico;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A cada minuto encerra as sessoes esquecidas abertas e reembolsa as flechas antigas guardadas (ferro, ouro,
 * diamante). Desligue com {@code flecha.agendador.habilitado=false}.
 */
@Component
@ConditionalOnProperty(prefix = "flecha.agendador", name = "habilitado", havingValue = "true", matchIfMissing = true)
public class EncerradorSessoesFlecha {

    private final SessaoFlechaService service;

    public EncerradorSessoesFlecha(SessaoFlechaService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${flecha.agendador.intervalo-ms:60000}")
    public void executar() {
        service.encerrarOciosas();
        service.reembolsarFlechasAntigas();
    }
}
