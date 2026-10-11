package com.tigrinhotrader.trading.servico;

/** O pedido conflita com o estado atual do recurso (ex.: ja existe sessao aberta, sessao ja encerrada): HTTP 409. */
public class ConflitoException extends RuntimeException {
    public ConflitoException(String mensagem) {
        super(mensagem);
    }
}
