package com.tigrinhotrader.trading.dominio;

/** Tipos de aposta de uma rodada. Cada um tem sua {@code EstrategiaResultado}. */
public enum TipoOrdem {
    /** Aposta que o preco de saida sera maior que o de entrada. */
    ALTA,
    /** Aposta que o preco de saida sera menor que o de entrada. */
    BAIXA,
    /** Aposta que o preco quase nao vai se mexer (dentro de uma faixa estreita). */
    LATERAL
}
