package com.tigrinhotrader.trading.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TradingConfigTest {

    private final TradingConfig config = new TradingConfig();

    @Test
    void propriedadesTemPadroesParaRodarLocal() {
        TradingProperties padrao = new TradingProperties(null, null, null);
        assertThat(padrao.marketDataUrl()).isEqualTo("http://localhost:8083");
        assertThat(padrao.walletUrl()).isEqualTo("http://localhost:8082");
        assertThat(padrao.timeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void criaClientesHttpERelogio() {
        TradingProperties props = new TradingProperties("http://md", "http://wallet", Duration.ofSeconds(1));
        assertThat(config.marketDataClient(RestClient.builder(), props)).isNotNull();
        assertThat(config.walletClient(RestClient.builder(), props)).isNotNull();
        assertThat(config.clock()).isNotNull();
    }
}
