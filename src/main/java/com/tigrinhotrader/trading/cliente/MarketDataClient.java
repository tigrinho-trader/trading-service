package com.tigrinhotrader.trading.cliente;

import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Consulta sincrona de cotacao, usada so quando o preco ainda nao chegou pela fila. */
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

    private final RestClient restClient;

    public MarketDataClient(RestClient restClient) {
        this.restClient = restClient;
    }

    record CotacaoResposta(String simbolo, BigDecimal preco) {
    }

    public Optional<BigDecimal> precoAtual(String simbolo) {
        try {
            CotacaoResposta resposta = restClient.get()
                    .uri("/cotacoes/{simbolo}", simbolo)
                    .retrieve()
                    .body(CotacaoResposta.class);
            return Optional.ofNullable(resposta).map(CotacaoResposta::preco);
        } catch (RestClientException e) {
            log.warn("market-data-service sem cotacao de {}: {}", simbolo, e.getMessage());
            return Optional.empty();
        }
    }
}
