package com.tigrinhotrader.trading.flecha.dominio;

import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Flechas de mercado de um tipo e um valor: {@code quantidade} flechas bitcoin de 10 moedas, por exemplo. O valor e a
 * aposta de cada flecha em moedas e o tamanho da operacao do estudo em reais (1 moeda = R$ 1), com 2 casas.
 */
public record LoteFlecha(TipoFlecha flecha, BigDecimal valor, int quantidade) {

    public LoteFlecha {
        valor = valor.setScale(2, RoundingMode.HALF_UP);
    }

    /** valor x quantidade. */
    public BigDecimal total() {
        return valor.multiply(BigDecimal.valueOf(quantidade));
    }
}
