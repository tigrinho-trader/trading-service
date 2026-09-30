package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import java.math.MathContext;
import org.springframework.stereotype.Component;

/** Ganha se o preco variar no maximo 0,05% (pra cima ou pra baixo) durante a rodada. */
@Component
public class EstrategiaLateral implements EstrategiaResultado {

    static final BigDecimal TOLERANCIA = new BigDecimal("0.0005");
    private static final BigDecimal MULTIPLICADOR = new BigDecimal("2.50");

    @Override
    public TipoOrdem tipo() {
        return TipoOrdem.LATERAL;
    }

    @Override
    public BigDecimal multiplicador() {
        return MULTIPLICADOR;
    }

    @Override
    public StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida) {
        BigDecimal variacao = precoSaida.subtract(precoEntrada).abs().divide(precoEntrada, MathContext.DECIMAL64);
        return variacao.compareTo(TOLERANCIA) <= 0 ? StatusOrdem.GANHOU : StatusOrdem.PERDEU;
    }
}
