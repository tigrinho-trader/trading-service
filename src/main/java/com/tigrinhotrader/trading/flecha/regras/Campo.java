package com.tigrinhotrader.trading.flecha.regras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Campos de tiro: entrada fixa, preco base da flecha, multiplicador das moedas e pesos do sorteio dos passaros.
 * Espelha RANGES do frontend ({@code src/regras.js}); o servidor e a fonte da verdade do dinheiro.
 */
public enum Campo {
    VILAREJO("vilarejo", "Campo do Vilarejo", 5, 1, 2, pesos("5", "3", "1", "0", "0.3")),
    BOSQUE("bosque", "Bosque dos Arqueiros", 15, 3, 4, pesos("4", "2", "3", "1", "0.5")),
    PICO("pico", "Pico das Águias", 40, 8, 10, pesos("3", "1", "3", "3", "0.8")),
    RESERVA_REAL("reserva-real", "Reserva Real", 100, 20, 25, pesos("2", "1", "3", "4", "1.2"));

    private final String slug;
    private final String nome;
    private final BigDecimal entrada;
    private final int flechaBase;
    private final int mult;
    private final Map<Passaro, BigDecimal> pool;

    Campo(String slug, String nome, int entrada, int flechaBase, int mult, Map<Passaro, BigDecimal> pool) {
        this.slug = slug;
        this.nome = nome;
        this.entrada = BigDecimal.valueOf(entrada);
        this.flechaBase = flechaBase;
        this.mult = mult;
        this.pool = pool;
    }

    /** Pesos na ordem red, big, yellow, blue, gold. */
    private static Map<Passaro, BigDecimal> pesos(String red, String big, String yellow, String blue, String gold) {
        Map<Passaro, BigDecimal> mapa = new EnumMap<>(Passaro.class);
        mapa.put(Passaro.RED, new BigDecimal(red));
        mapa.put(Passaro.BIG, new BigDecimal(big));
        mapa.put(Passaro.YELLOW, new BigDecimal(yellow));
        mapa.put(Passaro.BLUE, new BigDecimal(blue));
        mapa.put(Passaro.GOLD, new BigDecimal(gold));
        return Collections.unmodifiableMap(mapa);
    }

    public static Optional<Campo> porSlug(String slug) {
        return Arrays.stream(values()).filter(c -> c.slug.equals(slug)).findFirst();
    }

    /** Custo de uma flecha neste campo = fatorPreco x preco base. */
    public BigDecimal custoFlecha(TipoFlecha tipo) {
        return BigDecimal.valueOf((long) tipo.fatorPreco() * flechaBase);
    }

    /** Valor do passaro neste campo = round(moedas base x mult), HALF_UP como o Math.round do frontend. */
    public int valorPassaro(Passaro passaro) {
        return BigDecimal.valueOf(passaro.moedas()).multiply(BigDecimal.valueOf(mult))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /**
     * Moedas de um acerto: round(valor do passaro x fatorMoedas da flecha) x posicao no combo
     * (1o passaro da mesma flecha x1, 2o x2, ...).
     */
    public int moedasAcerto(Passaro passaro, TipoFlecha flecha, int posicaoCombo) {
        if (posicaoCombo < 1) {
            throw new IllegalArgumentException("Posicao no combo comeca em 1");
        }
        int base = BigDecimal.valueOf(valorPassaro(passaro)).multiply(flecha.fatorMoedas())
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
        return base * posicaoCombo;
    }

    /** O passaro aparece neste campo (peso maior que zero no sorteio, ou passaro de todo campo)? */
    public boolean temNoPool(Passaro passaro) {
        return passaro.emTodoCampo() || pool.getOrDefault(passaro, BigDecimal.ZERO).signum() > 0;
    }

    public String slug() {
        return slug;
    }

    public String nome() {
        return nome;
    }

    public BigDecimal entrada() {
        return entrada;
    }

    public int flechaBase() {
        return flechaBase;
    }

    public int mult() {
        return mult;
    }

    public Map<Passaro, BigDecimal> pool() {
        return pool;
    }
}
