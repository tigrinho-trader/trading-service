package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class EstrategiaBaixa implements EstrategiaResultado {

    private static final BigDecimal MULTIPLICADOR = new BigDecimal("1.90");

    @Override
    public TipoOrdem tipo() {
        return TipoOrdem.BAIXA;
    }

    @Override
    public BigDecimal multiplicador() {
        return MULTIPLICADOR;
    }

    @Override
    public StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida) {
        int comparacao = precoSaida.compareTo(precoEntrada);
        if (comparacao == 0) {
            return StatusOrdem.EMPATOU;
        }
        return comparacao < 0 ? StatusOrdem.GANHOU : StatusOrdem.PERDEU;
    }
}
