package com.tigrinhotrader.trading.flecha.servico;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do Flecha Certeira.
 *
 * @param expiraApos          sessao ABERTA sem atividade por mais que isso e encerrada pelo agendador
 * @param intervaloMinimoTiro menor intervalo aceito entre dois tiros da mesma sessao (senao 429)
 * @param taxaDevolucao       fracao descontada das flechas de mercado devolvidas ao encerrar (0.002 = 0,1% em cada
 *                            ponta da conversao real -> dolar -> real); {@code FLECHA_TAXA_DEVOLUCAO}
 */
@ConfigurationProperties(prefix = "flecha")
public record FlechaProperties(Duration expiraApos, Duration intervaloMinimoTiro, BigDecimal taxaDevolucao) {

    /** Taxa de devolucao padrao: 0,1% na ida e 0,1% na volta da conversao. */
    public static final BigDecimal TAXA_DEVOLUCAO_PADRAO = new BigDecimal("0.002");

    public FlechaProperties {
        expiraApos = expiraApos == null ? Duration.ofMinutes(10) : expiraApos;
        intervaloMinimoTiro = intervaloMinimoTiro == null ? Duration.ofMillis(150) : intervaloMinimoTiro;
        taxaDevolucao = taxaDevolucao == null ? TAXA_DEVOLUCAO_PADRAO : taxaDevolucao;
    }
}
