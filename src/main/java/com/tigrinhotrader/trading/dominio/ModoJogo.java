package com.tigrinhotrader.trading.dominio;

import java.math.BigDecimal;

/**
 * Nivel de risco da rodada. Quanto mais dificil, mais a aposta paga e mais facil e perder:
 * <ul>
 *   <li>FACIL: paga menos, mas a derrota so leva metade da aposta e a faixa do LATERAL e larga;</li>
 *   <li>DIFICIL: regras classicas (1,90x / 2,50x, derrota leva tudo);</li>
 *   <li>INSANO: paga 4x / 6x, mas ALTA/BAIXA so ganham se o preco andar pelo menos 0,02%
 *       e o LATERAL so ganha se ficar dentro de 0,01%.</li>
 * </ul>
 */
public enum ModoJogo {

    FACIL("1.50", "1.80", "0.50", "0", "0.0010"),
    DIFICIL("1.90", "2.50", "1.00", "0", "0.0005"),
    INSANO("4.00", "6.00", "1.00", "0.0002", "0.0001");

    private final BigDecimal multiplicadorDirecional;
    private final BigDecimal multiplicadorLateral;
    private final BigDecimal fracaoPerda;
    private final BigDecimal movimentoMinimo;
    private final BigDecimal toleranciaLateral;

    ModoJogo(String multiplicadorDirecional, String multiplicadorLateral, String fracaoPerda,
             String movimentoMinimo, String toleranciaLateral) {
        this.multiplicadorDirecional = new BigDecimal(multiplicadorDirecional);
        this.multiplicadorLateral = new BigDecimal(multiplicadorLateral);
        this.fracaoPerda = new BigDecimal(fracaoPerda);
        this.movimentoMinimo = new BigDecimal(movimentoMinimo);
        this.toleranciaLateral = new BigDecimal(toleranciaLateral);
    }

    /** Quanto ALTA e BAIXA devolvem na vitoria (aposta x multiplicador). */
    public BigDecimal multiplicadorDirecional() {
        return multiplicadorDirecional;
    }

    /** Quanto LATERAL devolve na vitoria. */
    public BigDecimal multiplicadorLateral() {
        return multiplicadorLateral;
    }

    /** Parte da aposta perdida na derrota (0,50 = metade, 1,00 = tudo). */
    public BigDecimal fracaoPerda() {
        return fracaoPerda;
    }

    /** Variacao minima (fracao do preco) que ALTA/BAIXA precisam pra ganhar; zero = qualquer movimento. */
    public BigDecimal movimentoMinimo() {
        return movimentoMinimo;
    }

    /** Variacao maxima (fracao do preco) em que o LATERAL ainda ganha. */
    public BigDecimal toleranciaLateral() {
        return toleranciaLateral;
    }
}
