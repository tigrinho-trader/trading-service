package com.tigrinhotrader.trading.flecha.regras;

import java.math.BigDecimal;
import java.util.List;

/** Limites do Flecha Certeira (iguais aos do frontend). */
public final class RegrasFlecha {

    /** Maximo de flechas na aljava, somando todos os tipos. */
    public static final int MAX_FLECHAS = 40;
    /** Maximo de passaros que uma flecha pode atravessar. */
    public static final int MAX_COMBO = 5;
    /** Maximo de flechas por compra durante a sessao. */
    public static final int MAX_COMPRA = 20;
    /** Cotacao da moeda do jogo: R$ 1 = 1 moeda. Converte o resultado liquido da operacao no pagamento. */
    public static final int MOEDAS_POR_REAL = 1;
    /**
     * Niveis prontos do valor de uma flecha de mercado (nivel 1 = 10, 2 = 20, 3 = 30 moedas). O jogador tambem pode
     * escolher qualquer valor a partir de {@link #VALOR_MINIMO_FLECHA}, com ate 2 casas e sem teto (so o saldo
     * limita). O valor e a aposta em moedas e o tamanho da operacao do estudo em reais (1 moeda = R$ 1).
     */
    public static final List<Integer> NIVEIS_FLECHA = List.of(10, 20, 30);
    /** Menor valor de uma flecha de mercado, em moedas. */
    public static final BigDecimal VALOR_MINIMO_FLECHA = BigDecimal.ONE;
    /** Casas decimais aceitas no valor de uma flecha de mercado. */
    public static final int CASAS_VALOR_FLECHA = 2;
    /** Valor das flechas de mercado pedidas sem valor (clientes antigos que mandam so a quantidade): o nivel 1. */
    public static final BigDecimal VALOR_FLECHA_PADRAO = new BigDecimal("10.00");
    /**
     * Valor das flechas de mercado compradas antes de o jogador escolher o valor (preco fixo antigo): as guardadas
     * e as de sessoes abertas antes da mudanca entram como lotes deste valor.
     */
    public static final BigDecimal VALOR_FLECHA_ANTIGA = new BigDecimal("100.00");

    private RegrasFlecha() {
    }
}
