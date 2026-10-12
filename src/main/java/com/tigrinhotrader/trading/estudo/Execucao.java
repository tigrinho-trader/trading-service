package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;

/**
 * Uma ordem executada. Os fluxos tem sinal (+ entrou na conta, - saiu) e ja descontam as taxas pagas em USDT ou
 * BTC; taxa paga em outro ativo (ex.: BNB) vem em {@code taxaOutraUsdt}, ja convertida.
 *
 * @param quantidadeBtc quantidade executada (bruta)
 * @param precoMedio preco medio de execucao, em USDT
 * @param taxaUsdt todas as taxas desta ordem convertidas para USDT (so para relatorio)
 */
public record Execucao(String ordemId, BigDecimal quantidadeBtc, BigDecimal precoMedio, BigDecimal fluxoUsdt,
                       BigDecimal fluxoBtc, BigDecimal taxaUsdt, BigDecimal taxaOutraUsdt) {
}
