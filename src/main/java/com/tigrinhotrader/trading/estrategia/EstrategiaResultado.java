package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;

/**
 * Strategy: cada tipo de aposta sabe calcular o proprio resultado e quanto paga em cada modo de jogo.
 * Adicionar um tipo novo de aposta = criar uma implementacao nova, sem mexer no servico.
 */
public interface EstrategiaResultado {

    TipoOrdem tipo();

    /** Quanto a aposta devolve em caso de vitoria (ex.: 1.90 = aposta + 90%). */
    BigDecimal multiplicador(ModoJogo modo);

    StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida, ModoJogo modo);

    /** Resultado olhando a ordem inteira (a BARREIRA precisa do alvo). */
    default StatusOrdem resolver(Ordem ordem, BigDecimal precoSaida) {
        return resolver(ordem.getPrecoEntrada(), precoSaida, ordem.getModo());
    }

    /** Multiplicador no modo classico ({@link ModoJogo#DIFICIL}). */
    default BigDecimal multiplicador() {
        return multiplicador(ModoJogo.DIFICIL);
    }

    /** Resultado no modo classico ({@link ModoJogo#DIFICIL}). */
    default StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida) {
        return resolver(precoEntrada, precoSaida, ModoJogo.DIFICIL);
    }
}
