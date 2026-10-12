package com.tigrinhotrader.trading.flecha.regras;

import java.util.Arrays;
import java.util.Optional;

/** Passaros do Flecha Certeira e suas moedas base (o campo multiplica). Espelha BIRD_TYPES do frontend. */
public enum Passaro {
    RED("red", 2, false),
    BIG("big", 1, false),
    YELLOW("yellow", 4, false),
    BLUE("blue", 5, false),
    GOLD("gold", 15, false),
    /** Passaro bitcoin: voa conforme a cotacao do BTC e aparece em todo campo, fora do sorteio. */
    BTC("btc", 3, true);

    private final String id;
    private final int moedas;
    private final boolean emTodoCampo;

    Passaro(String id, int moedas, boolean emTodoCampo) {
        this.id = id;
        this.moedas = moedas;
        this.emTodoCampo = emTodoCampo;
    }

    /** Aparece em todos os campos, independente dos pesos do sorteio. */
    public boolean emTodoCampo() {
        return emTodoCampo;
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
