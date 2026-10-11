package com.tigrinhotrader.trading.flecha.servico;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao do Flecha Certeira.
 *
 * @param expiraApos          sessao ABERTA sem atividade por mais que isso e encerrada pelo agendador
 * @param intervaloMinimoTiro menor intervalo aceito entre dois tiros da mesma sessao (senao 429)
 */
@ConfigurationProperties(prefix = "flecha")
public record FlechaProperties(Duration expiraApos, Duration intervaloMinimoTiro) {

    public FlechaProperties {
        expiraApos = expiraApos == null ? Duration.ofMinutes(10) : expiraApos;
        intervaloMinimoTiro = intervaloMinimoTiro == null ? Duration.ofMillis(150) : intervaloMinimoTiro;
    }
}
