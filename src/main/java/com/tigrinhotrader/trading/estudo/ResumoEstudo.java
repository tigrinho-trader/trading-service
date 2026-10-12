package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;

/** O estudo ate agora: o modo em uso e os numeros da simulacao e das operacoes reais, separados. */
public record ResumoEstudo(ModoEstudo modoAtual, BigDecimal nocionalBrl, BigDecimal apostaBinariaBrl,
                           NumerosEstudo simulado, NumerosEstudo real) {
}
