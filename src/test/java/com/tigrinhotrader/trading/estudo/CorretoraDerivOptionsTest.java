package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
}
