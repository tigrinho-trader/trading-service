package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.util.List;

/**
 * O estudo ate agora: o modo em uso (o do BTC; os outros ativos sao sempre simulados), os numeros da simulacao e
 * das operacoes reais, separados, e os mesmos numeros por ativo e modo.
 */
public record ResumoEstudo(ModoEstudo modoAtual, BigDecimal nocionalBrl, BigDecimal apostaBinariaBrl,
                           NumerosEstudo simulado, NumerosEstudo real, List<NumerosAtivo> porAtivo) {
}
