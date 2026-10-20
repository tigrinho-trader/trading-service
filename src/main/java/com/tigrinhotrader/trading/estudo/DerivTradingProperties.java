package com.tigrinhotrader.trading.estudo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credenciais opcionais do Options API da Deriv. Nunca devem ser logadas. */
@ConfigurationProperties(prefix = "deriv")
public record DerivTradingProperties(String apiToken, String appId, String accountId, String mode, String restUrl) {
    public DerivTradingProperties {
        apiToken = apiToken == null ? "" : apiToken.trim();
        appId = appId == null ? "" : appId.trim();
        accountId = accountId == null ? "" : accountId.trim();
        mode = mode == null || mode.isBlank() ? "SIMULADO" : mode.trim().toUpperCase();
        restUrl = restUrl == null || restUrl.isBlank() ? "https://api.derivws.com" : restUrl.replaceAll("/+$", "");
    }

    public boolean realHabilitado() {
        return "REAL".equals(mode) && !apiToken.isEmpty() && !appId.isEmpty();
    }

    public String accountType() {
        return realHabilitado() ? "real" : "demo";
    }
}
