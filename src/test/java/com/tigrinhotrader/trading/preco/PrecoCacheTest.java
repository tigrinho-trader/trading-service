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
    void volatilidadeSoApareceComAmostrasSuficientes() {
        assertThat(cache.volatilidadePorSegundo("BTCUSDT")).isEmpty();
        for (int i = 0; i < PrecoCache.AMOSTRAS_MINIMAS - 1; i++) {
            cache.atualizar(evento("BTCUSDT", "100", t0.plusSeconds(i)));
        }
        assertThat(cache.volatilidadePorSegundo("BTCUSDT")).isEmpty();
    }

    @Test
    void volatilidadeMedeORetornoPorRaizDeSegundo() {
        // vai e volta 0,01% a cada 1s: desvio de ~0,0001 por raiz de segundo
        for (int i = 0; i < 40; i++) {
            cache.atualizar(evento("ethusdt", i % 2 == 0 ? "100" : "100.01", t0.plusSeconds(i)));
        }
        assertThat(cache.volatilidadePorSegundo("ETHUSDT").orElseThrow()).isBetween(0.0000999, 0.0001001);

        // mesmo movimento a cada 4s: a volatilidade por segundo cai pela metade
        for (int i = 0; i < 40; i++) {
            cache.atualizar(evento("SOLUSDT", i % 2 == 0 ? "100" : "100.01", t0.plusSeconds(4L * i)));
        }
        assertThat(cache.volatilidadePorSegundo("SOLUSDT").orElseThrow()).isBetween(0.0000499, 0.0000501);
    }

    @Test
    void historicoIgnoraEventoSemHorarioOuForaDeOrdemELimitaOTamanho() {
        for (int i = 0; i < PrecoCache.AMOSTRAS + 50; i++) {
            cache.atualizar(evento("BTCUSDT", "100", t0.plusSeconds(i)));
        }
        cache.atualizar(evento("BTCUSDT", "999", null));
        cache.atualizar(evento("BTCUSDT", "999", t0));
        assertThat(cache.volatilidadePorSegundo("BTCUSDT")).contains(0.0);
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
