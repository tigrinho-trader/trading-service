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
     * Preco fixo, em qualquer campo, de uma flecha de mercado (bitcoin, ethereum, ouro): a aposta de sobe ou desce
     * do estudo, que vale R$ 10 ({@code estudo.aposta-binaria-brl}) a {@link #MOEDAS_POR_REAL} moedas por real. O
     * servidor liquida a aposta quando a operacao termina: fechada paga a aposta mais o resultado liquido da
     * operacao em moedas (nunca menos que 0); operacao recusada ou com falha devolve a aposta.
     */
    public static final int PRECO_FLECHA_MERCADO = 100;
    /** Cotacao da moeda do jogo: R$ 1 = 10 moedas. Converte o resultado liquido da operacao no pagamento. */
    public static final int MOEDAS_POR_REAL = 10;

    private RegrasFlecha() {
    }
}
