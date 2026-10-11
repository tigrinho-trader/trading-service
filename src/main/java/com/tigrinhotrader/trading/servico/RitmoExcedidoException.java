package com.tigrinhotrader.trading.servico;

/** Pedidos rapidos demais (ex.: tiros com menos de 150 ms de intervalo): HTTP 429. */
public class RitmoExcedidoException extends RuntimeException {
    public RitmoExcedidoException(String mensagem) {
        super(mensagem);
    }
}
