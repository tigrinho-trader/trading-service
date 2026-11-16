package com.tigrinhotrader.trading.painel;

/**
 * Carteira de poupanca do super-admin: recebe a parte poupada dos ganhos das flechas de mercado (taxa de poupanca do
 * painel). E uma carteira do wallet-service com este id, que nenhum login de jogador alcanca (o id do jogador vem do
 * token); so o painel mostra o saldo dela.
 */
public final class Poupanca {

    public static final String CARTEIRA = "poupanca-super-admin";
    public static final String TIPO_EVENTO = "POUPANCA";

    private Poupanca() {
    }
}
