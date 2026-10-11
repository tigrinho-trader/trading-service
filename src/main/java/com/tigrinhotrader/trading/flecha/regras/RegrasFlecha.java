package com.tigrinhotrader.trading.flecha.regras;

/** Limites do Flecha Certeira (iguais aos do frontend). */
public final class RegrasFlecha {

    /** Maximo de flechas na aljava, somando todos os tipos. */
    public static final int MAX_FLECHAS = 40;
    /** Maximo de passaros que uma flecha pode atravessar. */
    public static final int MAX_COMBO = 5;
    /** Maximo de flechas por compra durante a sessao. */
    public static final int MAX_COMPRA = 20;

    private RegrasFlecha() {
    }
}
