package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Simulacao com preco real: executa "ordens" no preco atual do cache (o mesmo que o market-data-service recebe da
 * Binance) e cobra a taxa configurada em cada ponta. Nada sai daqui.
 */
public class CorretoraSimulada implements Corretora {

    private final PrecoCache precos;
    private final String simbolo;
    private final BigDecimal taxa;

    public CorretoraSimulada(EstudoProperties propriedades, PrecoCache precos) {
        this.precos = precos;
        this.simbolo = propriedades.simbolo();
        this.taxa = propriedades.taxaSimulada();
    }

    @Override
    public ModoEstudo modo() {
        return ModoEstudo.SIMULADO;
    }

    @Override
    public Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
        BigDecimal preco = precoAtual();
        BigDecimal quantidade = nocionalUsdt.divide(preco, 8, RoundingMode.HALF_UP);
        return executar(idCliente, direcao == Direcao.ALTA, quantidade, preco);
    }

    @Override
    public Execucao fechar(Direcao direcao, Execucao abertura, String idCliente) {
        return executar(idCliente, direcao == Direcao.QUEDA, abertura.quantidadeBtc(), precoAtual());
    }

    private Execucao executar(String id, boolean compra, BigDecimal quantidade, BigDecimal preco) {
        BigDecimal valor = quantidade.multiply(preco);
        BigDecimal custo = valor.multiply(taxa);
        BigDecimal fluxoUsdt = compra ? valor.add(custo).negate() : valor.subtract(custo);
        BigDecimal fluxoBtc = compra ? quantidade : quantidade.negate();
        return new Execucao("sim-" + id, quantidade, preco, fluxoUsdt, fluxoBtc, custo, BigDecimal.ZERO);
    }

    private BigDecimal precoAtual() {
        return precos.preco(simbolo).filter(p -> p.signum() > 0)
                .orElseThrow(() -> new ServicoIndisponivelException("Sem cotacao de " + simbolo + " no momento"));
    }
}
