package com.tigrinhotrader.trading.mensageria;

import com.tigrinhotrader.trading.preco.PrecoCache;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PrecoAtualizadoListener {

    private final PrecoCache precoCache;

    public PrecoAtualizadoListener(PrecoCache precoCache) {
        this.precoCache = precoCache;
    }

    @RabbitListener(queues = MensageriaConfig.FILA_PRECOS)
    public void aoReceber(PrecoAtualizadoEvento evento) {
        precoCache.atualizar(evento);
    }
}
