package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;

/** Numeros das operacoes fechadas de um ativo num modo (ex.: ETHUSDT SIMULADO). */
public record NumerosAtivo(String simbolo, ModoEstudo modo, NumerosEstudo numeros) {

    /** Usado pela consulta agrupada do repositorio. */
    public NumerosAtivo(String simbolo, ModoEstudo modo, Long operacoes, Long acertos, BigDecimal resultadoBrutoBrl,
                        BigDecimal taxasBrl, BigDecimal resultadoLiquidoBrl, BigDecimal placarBinarioBrl) {
        this(simbolo, modo, new NumerosEstudo(operacoes, acertos, resultadoBrutoBrl, taxasBrl, resultadoLiquidoBrl,
                placarBinarioBrl));
    }
}
