package com.tigrinhotrader.trading.preco;

import static org.assertj.core.api.Assertions.assertThat;

import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PrecoCacheTest {

    private final PrecoCache cache = new PrecoCache();
    private final Instant t0 = Instant.parse("2026-09-30T12:00:00Z");

    private static PrecoAtualizadoEvento evento(String simbolo, String preco, Instant quando) {
        return new PrecoAtualizadoEvento(simbolo, preco == null ? null : new BigDecimal(preco), BigDecimal.ZERO, quando);
    }

    @Test
    void guardaUltimoPrecoIgnorandoCaixaDoSimbolo() {
        cache.atualizar(evento("btcusdt", "100", t0));
        cache.atualizar(evento("BTCUSDT", "101", t0.plusSeconds(1)));
        assertThat(cache.preco("BtcUsdt")).contains(new BigDecimal("101"));
        assertThat(cache.preco("ETHUSDT")).isEmpty();
    }

    @Test
    void eventoAtrasadoNaoSobrescreveMaisNovo() {
        cache.atualizar(evento("BTCUSDT", "101", t0.plusSeconds(5)));
        cache.atualizar(evento("BTCUSDT", "99", t0));
        assertThat(cache.preco("BTCUSDT")).contains(new BigDecimal("101"));
    }

    @Test
    void eventoSemHorarioSempreAtualiza() {
        cache.atualizar(evento("BTCUSDT", "101", t0));
        cache.atualizar(evento("BTCUSDT", "102", null));
        assertThat(cache.preco("BTCUSDT")).contains(new BigDecimal("102"));
    }

    @Test
    void ignoraEventosIncompletos() {
        cache.atualizar(null);
        cache.atualizar(evento(null, "1", t0));
        cache.atualizar(evento("BTCUSDT", null, t0));
        assertThat(cache.preco("BTCUSDT")).isEmpty();
    }
}
