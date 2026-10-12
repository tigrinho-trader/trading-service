package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.preco.PrecoCache;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Escolhe onde as operacoes do estudo acontecem. O modo REAL so liga com ESTUDO_MODO=REAL e a chave no ambiente. */
@Configuration
public class EstudoConfig {

    private static final Logger LOG = LoggerFactory.getLogger(EstudoConfig.class);

    @Bean
    Corretora corretoraEstudo(EstudoProperties propriedades, PrecoCache precos, Clock relogio,
                              RestClient.Builder builder) {
        if (propriedades.modo() != ModoEstudo.REAL) {
            LOG.info("Estudo em modo SIMULADO: previsoes registradas com preco real, sem ordens na corretora");
            return new CorretoraSimulada(propriedades, precos);
        }
        if (!propriedades.binance().temCredenciais()) {
            LOG.error("ESTUDO_MODO=REAL sem BINANCE_API_KEY/BINANCE_API_SECRET: o estudo segue SIMULADO");
            return new CorretoraSimulada(propriedades, precos);
        }
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(propriedades.binance().timeout());
        fabrica.setReadTimeout(propriedades.binance().timeout());
        LOG.warn("Estudo em modo REAL: cada flecha bitcoin abre uma operacao de R$ {} na margem isolada {} ({})",
                propriedades.nocionalBrl(), propriedades.simbolo(), propriedades.binance());
        return new CorretoraBinanceMargem(builder.clone().requestFactory(fabrica).build(), propriedades, precos,
                relogio);
    }
}
