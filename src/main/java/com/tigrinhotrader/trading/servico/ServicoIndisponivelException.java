package com.tigrinhotrader.trading.servico;

/** Dependencia (wallet, market-data) fora do ar ou sem dado: HTTP 503. */
public class ServicoIndisponivelException extends RuntimeException {
    public ServicoIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
