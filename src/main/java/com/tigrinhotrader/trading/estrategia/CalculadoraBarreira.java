package com.tigrinhotrader.trading.estrategia;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Preco justo da aposta "sem toque" (BARREIRA): o jogador ganha se o preco NAO encostar no alvo ate o fim.
 *
 * <p>Pelo principio da reflexao do movimento browniano sem tendencia, a chance de o preco nao tocar uma barreira
 * a distancia d (em log) durante T segundos, com volatilidade sigma por raiz de segundo, e
 * {@code erf(z / sqrt(2))}, com {@code z = d / (sigma * sqrt(T))}. O multiplicador devolve {@link #MARGEM} dessa
 * aposta justa: alvo colado no preco paga muito (e quase sempre perde); alvo longe paga pouco.
 *
 * <p>O frontend repete esta conta pra mostrar o multiplicador ao vivo; os parametros saem de GET /ordens/barreira.
 */
public final class CalculadoraBarreira {

    /** Parte do valor justo que o jogo devolve (o resto e a "vantagem da casa"). */
    public static final double MARGEM = 0.95;
    public static final BigDecimal MULTIPLICADOR_MINIMO = new BigDecimal("1.10");
    public static final BigDecimal MULTIPLICADOR_MAXIMO = new BigDecimal("20.00");
    /** Alvo a menos de 0,001% do preco ja nasceria tocado. */
    public static final double DISTANCIA_MINIMA = 0.00001;
    /** Sem historico ainda: chute conservador (~0,008% por segundo, perto do BTC). */
    public static final double VOLATILIDADE_PADRAO = 0.00008;
    static final double VOLATILIDADE_MINIMA = 0.000005;
    static final double VOLATILIDADE_MAXIMA = 0.002;

    private CalculadoraBarreira() {
    }

    public static double distancia(BigDecimal precoEntrada, BigDecimal alvo) {
        return Math.abs(Math.log(alvo.doubleValue() / precoEntrada.doubleValue()));
    }

    public static double volatilidadeLimitada(double sigma) {
        return Math.min(VOLATILIDADE_MAXIMA, Math.max(VOLATILIDADE_MINIMA, sigma));
    }

    /** Chance de o preco nao encostar no alvo durante a rodada. */
    public static double probabilidadeSemToque(double distancia, double sigma, int duracaoSegundos) {
        double z = distancia / (volatilidadeLimitada(sigma) * Math.sqrt(duracaoSegundos));
        return erf(z / Math.sqrt(2));
    }

    /**
     * Multiplicador da aposta, ja arredondado e limitado ao maximo. Pode ficar abaixo de
     * {@link #MULTIPLICADOR_MINIMO}: quem cria a ordem recusa (alvo longe demais nao vale a pena).
     */
    public static BigDecimal multiplicador(double distancia, double sigma, int duracaoSegundos) {
        double chance = probabilidadeSemToque(distancia, sigma, duracaoSegundos);
        double bruto = chance <= 0 ? Double.MAX_VALUE : MARGEM / chance;
        return BigDecimal.valueOf(Math.min(bruto, MULTIPLICADOR_MAXIMO.doubleValue())).setScale(2, RoundingMode.DOWN);
    }

    /** Abramowitz & Stegun 7.1.26 (erro maximo 1,5e-7), suficiente pra precificar. */
    static double erf(double x) {
        double sinal = Math.signum(x);
        double a = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * a);
        double polinomio = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027
                + t * 1.061405429))));
        return sinal * (1 - polinomio * Math.exp(-a * a));
    }
}
