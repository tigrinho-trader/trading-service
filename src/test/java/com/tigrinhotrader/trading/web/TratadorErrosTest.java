package com.tigrinhotrader.trading.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigrinhotrader.trading.servico.ConflitoException;
import com.tigrinhotrader.trading.servico.RitmoExcedidoException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class TratadorErrosTest {

    private final TratadorErros tratador = new TratadorErros();

    @Test
    void alteracaoSimultaneaViraConflito() {
        var problema = tratador.alteracaoSimultanea(new ObjectOptimisticLockingFailureException("SessaoFlecha", "id"));
        assertThat(problema.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problema.getDetail()).contains("tente de novo");
    }

    @Test
    void conflitoERitmo() {
        assertThat(tratador.conflito(new ConflitoException("ja aberta")).getStatus()).isEqualTo(409);
        assertThat(tratador.ritmoExcedido(new RitmoExcedidoException("calma")).getStatus()).isEqualTo(429);
    }
}
