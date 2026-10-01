package com.tigrinhotrader.trading.config;

import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class TradingConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MarketDataClient marketDataClient(RestClient.Builder builder, TradingProperties propriedades) {
        return new MarketDataClient(cliente(builder, propriedades.marketDataUrl(), propriedades));
    }

    @Bean
    WalletClient walletClient(RestClient.Builder builder, TradingProperties propriedades) {
        return new WalletClient(cliente(builder, propriedades.walletUrl(), propriedades));
    }

    private static RestClient cliente(RestClient.Builder builder, String url, TradingProperties propriedades) {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(propriedades.timeout());
        fabrica.setReadTimeout(propriedades.timeout());
        return builder.clone().baseUrl(url).requestFactory(fabrica).build();
    }
}
