package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import java.math.MathContext;
import org.springframework.stereotype.Component;

/** Ganha se o preco variar no maximo a tolerancia do modo (0,05% no DIFICIL) durante a rodada. */
@Component
public class EstrategiaLateral implements EstrategiaResultado {

    @Override
    public TipoOrdem tipo() {
        return TipoOrdem.LATERAL;
    }

    @Override
    public BigDecimal multiplicador(ModoJogo modo) {
        return modo.multiplicadorLateral();
    }

    @Override
    public StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida, ModoJogo modo) {
        BigDecimal variacao = precoSaida.subtract(precoEntrada).abs().divide(precoEntrada, MathContext.DECIMAL64);
        return variacao.compareTo(modo.toleranciaLateral()) <= 0 ? StatusOrdem.GANHOU : StatusOrdem.PERDEU;
    }
}
