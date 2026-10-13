package com.tigrinhotrader.trading.flecha.regras;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Campos de tiro: entrada fixa, preco base da flecha, multiplicador das moedas, pesos do sorteio dos passaros e os
 * passaros de mercado (ativos) do campo: quanto mais caro o campo, mais ativos para apostar. Espelha RANGES do
 * frontend ({@code src/regras.js}); o servidor e a fonte da verdade do dinheiro.
 */
public enum Campo {
    VILAREJO("vilarejo", "Campo do Vilarejo", 5, 1, 2, pesos("5", "3", "1", "0", "0.3"),
            mercado(Passaro.BTC)),
    BOSQUE("bosque", "Bosque dos Arqueiros", 15, 3, 4, pesos("4", "2", "3", "1", "0.5"),
            mercado(Passaro.BTC, Passaro.ETH)),
    PICO("pico", "Pico das Águias", 40, 8, 10, pesos("3", "1", "3", "3", "0.8"),
            mercado(Passaro.BTC, Passaro.ETH, Passaro.OURO)),
    RESERVA_REAL("reserva-real", "Reserva Real", 100, 20, 25, pesos("2", "1", "3", "4", "1.2"),
            mercado(Passaro.BTC, Passaro.ETH, Passaro.OURO));

    private final String slug;
    private final String nome;
    private final BigDecimal entrada;
    private final int flechaBase;
    private final int mult;
    private final Map<Passaro, BigDecimal> pool;
    private final Set<Passaro> passarosMercado;

    Campo(String slug, String nome, int entrada, int flechaBase, int mult, Map<Passaro, BigDecimal> pool,
          Set<Passaro> passarosMercado) {
        this.slug = slug;
        this.nome = nome;
        this.entrada = BigDecimal.valueOf(entrada);
        this.flechaBase = flechaBase;
        this.mult = mult;
        this.pool = pool;
        this.passarosMercado = passarosMercado;
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

    /** Passaros de mercado (ativos) que voam no campo, na ordem do enum. */
    private static Set<Passaro> mercado(Passaro primeiro, Passaro... outros) {
        return Collections.unmodifiableSet(EnumSet.of(primeiro, outros));
    }

    public static Optional<Campo> porSlug(String slug) {
        return Arrays.stream(values()).filter(c -> c.slug.equals(slug)).findFirst();
    }

    /**
     * Custo de uma flecha neste campo = fatorPreco x preco base (a madeira tem fator 0: e gratis); flecha de mercado
     * tem preco fixo ({@link RegrasFlecha#PRECO_FLECHA_MERCADO}) em todo campo.
     */
    public BigDecimal custoFlecha(TipoFlecha tipo) {
        if (tipo.deMercado()) {
            return BigDecimal.valueOf(RegrasFlecha.PRECO_FLECHA_MERCADO);
        }
        return BigDecimal.valueOf((long) tipo.fatorPreco() * flechaBase);
    }

    /**
     * Valor do passaro neste campo = round(moedas base x mult), HALF_UP como o Math.round do frontend. Hoje todo
     * passaro vale 0: moedas so vem das apostas das flechas de mercado.
     */
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

    /** O passaro aparece neste campo (peso maior que zero no sorteio, ou passaro de mercado de um ativo do campo)? */
    public boolean temNoPool(Passaro passaro) {
        if (passaro.deMercado()) {
            return passarosMercado.contains(passaro);
        }
        return pool.getOrDefault(passaro, BigDecimal.ZERO).signum() > 0;
    }

    /**
     * A flecha vale neste campo? As comuns sim; as de mercado so se o passaro do ativo dela voa aqui (a flecha
     * Ethereum nao e vendida nem apostada no Vilarejo, que so tem o bitcoin).
     */
    public boolean temFlecha(TipoFlecha tipo) {
        return tipo.passaroAlvo().map(passarosMercado::contains).orElse(true);
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

    /** Passaros de mercado (ativos) do campo: o Vilarejo so tem bitcoin; quanto mais caro o campo, mais ativos. */
    public Set<Passaro> passarosMercado() {
        return passarosMercado;
    }
}
