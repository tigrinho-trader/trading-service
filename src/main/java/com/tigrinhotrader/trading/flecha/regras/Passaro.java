package com.tigrinhotrader.trading.flecha.regras;

import java.util.Arrays;
import java.util.Optional;

/** Passaros do Flecha Certeira e suas moedas base (o campo multiplica). Espelha BIRD_TYPES do frontend. */
public enum Passaro {
    RED("red", 2),
    BIG("big", 1),
    YELLOW("yellow", 4),
    BLUE("blue", 5),
    GOLD("gold", 15);

    private final String id;
    private final int moedas;

    Passaro(String id, int moedas) {
        this.id = id;
        this.moedas = moedas;
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
