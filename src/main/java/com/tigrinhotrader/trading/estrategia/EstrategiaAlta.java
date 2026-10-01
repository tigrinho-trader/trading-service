package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.TipoOrdem;
import org.springframework.stereotype.Component;

@Component
public class EstrategiaAlta extends EstrategiaDirecional {

    @Override
    public TipoOrdem tipo() {
        return TipoOrdem.ALTA;
    }

    @Override
    protected int direcao() {
        return 1;
    }
}
