package com.tigrinhotrader.trading.flecha.regras;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * Tipos de flecha: custam {@code fatorPreco} vezes o preco base do campo e multiplicam as moedas do passaro por
 * {@code fatorMoedas}; as de mercado tem preco fixo ({@link RegrasFlecha#PRECO_FLECHA_MERCADO}). Espelha ARROW_TYPES
 * do frontend. A madeira e gratis (treino) e passaros nao pagam moedas: so as apostas das flechas de mercado rendem.
 * As antigas flechas de ferro, ouro e diamante foram removidas (as guardadas sao reembolsadas, ver
 * {@code SessaoFlechaService#reembolsarFlechasAntigas}).
 */
public enum TipoFlecha {
    /** Flecha de treino: gratis em todo campo. */
    WOOD("wood", "Madeira", 0, new BigDecimal("1")),
    /**
     * Flecha de mercado: custa {@link RegrasFlecha#PRECO_FLECHA_MERCADO} em qualquer campo e e uma aposta binaria
     * (sobe ou desce em 5 s) registrada pelo estudo, nao pelos tiros. So voa ate o passaro bitcoin.
     */
    BTC("btc", "Bitcoin", 1, new BigDecimal("2")),
    /** Especial como a bitcoin, mas so acerta o passaro ethereum (campos com o ativo). */
    ETH("eth", "Ethereum", 1, new BigDecimal("2")),
    /** Especial como a bitcoin, mas so acerta o passaro do ouro (campos com o ativo). */
    OURO("ouro", "Ouro PAXG", 1, new BigDecimal("2")),
    V100("v100", "Volatilidade 100", 1, new BigDecimal("2"));

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

    /** Flecha de mercado (bitcoin, ethereum, ouro): preco fixo, guardada em qualquer campo e usada pelo estudo. */
    public boolean deMercado() {
        return passaroAlvo().isPresent();
    }

    /** O unico passaro que esta flecha acerta (flechas de mercado), ou vazio se ela acerta qualquer um. */
    public Optional<Passaro> passaroAlvo() {
        return switch (this) {
            case BTC -> Optional.of(Passaro.BTC);
            case ETH -> Optional.of(Passaro.ETH);
            case OURO -> Optional.of(Passaro.OURO);
            case V100 -> Optional.of(Passaro.V100);
            case WOOD -> Optional.empty();
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
