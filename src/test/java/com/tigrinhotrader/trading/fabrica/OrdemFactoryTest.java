package com.tigrinhotrader.trading.fabrica;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import com.tigrinhotrader.trading.estrategia.EstrategiaAlta;
import com.tigrinhotrader.trading.estrategia.EstrategiaBaixa;
import com.tigrinhotrader.trading.estrategia.EstrategiaLateral;
import com.tigrinhotrader.trading.estrategia.RegistroEstrategias;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrdemFactoryTest {

    private final OrdemFactory fabrica = new OrdemFactory(
            new RegistroEstrategias(List.of(new EstrategiaAlta(), new EstrategiaBaixa(), new EstrategiaLateral())));
    private final Instant agora = Instant.parse("2026-09-30T12:00:00Z");
    private final BigDecimal preco = new BigDecimal("65000.12");

    @Test
    void barreiraPrecificaPelaDistanciaEVolatilidade() {
        BigDecimal entrada = new BigDecimal("100");
        // alvo a 1 desvio da rodada de 15s: 0,95 / 0,6827 = 1,39x
        double sigma = 0.0001;
        BigDecimal alvo = entrada.multiply(BigDecimal.valueOf(Math.exp(sigma * Math.sqrt(15))));
        Ordem ordem = fabrica.criarBarreira("u1", "btcusdt", alvo, BigDecimal.TEN, 15, entrada, sigma, agora);
        assertThat(ordem.getTipo()).isEqualTo(TipoOrdem.BARREIRA);
        assertThat(ordem.getSimbolo()).isEqualTo("BTCUSDT");
        assertThat(ordem.getAlvo()).isEqualByComparingTo(alvo);
        assertThat(ordem.getMultiplicador()).isEqualByComparingTo("1.39");
    }

    @Test
    void barreiraRecusaAlvoColadoLongeOuAusente() {
        BigDecimal entrada = new BigDecimal("100");
        assertThatThrownBy(() -> fabrica.criarBarreira("u1", "BTCUSDT", new BigDecimal("100.0001"), BigDecimal.TEN,
                15, entrada, 0.0001, agora)).hasMessageContaining("colado");
        assertThatThrownBy(() -> fabrica.criarBarreira("u1", "BTCUSDT", new BigDecimal("110"), BigDecimal.TEN, 15,
                entrada, 0.0001, agora)).hasMessageContaining("longe demais");
        assertThatThrownBy(() -> fabrica.criarBarreira("u1", "BTCUSDT", null, BigDecimal.TEN, 15, entrada, 0.0001,
                agora)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> fabrica.criarBarreira("u1", "BTCUSDT", new BigDecimal("101"), BigDecimal.TEN, 7,
                entrada, 0.0001, agora)).hasMessageContaining("Duracao");
        assertThatThrownBy(() -> fabrica.criar("u1", "BTCUSDT", TipoOrdem.BARREIRA, BigDecimal.TEN, 15, entrada, agora))
                .hasMessageContaining("alvo");
    }

    @Test
    void multiplicadorVemDoModoEscolhido() {
        Ordem insana = fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, ModoJogo.INSANO, BigDecimal.TEN, 15, preco, agora);
        assertThat(insana.getModo()).isEqualTo(ModoJogo.INSANO);
        assertThat(insana.getMultiplicador()).isEqualByComparingTo("4.00");

        Ordem padrao = fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, BigDecimal.TEN, 15, preco, agora);
        assertThat(padrao.getModo()).isEqualTo(ModoJogo.DIFICIL);
        assertThat(padrao.getMultiplicador()).isEqualByComparingTo("1.90");
    }

    @Test
    void criaRodadaAbertaComMultiplicadorDaEstrategia() {
        Ordem ordem = fabrica.criar("u1", "btcusdt", TipoOrdem.LATERAL, new BigDecimal("10.555"), 60, preco, agora);

        assertThat(ordem.getId()).isNotNull();
        assertThat(ordem.getSimbolo()).isEqualTo("BTCUSDT");
        assertThat(ordem.getValor()).isEqualByComparingTo("10.56");
        assertThat(ordem.getMultiplicador()).isEqualByComparingTo("2.50");
        assertThat(ordem.getStatus()).isEqualTo(StatusOrdem.ABERTA);
        assertThat(ordem.getCriadaEm()).isEqualTo(agora);
        assertThat(ordem.getExpiraEm()).isEqualTo(agora.plusSeconds(60));
        assertThat(ordem.getPrecoEntrada()).isEqualTo(preco);
        assertThat(ordem.getDuracaoSegundos()).isEqualTo(60);
        assertThat(ordem.getUsuarioId()).isEqualTo("u1");
    }

    @Test
    void recusaDuracaoForaDasPermitidas() {
        assertThatThrownBy(() -> fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, BigDecimal.TEN, 7, preco, agora))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("[15, 30, 60, 300]");
    }

    @Test
    void recusaValorForaDosLimites() {
        assertThatThrownBy(() -> fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, new BigDecimal("0.50"), 30, preco, agora))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, new BigDecimal("10000.01"), 30, preco, agora))
                .isInstanceOf(RegraNegocioException.class);
    }

    @Test
    void recusaPrecoDeEntradaInvalido() {
        assertThatThrownBy(() -> fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, BigDecimal.TEN, 30, null, agora))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> fabrica.criar("u1", "BTCUSDT", TipoOrdem.ALTA, BigDecimal.TEN, 30, BigDecimal.ZERO, agora))
                .isInstanceOf(RegraNegocioException.class);
    }
}
