package com.tigrinhotrader.trading.servico;

/** Pedido valido sintaticamente mas que quebra uma regra do jogo (HTTP 422). */
public class RegraNegocioException extends RuntimeException {
    public RegraNegocioException(String mensagem) {
        super(mensagem);
    }
}
