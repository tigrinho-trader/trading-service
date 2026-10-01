package com.tigrinhotrader.trading.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading")
public record TradingProperties(String marketDataUrl, String walletUrl, Duration timeout) {

    public TradingProperties {
        marketDataUrl = marketDataUrl == null ? "http://localhost:8083" : marketDataUrl;
        walletUrl = walletUrl == null ? "http://localhost:8082" : walletUrl;
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }
}
