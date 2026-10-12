package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;

/** Onde as operacoes do estudo sao executadas: simulacao com preco real ou a margem isolada da Binance. */
public interface Corretora {

    ModoEstudo modo();

    /**
     * Abre a posicao na direcao prevista.
     *
     * @param nocionalUsdt tamanho da posicao em USDT
     * @param idCliente id unico da ordem (idempotencia na corretora)
     * @throws com.tigrinhotrader.trading.servico.RegraNegocioException quando a operacao nao pode ser feita
     *     (ex.: abaixo do minimo da corretora); a operacao fica RECUSADA
     */
    Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente);

    /** Fecha a posicao aberta por {@code abertura} (ordem no sentido contrario, mesma quantidade). */
    Execucao fechar(Direcao direcao, Execucao abertura, String idCliente);
}
