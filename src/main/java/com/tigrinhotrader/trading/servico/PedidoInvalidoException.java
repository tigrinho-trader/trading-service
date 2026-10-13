package com.tigrinhotrader.trading.servico;

/** Pedido com algo que a API nao aceita mais (ex.: flechas de ferro, ouro ou diamante): HTTP 400. */
public class PedidoInvalidoException extends RuntimeException {
    public PedidoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
