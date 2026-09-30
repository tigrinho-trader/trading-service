package com.tigrinhotrader.trading.mensageria;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MensageriaConfig {

    public static final String ROTA_PRECO_ATUALIZADO = "preco.atualizado";
    public static final String ROTA_ORDEM_EXECUTADA = "ordem.executada";
    public static final String FILA_PRECOS = "trading.preco-atualizado";

    @Bean
    TopicExchange eventosExchange(@Value("${mensageria.exchange:tigrinho.eventos}") String nome) {
        return new TopicExchange(nome, true, false);
    }

    /** Fila de precos nao precisa sobreviver a restart: so interessa o preco mais recente. */
    @Bean
    Queue filaPrecos() {
        return QueueBuilder.nonDurable(FILA_PRECOS).autoDelete().withArgument("x-max-length", 1000).build();
    }

    @Bean
    Binding bindingPrecos(Queue filaPrecos, TopicExchange eventosExchange) {
        return BindingBuilder.bind(filaPrecos).to(eventosExchange).with(ROTA_PRECO_ATUALIZADO);
    }

    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter conversor = new Jackson2JsonMessageConverter(objectMapper);
        // cada servico tem suas proprias classes de evento: usa o tipo do parametro do listener
        conversor.setAlwaysConvertToInferredType(true);
        return conversor;
    }
}
