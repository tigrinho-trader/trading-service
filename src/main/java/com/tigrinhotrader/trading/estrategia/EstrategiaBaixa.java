package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.TipoOrdem;
import org.springframework.stereotype.Component;

@Component
public class EstrategiaBaixa extends EstrategiaDirecional {

    @Override
    public TipoOrdem tipo() {
        return TipoOrdem.BAIXA;
    }

    @Override
    protected int direcao() {
        return -1;
    }
}
