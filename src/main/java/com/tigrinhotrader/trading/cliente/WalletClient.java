package com.tigrinhotrader.trading.cliente;

import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Consulta o saldo ficticio do jogador antes de aceitar uma aposta. */
public class WalletClient {

    public static final String CABECALHO_USUARIO = "X-Usuario-Id";

    private final RestClient restClient;

    public WalletClient(RestClient restClient) {
        this.restClient = restClient;
    }

    record CarteiraResposta(String usuarioId, BigDecimal saldo) {
    }

    public BigDecimal saldo(String usuarioId) {
        try {
            CarteiraResposta resposta = restClient.get()
                    .uri("/carteira")
                    .header(CABECALHO_USUARIO, usuarioId)
                    .retrieve()
                    .body(CarteiraResposta.class);
            if (resposta == null || resposta.saldo() == null) {
                throw new ServicoIndisponivelException("wallet-service devolveu carteira vazia");
            }
            return resposta.saldo();
        } catch (RestClientException e) {
            throw new ServicoIndisponivelException("wallet-service indisponivel: " + e.getMessage());
        }
    }
}
