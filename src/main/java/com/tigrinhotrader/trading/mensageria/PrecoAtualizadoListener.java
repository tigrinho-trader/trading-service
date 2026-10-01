package com.tigrinhotrader.trading.mensageria;

import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.OrdemService;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PrecoAtualizadoListener {

    private final PrecoCache precoCache;
    private final OrdemService ordemService;

    public PrecoAtualizadoListener(PrecoCache precoCache, OrdemService ordemService) {
        this.precoCache = precoCache;
        this.ordemService = ordemService;
    }

    /** Guarda o preco e derruba na hora as rodadas "sem toque" cujo alvo ele encostou. */
    @RabbitListener(queues = MensageriaConfig.FILA_PRECOS)
    public void aoReceber(PrecoAtualizadoEvento evento) {
        precoCache.atualizar(evento);
        if (evento != null && evento.simbolo() != null && evento.preco() != null) {
            ordemService.verificarBarreiras(evento.simbolo(), evento.preco());
        }
    }
}
