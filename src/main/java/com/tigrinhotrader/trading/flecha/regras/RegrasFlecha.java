package com.tigrinhotrader.trading.flecha.regras;

/** Limites do Flecha Certeira (iguais aos do frontend). */
public final class RegrasFlecha {

    /** Maximo de flechas na aljava, somando todos os tipos. */
    public static final int MAX_FLECHAS = 40;
    /** Maximo de passaros que uma flecha pode atravessar. */
    public static final int MAX_COMBO = 5;
    /** Maximo de flechas por compra durante a sessao. */
    public static final int MAX_COMPRA = 20;
    /**
     * Preco fixo, em qualquer campo, de uma flecha de mercado (bitcoin, ethereum, ouro): a aposta binaria de sobe ou
     * desce do estudo, que vale R$ 10 ({@code estudo.aposta-binaria-brl}), a 10 moedas = R$ 1. O servidor liquida a
     * aposta quando a operacao termina: acertou paga {@link #PAGAMENTO_ACERTO_MERCADO}, preco parado devolve a
     * aposta, errou nao paga nada; operacao recusada ou com falha devolve a aposta.
     */
    public static final int PRECO_FLECHA_MERCADO = 100;
    /** Pagamento de uma flecha de mercado que acertou a direcao: a aposta de volta mais 100. */
    public static final int PAGAMENTO_ACERTO_MERCADO = 2 * PRECO_FLECHA_MERCADO;

    private RegrasFlecha() {
    }
}
