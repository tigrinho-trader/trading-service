package com.tigrinhotrader.trading.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientesRestTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://servico");
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();

    @Test
    void marketDataDevolvePrecoAtual() {
        servidor.expect(requestTo("http://servico/cotacoes/BTCUSDT"))
                .andRespond(withSuccess("{\"simbolo\":\"BTCUSDT\",\"preco\":65000.5}", MediaType.APPLICATION_JSON));
        assertThat(new MarketDataClient(builder.build()).precoAtual("BTCUSDT")).contains(new BigDecimal("65000.5"));
        servidor.verify();
    }

    @Test
    void marketDataSemCotacaoDevolveVazio() {
        servidor.expect(requestTo("http://servico/cotacoes/XPTO")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(new MarketDataClient(builder.build()).precoAtual("XPTO")).isEmpty();
    }

    @Test
    void walletDevolveSaldoMandandoUsuarioNoCabecalho() {
        servidor.expect(requestTo("http://servico/carteira"))
                .andExpect(header("X-Usuario-Id", "u1"))
                .andRespond(withSuccess("{\"usuarioId\":\"u1\",\"saldo\":1000.00}", MediaType.APPLICATION_JSON));
        assertThat(new WalletClient(builder.build()).saldo("u1")).isEqualByComparingTo("1000");
        servidor.verify();
    }

    @Test
    void walletForaDoArViraServicoIndisponivel() {
        servidor.expect(requestTo("http://servico/carteira")).andRespond(withServerError());
        WalletClient cliente = new WalletClient(builder.build());
        assertThatThrownBy(() -> cliente.saldo("u1")).isInstanceOf(ServicoIndisponivelException.class);
    }

    @Test
    void walletSemSaldoNaRespostaViraServicoIndisponivel() {
        servidor.expect(requestTo("http://servico/carteira"))
                .andRespond(withSuccess("{\"usuarioId\":\"u1\"}", MediaType.APPLICATION_JSON));
        WalletClient cliente = new WalletClient(builder.build());
        assertThatThrownBy(() -> cliente.saldo("u1")).isInstanceOf(ServicoIndisponivelException.class);
    }
}
