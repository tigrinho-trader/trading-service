package com.tigrinhotrader.trading.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrdemTest {

    private final Instant agora = Instant.parse("2026-09-30T12:00:00Z");

    private Ordem ordem() {
        return new Ordem(UUID.randomUUID(), "u1", "BTCUSDT", TipoOrdem.ALTA, new BigDecimal("100.00"),
                new BigDecimal("1.90"), new BigDecimal("50000"), 30, agora);
    }

    @Test
    void vitoriaPagaValorVezesMultiplicador() {
        Ordem o = ordem();
        assertThat(o.valorLiquido()).isEqualByComparingTo("0");
        o.resolver(StatusOrdem.GANHOU, new BigDecimal("50001"), agora.plusSeconds(30));
        assertThat(o.getValorPago()).isEqualByComparingTo("190.00");
        assertThat(o.valorLiquido()).isEqualByComparingTo("90.00");
        assertThat(o.getPrecoSaida()).isEqualByComparingTo("50001");
        assertThat(o.getResolvidaEm()).isEqualTo(agora.plusSeconds(30));
        assertThat(o.isAberta()).isFalse();
    }

    @Test
    void derrotaPerdeTudoEEmpateDevolve() {
        Ordem perdeu = ordem();
        perdeu.resolver(StatusOrdem.PERDEU, BigDecimal.ONE, agora);
        assertThat(perdeu.valorLiquido()).isEqualByComparingTo("-100.00");

        Ordem empatou = ordem();
        empatou.resolver(StatusOrdem.EMPATOU, new BigDecimal("50000"), agora);
        assertThat(empatou.valorLiquido()).isEqualByComparingTo("0");
        assertThat(empatou.getValorPago()).isEqualByComparingTo("100.00");
    }

    @Test
    void naoResolveDuasVezesNemComResultadoAberto() {
        Ordem o = ordem();
        assertThatThrownBy(() -> o.resolver(StatusOrdem.ABERTA, BigDecimal.ONE, agora))
                .isInstanceOf(IllegalArgumentException.class);
        o.resolver(StatusOrdem.PERDEU, BigDecimal.ONE, agora);
        assertThatThrownBy(() -> o.resolver(StatusOrdem.GANHOU, BigDecimal.ONE, agora))
                .isInstanceOf(IllegalStateException.class);
    }
}
