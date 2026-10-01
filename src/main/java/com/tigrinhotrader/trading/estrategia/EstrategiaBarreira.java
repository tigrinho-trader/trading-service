package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * "Sem toque": ganha se o preco nao encostar no alvo da ordem. O toque no meio da rodada e verificado a cada
 * preco recebido ({@code OrdemService.verificarBarreiras}); aqui fica a regra e a checagem final.
 * O multiplicador depende do alvo e da volatilidade, entao vem da {@link CalculadoraBarreira}, nao do modo.
 */
@Component
public class EstrategiaBarreira implements EstrategiaResultado {

    @Override
    public TipoOrdem tipo() {
        return TipoOrdem.BARREIRA;
    }

    @Override
    public BigDecimal multiplicador(ModoJogo modo) {
        throw new UnsupportedOperationException("O multiplicador da barreira depende do alvo: use CalculadoraBarreira");
    }

    @Override
    public StatusOrdem resolver(BigDecimal precoEntrada, BigDecimal precoSaida, ModoJogo modo) {
        throw new UnsupportedOperationException("A barreira precisa do alvo da ordem");
    }

    @Override
    public StatusOrdem resolver(Ordem ordem, BigDecimal precoSaida) {
        return tocou(ordem.getPrecoEntrada(), ordem.getAlvo(), precoSaida) ? StatusOrdem.PERDEU : StatusOrdem.GANHOU;
    }

    /** Alvo acima da entrada e tocado quando o preco chega nele ou passa; abaixo, idem pra baixo. */
    public static boolean tocou(BigDecimal precoEntrada, BigDecimal alvo, BigDecimal preco) {
        return alvo.compareTo(precoEntrada) > 0 ? preco.compareTo(alvo) >= 0 : preco.compareTo(alvo) <= 0;
    }
}
