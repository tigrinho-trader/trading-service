package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeros das operacoes fechadas de um modo.
 *
 * @param operacoes operacoes fechadas
 * @param acertos operacoes em que o preco andou na direcao prevista
 * @param placarBinarioBrl soma do placar de aposta binaria (+aposta no acerto, -aposta no erro)
 */
public record NumerosEstudo(Long operacoes, Long acertos, BigDecimal resultadoBrutoBrl, BigDecimal taxasBrl,
                            BigDecimal resultadoLiquidoBrl, BigDecimal placarBinarioBrl) {

    /** Porcentagem de acertos de direcao (0 a 100), ou null sem operacoes. */
    public BigDecimal taxaAcertoPercentual() {
        if (operacoes == null || operacoes == 0) {
            return null;
        }
        return BigDecimal.valueOf(acertos * 100.0 / operacoes).setScale(1, RoundingMode.HALF_UP);
    }
}
