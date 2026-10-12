package com.tigrinhotrader.trading.flecha.regras;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de flecha: custam {@code fatorPreco} vezes o preco base do campo e multiplicam as moedas do passaro por
 * {@code fatorMoedas}. Espelha ARROW_TYPES do frontend.
 */
public enum TipoFlecha {
    WOOD("wood", "Madeira", 1, new BigDecimal("1")),
    IRON("iron", "Ferro", 2, new BigDecimal("2.5")),
    GOLD("gold", "Ouro", 4, new BigDecimal("5.5")),
    DIAMOND("diamond", "Diamante", 8, new BigDecimal("12")),
    /** Especial: leva 5 s ate o ponto mirado e so acerta o passaro bitcoin, com o dobro das moedas. */
    BTC("btc", "Bitcoin", 1, new BigDecimal("2"));

    private final String id;
    private final String nome;
    private final int fatorPreco;
    private final BigDecimal fatorMoedas;

    TipoFlecha(String id, String nome, int fatorPreco, BigDecimal fatorMoedas) {
        this.id = id;
        this.nome = nome;
        this.fatorPreco = fatorPreco;
        this.fatorMoedas = fatorMoedas;
    }

    /** Esta flecha so pode acertar este passaro? (a flecha bitcoin so acerta o passaro bitcoin) */
    public boolean acerta(Passaro passaro) {
        return this != BTC || passaro == Passaro.BTC;
    }

    public String id() {
        return id;
    }

    public String nome() {
        return nome;
    }

    public int fatorPreco() {
        return fatorPreco;
    }

    public BigDecimal fatorMoedas() {
        return fatorMoedas;
    }

    public static Optional<TipoFlecha> porId(String id) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id)).findFirst();
    }
}
