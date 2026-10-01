package com.tigrinhotrader.trading.mensageria;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrdemExecutadaPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange exchange;

    public OrdemExecutadaPublisher(RabbitTemplate rabbitTemplate, TopicExchange exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    /**
     * Publica dentro da transacao de resolucao: se a fila falhar, a transacao volta,
     * a ordem continua ABERTA e e tentada de novo no proximo ciclo (entrega pelo menos uma vez;
     * o wallet-service e idempotente por ordemId).
     */
    public void publicar(OrdemExecutadaEvento evento) {
        rabbitTemplate.convertAndSend(exchange.getName(), MensageriaConfig.ROTA_ORDEM_EXECUTADA, evento);
    }
}
