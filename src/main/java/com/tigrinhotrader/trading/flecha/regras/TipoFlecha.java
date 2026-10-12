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
    BTC("btc", "Bitcoin", 1, new BigDecimal("2")),
    /** Especial como a bitcoin, mas so acerta o passaro ethereum. */
    ETH("eth", "Ethereum", 1, new BigDecimal("2")),
    /** Especial como a bitcoin, mas so acerta o passaro do ouro. */
    OURO("ouro", "Ouro PAXG", 1, new BigDecimal("2"));

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

    /** Esta flecha pode acertar este passaro? (cada flecha de mercado so acerta o passaro do seu ativo) */
    public boolean acerta(Passaro passaro) {
        return passaroAlvo().map(alvo -> alvo == passaro).orElse(true);
    }

    /** O unico passaro que esta flecha acerta (flechas de mercado), ou vazio se ela acerta qualquer um. */
    public Optional<Passaro> passaroAlvo() {
        return switch (this) {
            case BTC -> Optional.of(Passaro.BTC);
            case ETH -> Optional.of(Passaro.ETH);
            case OURO -> Optional.of(Passaro.OURO);
            default -> Optional.empty();
        };
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
