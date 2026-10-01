package com.tigrinhotrader.trading.web;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.fabrica.OrdemFactory;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Regras do jogo pro frontend montar a tela sem repetir os numeros. */
public record RegrasResponse(List<Modo> modos, List<Integer> duracoesSegundos, BigDecimal valorMinimo,
                             BigDecimal valorMaximo) {

    /** Percentuais ja em %, ex.: movimentoMinimoPercentual 0.02 = 0,02%. */
    public record Modo(ModoJogo modo, BigDecimal multiplicadorDirecional, BigDecimal multiplicadorLateral,
                       BigDecimal perdaPercentual, BigDecimal movimentoMinimoPercentual,
                       BigDecimal toleranciaLateralPercentual) {

        static Modo de(ModoJogo m) {
            return new Modo(m, m.multiplicadorDirecional(), m.multiplicadorLateral(), percentual(m.fracaoPerda()),
                    percentual(m.movimentoMinimo()), percentual(m.toleranciaLateral()));
        }

        /** 0.0005 -> 0.05 (sem notacao cientifica, que o stripTrailingZeros sozinho gera pra 50 -> 5E+1). */
        private static BigDecimal percentual(BigDecimal fracao) {
            return new BigDecimal(fracao.movePointRight(2).stripTrailingZeros().toPlainString());
        }
    }

    public static RegrasResponse atuais() {
        return new RegrasResponse(
                Arrays.stream(ModoJogo.values()).map(Modo::de).toList(),
                OrdemFactory.DURACOES_PERMITIDAS.stream().sorted().toList(),
                OrdemFactory.VALOR_MINIMO,
                OrdemFactory.VALOR_MAXIMO);
    }
}
