package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Uma ordem executada. Os fluxos tem sinal (+ entrou na conta, - saiu) e ja descontam as taxas pagas em USDT ou
 * BTC; taxa paga em outro ativo (ex.: BNB) vem em {@code taxaOutraUsdt}, ja convertida.
 *
 * @param quantidadeBtc quantidade executada (bruta)
 * @param precoMedio preco medio de execucao, em USDT
 * @param taxaUsdt todas as taxas desta ordem convertidas para USDT (so para relatorio)
 * @param ticks ticks reais do contrato (opcao da Deriv): o de entrada e, no fechamento, o de saida; nulo nas
 *     outras corretoras
 */
public record Execucao(String ordemId, BigDecimal quantidadeBtc, BigDecimal precoMedio, BigDecimal fluxoUsdt,
                       BigDecimal fluxoBtc, BigDecimal taxaUsdt, BigDecimal taxaOutraUsdt, Ticks ticks) {

    public Execucao(String ordemId, BigDecimal quantidadeBtc, BigDecimal precoMedio, BigDecimal fluxoUsdt,
                    BigDecimal fluxoBtc, BigDecimal taxaUsdt, BigDecimal taxaOutraUsdt) {
        this(ordemId, quantidadeBtc, precoMedio, fluxoUsdt, fluxoBtc, taxaUsdt, taxaOutraUsdt, null);
    }

    /**
     * Ticks de um contrato por ticks: o preco e a hora em que ele comecou (tick de entrada) e terminou (tick de
     * saida). Campos ainda desconhecidos ficam nulos (a saida, logo depois da compra).
     */
    public record Ticks(BigDecimal entrada, Instant entradaEm, BigDecimal saida, Instant saidaEm) {
    }
}
