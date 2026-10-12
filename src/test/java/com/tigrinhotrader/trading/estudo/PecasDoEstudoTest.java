package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class PecasDoEstudoTest {

    private static EstudoProperties padrao() {
        return new EstudoProperties(null, null, null, 0, null, null, null, 0, 0, null, null, false, null);
    }

    private static PrecoCache comPreco(String simbolo, String preco) {
        PrecoCache cache = new PrecoCache();
        cache.atualizar(new PrecoAtualizadoEvento(simbolo, new BigDecimal(preco), null, Instant.now()));
        return cache;
    }

    @Test
    void padroesDoEstudoSaoSegurosEAChaveNaoApareceEmLog() {
        EstudoProperties p = padrao();
        assertThat(p.modo()).isEqualTo(ModoEstudo.SIMULADO);
        assertThat(p.simbolo()).isEqualTo("BTCUSDT");
        assertThat(p.nocionalBrl()).isEqualByComparingTo("30");          // R$ 10 a 3x
        assertThat(p.fechaApos()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.maxOperacoesDia()).isEqualTo(300);
        assertThat(p.maxAbertas()).isEqualTo(5);
        assertThat(p.perdaMaximaDiaBrl()).isEqualByComparingTo("50");
        assertThat(p.intervaloPorJogador()).isEqualTo(Duration.ofSeconds(1));
        assertThat(p.binance().url()).isEqualTo("https://api.binance.com");
        assertThat(p.binance().temCredenciais()).isFalse();
        assertThat(p.binance().recvWindow()).isEqualTo(5000);

        EstudoProperties.Binance comChave = new EstudoProperties.Binance(" ", " k ", "s3gr3d0", 0, null);
        assertThat(comChave.temCredenciais()).isTrue();
        assertThat(comChave.apiKey()).isEqualTo("k");
        assertThat(comChave.toString()).doesNotContain("s3gr3d0").doesNotContain("k,").contains("configuradas");
        assertThat(new EstudoProperties.Binance(null, "k", "", 0, null).temCredenciais()).isFalse();
    }

    @Test
    void direcaoAceitaMaiusculasEMinusculas() {
        assertThat(Direcao.porNome(" alta ")).contains(Direcao.ALTA);
        assertThat(Direcao.porNome("QUEDA")).contains(Direcao.QUEDA);
        assertThat(Direcao.porNome("lateral")).isEmpty();
        assertThat(Direcao.porNome(null)).isEmpty();
        assertThat(Direcao.QUEDA.sinal()).isEqualTo(-1);
    }

    @Test
    void simulacaoAltaGanhaQuandoSobeEPagaTaxaNasDuasPontas() {
        PrecoCache precos = comPreco("BTCUSDT", "80000");
        CorretoraSimulada sim = new CorretoraSimulada(padrao(), precos);
        assertThat(sim.modo()).isEqualTo(ModoEstudo.SIMULADO);

        Execucao entrada = sim.abrir(Direcao.ALTA, new BigDecimal("8"), "op");
        assertThat(entrada.quantidadeBtc()).isEqualByComparingTo("0.0001");
        assertThat(entrada.fluxoUsdt()).isEqualByComparingTo("-8.008");   // 8 + 0,1%
        assertThat(entrada.ordemId()).isEqualTo("sim-op");

        precos.atualizar(new PrecoAtualizadoEvento("BTCUSDT", new BigDecimal("80400"), null, Instant.now().plusSeconds(5)));
        Execucao saida = sim.fechar(Direcao.ALTA, entrada, "op-f");
        ResultadoOperacao r = ResultadoOperacao.calcular(Direcao.ALTA, entrada, saida, new BigDecimal("5"));

        assertThat(r.brutoUsdt()).isEqualByComparingTo("0.04");           // 0,0001 x 400
        assertThat(r.taxasUsdt()).isEqualByComparingTo("0.01604");        // 0,008 + 0,00804
        assertThat(r.liquidoUsdt()).isEqualByComparingTo("0.02396");
        assertThat(r.liquidoBrl()).isEqualByComparingTo("0.1198");
        assertThat(r.brutoBrl()).isEqualByComparingTo("0.2");
        assertThat(r.taxasBrl()).isEqualByComparingTo("0.0802");
        assertThat(r.acertou()).isTrue();
    }

    @Test
    void simulacaoQuedaAcertaQuandoCaiEErraQuandoSobe() {
        PrecoCache precos = comPreco("BTCUSDT", "80000");
        CorretoraSimulada sim = new CorretoraSimulada(padrao(), precos);
        Execucao entrada = sim.abrir(Direcao.QUEDA, new BigDecimal("8"), "q");
        assertThat(entrada.fluxoBtc()).isEqualByComparingTo("-0.0001");
        assertThat(entrada.fluxoUsdt()).isEqualByComparingTo("7.992");

        precos.atualizar(new PrecoAtualizadoEvento("BTCUSDT", new BigDecimal("79600"), null, Instant.now().plusSeconds(5)));
        ResultadoOperacao caiu = ResultadoOperacao.calcular(Direcao.QUEDA, entrada,
                sim.fechar(Direcao.QUEDA, entrada, "q-f"), BigDecimal.ONE);
        assertThat(caiu.brutoUsdt()).isEqualByComparingTo("0.04");
        assertThat(caiu.liquidoUsdt()).isEqualByComparingTo("0.024040");   // 0,04 - 0,008 - 0,00796
        assertThat(caiu.acertou()).isTrue();

        precos.atualizar(new PrecoAtualizadoEvento("BTCUSDT", new BigDecimal("80000"), null, Instant.now().plusSeconds(9)));
        ResultadoOperacao parado = ResultadoOperacao.calcular(Direcao.QUEDA, entrada,
                sim.fechar(Direcao.QUEDA, entrada, "q-f"), BigDecimal.ONE);
        assertThat(parado.acertou()).isFalse();                         // nao mexeu: nao conta como acerto
        assertThat(parado.liquidoUsdt()).isNegative();                  // so pagou as taxas
    }

    @Test
    void liquidoContaOBtcQueSobrouPeloPrecoDeSaidaETaxaEmOutroAtivo() {
        // compra 0,001 recebendo 0,000999 (taxa em BTC); vende 0,001 pegando emprestada a diferenca
        Execucao entrada = new Execucao("a", new BigDecimal("0.001"), new BigDecimal("100000"), new BigDecimal("-100"),
                new BigDecimal("0.000999"), new BigDecimal("0.1"), BigDecimal.ZERO);
        Execucao saida = new Execucao("f", new BigDecimal("0.001"), new BigDecimal("101000"), new BigDecimal("101"),
                new BigDecimal("-0.001"), new BigDecimal("0.05"), new BigDecimal("0.05"));
        ResultadoOperacao r = ResultadoOperacao.calcular(Direcao.ALTA, entrada, saida, BigDecimal.ONE);
        assertThat(r.brutoUsdt()).isEqualByComparingTo("1");
        // 1 - 0,000001 x 101.000 - 0,05 (BNB)
        assertThat(r.liquidoUsdt()).isEqualByComparingTo("0.849");
    }

    @Test
    void simulacaoSemCotacaoNaoInventaPreco() {
        CorretoraSimulada sim = new CorretoraSimulada(padrao(), new PrecoCache());
        assertThatThrownBy(() -> sim.abrir(Direcao.ALTA, BigDecimal.TEN, "x"))
                .isInstanceOf(ServicoIndisponivelException.class).hasMessageContaining("BTCUSDT");
    }

    @Test
    void taxaDeAcertoDoResumo() {
        assertThat(new NumerosEstudo(0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).taxaAcertoPercentual())
                .isNull();
        assertThat(new NumerosEstudo(null, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).taxaAcertoPercentual())
                .isNull();
        assertThat(new NumerosEstudo(3L, 2L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO).taxaAcertoPercentual())
                .isEqualByComparingTo("66.7");
    }

    @Test
    void configSoUsaABinanceNoModoRealComChave() {
        EstudoConfig config = new EstudoConfig();
        Clock relogio = Clock.systemUTC();
        PrecoCache precos = new PrecoCache();
        assertThat(config.corretoraEstudo(padrao(), precos, relogio, RestClient.builder()))
                .isInstanceOf(CorretoraSimulada.class);

        EstudoProperties realSemChave = new EstudoProperties(ModoEstudo.REAL, null, null, 0, null, null, null, 0, 0,
                null, null, false, null);
        assertThat(config.corretoraEstudo(realSemChave, precos, relogio, RestClient.builder()))
                .isInstanceOf(CorretoraSimulada.class);

        EstudoProperties realComChave = new EstudoProperties(ModoEstudo.REAL, null, null, 0, null, null, null, 0, 0,
                null, null, false, new EstudoProperties.Binance(null, "k", "s", 0, null));
        assertThat(config.corretoraEstudo(realComChave, precos, relogio, RestClient.builder()))
                .isInstanceOf(CorretoraBinanceMargem.class);
    }

    @Test
    void agendadorRodaAsTarefasESegueDepoisDeUmaFalha() throws InterruptedException {
        AgendadorTarefas agendador = new AgendadorTarefas();
        try {
            CountDownLatch feitas = new CountDownLatch(2);
            agendador.executar(() -> {
                throw new IllegalStateException("tarefa com erro nao derruba o agendador");
            });
            agendador.executar(feitas::countDown);
            agendador.agendar(feitas::countDown, Instant.now().plusMillis(50));
            assertThat(feitas.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            agendador.desligar();
        }
    }
}
