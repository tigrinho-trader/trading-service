package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Como o contrato liquidado da Deriv vira acerto, erro ou empate no placar do estudo. */
class CorretoraDerivOptionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal ENTRADA = new BigDecimal("1300.50");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static JsonNode contrato(String entrada, String saida) throws Exception {
        return JSON.readTree("{\"entry_tick\":" + entrada + ",\"exit_tick\":" + saida + "}");
    }

    @ParameterizedTest(name = "{0}, saida {2}, lucro {3} -> {4}")
    @CsvSource({
            // direcao, tick de entrada, tick de saida, lucro do contrato (USD), placar esperado
            "ALTA,  1300.50, 1301.20,  1.71,  1",   // CALL ganhou
            "ALTA,  1300.50, 1299.80, -1.80, -1",   // CALL perdeu
            "QUEDA, 1300.50, 1299.80,  1.71,  1",   // PUT ganhou: antes virava erro
            "QUEDA, 1300.50, 1301.20, -1.80, -1",   // PUT perdeu: antes virava acerto
            "QUEDA, 1300.50, 1300.50, -1.80,  0",   // empate: a Deriv cobra, o jogo devolve
    })
    void contratoLiquidadoViraOPlacarCerto(Direcao direcao, String tickEntrada, String tickSaida, String lucro,
                                           int esperado) throws Exception {
        BigDecimal saida = CorretoraDerivOptions.precoDeSaida(direcao, ENTRADA, contrato(tickEntrada, tickSaida),
                new BigDecimal(lucro));

        Execucao abertura = new Execucao("deriv-1", ZERO, ENTRADA, new BigDecimal("-1.80"), ZERO, ZERO, ZERO);
        Execucao fechamento = new Execucao("deriv-fechado-1", ZERO, saida, new BigDecimal("3.51"), ZERO, ZERO, ZERO);
        ResultadoOperacao resultado = ResultadoOperacao.calcular(direcao, abertura, fechamento,
                new BigDecimal("5.40"), BigDecimal.TEN);

        assertThat(resultado.binarioBrl().signum()).isEqualTo(esperado);
        assertThat(resultado.acertou()).isEqualTo(esperado > 0);
    }

    @Test
    void contratoPerdidoNaoRecebeOPayout() throws Exception {
        // contratos reais da conta: o perdido traz payout 3.31 mas a Deriv pagou 0 (sell_price)
        JsonNode perdido = JSON.readTree("{\"status\":\"lost\",\"buy_price\":\"1.74\",\"payout\":\"3.31\","
                + "\"sell_price\":\"0.00\",\"profit\":\"-1.74\"}");
        JsonNode ganho = JSON.readTree("{\"status\":\"won\",\"buy_price\":\"3.49\",\"payout\":\"6.64\","
                + "\"sell_price\":\"6.64\",\"profit\":\"3.15\"}");
        assertThat(CorretoraDerivOptions.recebido(perdido, new BigDecimal("-1.74"))).isEqualByComparingTo("0");
        assertThat(CorretoraDerivOptions.recebido(ganho, new BigDecimal("-3.49"))).isEqualByComparingTo("6.64");
        // sem sell_price: pelo lucro; sem nada: o payout so se ganhou
        assertThat(CorretoraDerivOptions.recebido(JSON.readTree("{\"profit\":-1.74,\"payout\":3.31}"),
                new BigDecimal("-1.74"))).isEqualByComparingTo("0");
        assertThat(CorretoraDerivOptions.recebido(JSON.readTree("{\"status\":\"lost\",\"payout\":3.31}"),
                new BigDecimal("-1.74"))).isEqualByComparingTo("0");

        // o resultado em reais da perda e negativo (era +1,57 x cambio)
        Execucao abertura = new Execucao("deriv-1", ZERO, ENTRADA, new BigDecimal("-1.74"), ZERO, ZERO, ZERO);
        Execucao fechamento = new Execucao("deriv-fechado-1", ZERO,
                CorretoraDerivOptions.precoDeSaida(Direcao.ALTA, ENTRADA, perdido, new BigDecimal("-1.74")),
                CorretoraDerivOptions.recebido(perdido, new BigDecimal("-1.74")), ZERO, ZERO, ZERO);
        ResultadoOperacao r = ResultadoOperacao.calcular(Direcao.ALTA, abertura, fechamento, new BigDecimal("5.60"),
                BigDecimal.TEN);
        assertThat(r.liquidoBrl()).isEqualByComparingTo("-9.744");
    }

    @Test
    void empateNaApiNovaUsaEntrySpotEExitSpot() throws Exception {
        // a API nova manda entry_spot/exit_spot: antes so exit_tick era lido e todo empate virava erro
        JsonNode empate = JSON.readTree("{\"entry_spot\":1348.71,\"exit_spot\":1348.71,\"profit\":-1.74}");
        JsonNode subiu = JSON.readTree("{\"entry_spot\":1348.71,\"exit_spot\":1348.97,\"profit\":1.57}");

        assertThat(CorretoraDerivOptions.empatou(empate)).isTrue();
        assertThat(CorretoraDerivOptions.empatou(subiu)).isFalse();
        assertThat(CorretoraDerivOptions.precoDeSaida(Direcao.ALTA, ENTRADA, empate, new BigDecimal("-1.74")))
                .isEqualByComparingTo(ENTRADA);
    }

    @Test
    void operacaoGuardaOsTicksReaisDoContrato() {
        Instant agora = Instant.parse("2026-11-16T01:00:00Z");
        OperacaoEstudo op = OperacaoEstudo.nova("u1", UUID.randomUUID(), "1HZ100V", Direcao.ALTA, TipoPrevisao.BINARIA,
                ModoEstudo.REAL, BigDecimal.TEN, new BigDecimal("1348.50"), new BigDecimal("1348.50"), agora);
        Instant entradaEm = Instant.ofEpochSecond(1794791395);
        Execucao abertura = new Execucao("deriv-1", ZERO, new BigDecimal("1348.71"), new BigDecimal("-1.74"), ZERO,
                ZERO, ZERO, new Execucao.Ticks(new BigDecimal("1348.71"), entradaEm, null, null));
        op.registrarAbertura(abertura, new BigDecimal("5.60"), agora.plusSeconds(1));

        assertThat(op.getPrecoEntradaContrato()).isEqualByComparingTo("1348.71");
        assertThat(op.getEntradaEm()).isEqualTo(entradaEm);
        assertThat(op.getPrecoSaidaContrato()).isNull();

        Execucao fechamento = new Execucao("deriv-fechado-1", ZERO, new BigDecimal("1349.71"), new BigDecimal("3.31"),
                ZERO, ZERO, ZERO, new Execucao.Ticks(new BigDecimal("1348.71"), entradaEm, new BigDecimal("1348.97"),
                entradaEm.plusSeconds(5)));
        op.fechar(fechamento, ResultadoOperacao.calcular(Direcao.ALTA, abertura, fechamento, new BigDecimal("5.60"),
                BigDecimal.TEN), agora.plusSeconds(7));

        assertThat(op.getPrecoSaidaContrato()).isEqualByComparingTo("1348.97");
        assertThat(op.getSaidaEm()).isEqualTo(entradaEm.plusSeconds(5));
        assertThat(op.getPrecoEntradaContrato()).isEqualByComparingTo("1348.71");
    }
}
