package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;

/**
 * Strategy: cada tipo de aposta sabe calcular o proprio resultado e quanto paga.
 * Adicionar um tipo novo de aposta = criar uma implementacao nova, sem mexer no servico.
 */
public interface EstrategiaResultado {

    TipoOrdem tipo();

    /** Quanto a aposta devolve em caso de vitoria (ex.: 1.90 = aposta + 90%). */
    BigDecimal multiplicador();

    StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida);
}
