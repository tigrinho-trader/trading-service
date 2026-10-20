package com.tigrinhotrader.trading.flecha.regras;

import java.util.Arrays;
import java.util.Optional;

/**
 * Passaros do Flecha Certeira e suas moedas base (o campo multiplica). Passaros nao pagam moedas: acertar so conta
 * como acerto (treino); moedas so vem das apostas das flechas de mercado, liquidadas pelo servidor.
 */
public enum Passaro {
    RED("red", 0, false),
    BIG("big", 0, false),
    YELLOW("yellow", 0, false),
    BLUE("blue", 0, false),
    GOLD("gold", 0, false),
    /** Passaro bitcoin: voa conforme a cotacao do BTC, fora do sorteio, nos campos que tem o ativo. */
    BTC("btc", 0, true),
    /** Passaro ethereum: voa conforme a cotacao do ETH, fora do sorteio, nos campos que tem o ativo. */
    ETH("eth", 0, true),
    /** Passaro do ouro: voa conforme a cotacao do ouro (PAXG), fora do sorteio, nos campos que tem o ativo. */
    OURO("ouro", 0, true),
    V100("v100", 0, true);

    private final String id;
    private final int moedas;
    private final boolean deMercado;

    Passaro(String id, int moedas, boolean deMercado) {
        this.id = id;
        this.moedas = moedas;
        this.deMercado = deMercado;
    }

    /** Passaro de um ativo (bitcoin, ethereum, ouro): fora do sorteio, aparece nos campos que tem o ativo. */
    public boolean deMercado() {
        return deMercado;
    }

    public String id() {
        return id;
    }

    public int moedas() {
        return moedas;
    }

    public static Optional<Passaro> porId(String id) {
        return Arrays.stream(values()).filter(p -> p.id.equals(id)).findFirst();
    }
}
