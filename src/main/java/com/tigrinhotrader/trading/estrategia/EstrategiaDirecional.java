package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import java.math.BigDecimal;
import java.math.MathContext;

/** Base de ALTA e BAIXA: ganha quem acertar a direcao, com o movimento minimo exigido pelo modo. */
abstract class EstrategiaDirecional implements EstrategiaResultado {

    /** +1 se a aposta ganha com o preco subindo, -1 se ganha com o preco caindo. */
    protected abstract int direcao();

    @Override
    public BigDecimal multiplicador(ModoJogo modo) {
        return modo.multiplicadorDirecional();
    }

    @Override
    public StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida, ModoJogo modo) {
        BigDecimal movimento = precoSaida.subtract(precoEntrada)
                .divide(precoEntrada, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(direcao()));
        if (modo.movimentoMinimo().signum() == 0) {
            if (movimento.signum() == 0) {
                return StatusOrdem.EMPATOU;
            }
            return movimento.signum() > 0 ? StatusOrdem.GANHOU : StatusOrdem.PERDEU;
        }
        return movimento.compareTo(modo.movimentoMinimo()) >= 0 ? StatusOrdem.GANHOU : StatusOrdem.PERDEU;
    }
}
