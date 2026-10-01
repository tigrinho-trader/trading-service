package com.tigrinhotrader.trading.estrategia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BarreiraTest {

    private final EstrategiaBarreira estrategia = new EstrategiaBarreira();

    @Test
    void erfBateComValoresConhecidos() {
        assertThat(CalculadoraBarreira.erf(0)).isCloseTo(0, within(1e-7));
        assertThat(CalculadoraBarreira.erf(0.5)).isCloseTo(0.5204999, within(1e-6));
        assertThat(CalculadoraBarreira.erf(1)).isCloseTo(0.8427008, within(1e-6));
        assertThat(CalculadoraBarreira.erf(-1)).isCloseTo(-0.8427008, within(1e-6));
    }

    @Test
    void alvoAUmDesvioDaRodadaSobreviveEm68PorCento() {
        double sigma = 0.0001;
        int duracao = 25;
        double umDesvio = sigma * Math.sqrt(duracao);
        assertThat(CalculadoraBarreira.probabilidadeSemToque(umDesvio, sigma, duracao)).isCloseTo(0.6827, within(1e-4));
        // 0,95 / 0,6827 = 1,3915 -> arredonda pra baixo
        assertThat(CalculadoraBarreira.multiplicador(umDesvio, sigma, duracao)).isEqualByComparingTo("1.39");
    }

    @Test
    void quantoMaisPertoDoPrecoMaisPagaAteOTeto() {
        double sigma = 0.0001;
        BigDecimal longe = CalculadoraBarreira.multiplicador(0.002, sigma, 30);
        BigDecimal medio = CalculadoraBarreira.multiplicador(0.0003, sigma, 30);
        BigDecimal colado = CalculadoraBarreira.multiplicador(0.000001, sigma, 30);
        assertThat(longe).isLessThan(medio);
        assertThat(longe).isLessThan(CalculadoraBarreira.MULTIPLICADOR_MINIMO);
        assertThat(colado).isEqualByComparingTo(CalculadoraBarreira.MULTIPLICADOR_MAXIMO);
        assertThat(CalculadoraBarreira.multiplicador(0, sigma, 30)).isEqualByComparingTo("20.00");
    }

    @Test
    void volatilidadeAbsurdaELimitada() {
        assertThat(CalculadoraBarreira.volatilidadeLimitada(0)).isEqualTo(0.000005);
        assertThat(CalculadoraBarreira.volatilidadeLimitada(1)).isEqualTo(0.002);
        assertThat(CalculadoraBarreira.distancia(new BigDecimal("100"), new BigDecimal("101")))
                .isCloseTo(Math.log(1.01), within(1e-12));
    }

    @ParameterizedTest(name = "entrada 100, alvo {0}, preco {1} -> tocou {2}")
    @CsvSource({
            "101, 100.99, false",
            "101, 101,    true",
            "101, 102,    true",
            "99,  99.01,  false",
            "99,  99,     true",
            "99,  98,     true"
    })
    void tocaQuandoChegaNoAlvoPeloLadoDele(BigDecimal alvo, BigDecimal preco, boolean tocou) {
        assertThat(EstrategiaBarreira.tocou(new BigDecimal("100"), alvo, preco)).isEqualTo(tocou);
    }

    @Test
    void resolveOlhandoOAlvoDaOrdem() {
        Ordem ordem = Ordem.barreira(UUID.randomUUID(), "u1", "BTCUSDT", new BigDecimal("101"), BigDecimal.TEN,
                new BigDecimal("2.00"), new BigDecimal("100"), 30, Instant.now());
        assertThat(ordem.getTipo()).isEqualTo(TipoOrdem.BARREIRA);
        assertThat(ordem.getAlvo()).isEqualByComparingTo("101");
        assertThat(estrategia.tipo()).isEqualTo(TipoOrdem.BARREIRA);
        assertThat(estrategia.resolver(ordem, new BigDecimal("100.5"))).isEqualTo(StatusOrdem.GANHOU);
        assertThat(estrategia.resolver(ordem, new BigDecimal("101.5"))).isEqualTo(StatusOrdem.PERDEU);
    }

    @Test
    void semAlvoNaoDaPraPrecificarNemResolver() {
        assertThatThrownBy(() -> estrategia.multiplicador(ModoJogo.DIFICIL))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> estrategia.resolver(BigDecimal.ONE, BigDecimal.TEN, ModoJogo.DIFICIL))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
