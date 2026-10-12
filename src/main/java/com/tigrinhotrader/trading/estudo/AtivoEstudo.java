package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Ativos do estudo e a flecha de mercado de cada um. So o BTC pode operar no modo REAL; ETH e ouro (PAXG) sao
 * sempre simulados com o preco real.
 */
public enum AtivoEstudo {
    BTC("BTCUSDT", TipoFlecha.BTC),
    ETH("ETHUSDT", TipoFlecha.ETH),
    OURO("PAXGUSDT", TipoFlecha.OURO);

    private final String simbolo;
    private final TipoFlecha flecha;

    AtivoEstudo(String simbolo, TipoFlecha flecha) {
        this.simbolo = simbolo;
        this.flecha = flecha;
    }

    public String simbolo() {
        return simbolo;
    }

    /** A flecha que, ao ser solta, abre uma operacao neste ativo. */
    public TipoFlecha flecha() {
        return flecha;
    }

    public static Optional<AtivoEstudo> porSimbolo(String simbolo) {
        if (simbolo == null) {
            return Optional.empty();
        }
        String procurado = simbolo.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(a -> a.simbolo.equals(procurado)).findFirst();
    }
}
