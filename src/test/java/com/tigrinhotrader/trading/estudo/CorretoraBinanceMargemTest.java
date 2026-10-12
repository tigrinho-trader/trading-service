package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tigrinhotrader.trading.estudo.CorretoraBinanceMargem.Filtros;
import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

/** Ordens na margem isolada da Binance contra um servidor falso: nada sai para a internet. */
class CorretoraBinanceMargemTest {

    private static final String URL = "https://binance.teste";
    private static final String SEGREDO = "segredo-de-teste";
    private static final String INFO = """
            {"symbols":[{"symbol":"BTCUSDT","filters":[
              {"filterType":"PRICE_FILTER","tickSize":"0.01"},
              {"filterType":"LOT_SIZE","minQty":"0.00001000","maxQty":"9000","stepSize":"0.00001000"},
              {"filterType":"NOTIONAL","minNotional":"5.00000000","applyMinToMarket":true}]}]}""";

    private MockRestServiceServer servidor;
    private PrecoCache precos;
    private CorretoraBinanceMargem corretora;

    @BeforeEach
    void preparar() {
        RestClient.Builder builder = RestClient.builder();
        servidor = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        precos = new PrecoCache();
        EstudoProperties propriedades = new EstudoProperties(ModoEstudo.REAL, "BTCUSDT", null, 0, null, null, null,
                0, 0, null, null, false, new EstudoProperties.Binance(URL, "chave-teste", SEGREDO, 5000, null));
        corretora = new CorretoraBinanceMargem(builder.build(), propriedades, precos,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC));
    }

    private void preco(String simbolo, String valor) {
        precos.atualizar(new PrecoAtualizadoEvento(simbolo, new BigDecimal(valor), null, Instant.now()));
    }

    private void esperarInfo() {
        servidor.expect(ExpectedCount.once(), requestTo(URL + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(INFO, MediaType.APPLICATION_JSON));
    }

    /** A assinatura da URL confere com HMAC-SHA256 do resto da consulta. */
    private static RequestMatcher assinaturaValida() {
        return pedido -> {
            String consulta = pedido.getURI().getRawQuery();
            int i = consulta.indexOf("&signature=");
            assertThat(i).isPositive();
            assertThat(consulta.substring(i + "&signature=".length()))
                    .isEqualTo(CorretoraBinanceMargem.assinar(consulta.substring(0, i), SEGREDO));
        };
    }

    private static String ordem(String lado, String qtd, String valor, String fills) {
        return """
                {"symbol":"BTCUSDT","orderId":%d,"clientOrderId":"x","status":"FILLED","side":"%s",
                 "executedQty":"%s","cummulativeQuoteQty":"%s","isIsolated":true,"fills":[%s]}"""
                .formatted(lado.equals("BUY") ? 111 : 222, lado, qtd, valor, fills);
    }

    @Test
    void assinaturaConfereComOExemploDaDocumentacaoDaBinance() {
        assertThat(CorretoraBinanceMargem.assinar(
                "symbol=LTCBTC&side=BUY&type=LIMIT&timeInForce=GTC&quantity=1&price=0.1&recvWindow=5000"
                        + "&timestamp=1499827319559",
                "NhqPtmdSJYdKjVHjA7PZj4Mge3R5YNiP1e3UZjInClVN65XAbvqqM6A7H5fATj0j"))
                .isEqualTo("c8db56825ae71d6d79447849e617115f4a920fa2acdcab2b053c4b2838bd6b71");
    }

    @Test
    void quantidadeRespeitaPassoEMinimos() {
        Filtros f = new Filtros(new BigDecimal("0.00001"), new BigDecimal("0.00001"), new BigDecimal("5"));
        // R$ 30 / 5,5 = US$ 5,45 a 83.600: 0,0000652 -> 0,00006 (US$ 5,02)
        assertThat(CorretoraBinanceMargem.quantidadePara(new BigDecimal("5.45"), new BigDecimal("83600"), f))
                .isEqualByComparingTo("0.00006");
        // a 80.000, 0,00006 vale US$ 4,80 (< 5): sobe um passo
        assertThat(CorretoraBinanceMargem.quantidadePara(new BigDecimal("5.45"), new BigDecimal("80000"), f))
                .isEqualByComparingTo("0.00007");
        // quantidade minima maior que o calculado
        Filtros minimoAlto = new Filtros(new BigDecimal("0.00001"), new BigDecimal("0.00007"), BigDecimal.ONE);
        assertThat(CorretoraBinanceMargem.quantidadePara(new BigDecimal("5.45"), new BigDecimal("83600"), minimoAlto))
                .isEqualByComparingTo("0.00007");
        // US$ 1 ficaria muito abaixo do minimo: recusa em vez de operar US$ 5
        assertThatThrownBy(() -> CorretoraBinanceMargem.quantidadePara(BigDecimal.ONE, new BigDecimal("83600"), f))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("abaixo do minimo da Binance (US$ 5)");
    }

    @Test
    void altaCompraNaMargemIsoladaComEmprestimoAutomatico() {
        preco("BTCUSDT", "83600");
        esperarInfo();
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-MBX-APIKEY", "chave-teste"))
                .andExpect(queryParam("symbol", "BTCUSDT"))
                .andExpect(queryParam("isIsolated", "TRUE"))
                .andExpect(queryParam("side", "BUY"))
                .andExpect(queryParam("type", "MARKET"))
                .andExpect(queryParam("quantity", "0.00006"))
                .andExpect(queryParam("sideEffectType", "MARGIN_BUY"))
                .andExpect(queryParam("newOrderRespType", "FULL"))
                .andExpect(queryParam("newClientOrderId", "est123a"))
                .andExpect(queryParam("recvWindow", "5000"))
                .andExpect(queryParam("timestamp", "1700000000000"))
                .andExpect(assinaturaValida())
                .andRespond(withSuccess(ordem("BUY", "0.00006000", "5.01600000",
                        "{\"price\":\"83600.00\",\"qty\":\"0.00006\",\"commission\":\"0.00000006\","
                                + "\"commissionAsset\":\"BTC\"}"), MediaType.APPLICATION_JSON));

        Execucao e = corretora.abrir(Direcao.ALTA, new BigDecimal("5.45"), "est123a");

        servidor.verify();
        assertThat(e.ordemId()).isEqualTo("111");
        assertThat(e.quantidadeBtc()).isEqualByComparingTo("0.00006");
        assertThat(e.precoMedio()).isEqualByComparingTo("83600");
        assertThat(e.fluxoUsdt()).isEqualByComparingTo("-5.016");
        assertThat(e.fluxoBtc()).isEqualByComparingTo("0.00005994");       // taxa cobrada em BTC
        assertThat(e.taxaUsdt()).isEqualByComparingTo("0.005016");
        assertThat(e.taxaOutraUsdt()).isEqualByComparingTo("0");
    }

    @Test
    void quedaVendeComTaxaEmUsdtETaxaEmBnbConvertida() {
        preco("BTCUSDT", "83600");
        esperarInfo();
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andExpect(queryParam("side", "SELL"))
                .andExpect(queryParam("sideEffectType", "MARGIN_BUY"))
                .andRespond(withSuccess(ordem("SELL", "0.00006", "5.016",
                        "{\"price\":\"83600\",\"qty\":\"0.00004\",\"commission\":\"0.003344\",\"commissionAsset\":\"USDT\"},"
                                + "{\"price\":\"83600\",\"qty\":\"0.00002\",\"commission\":\"0.000002\",\"commissionAsset\":\"BNB\"},"
                                + "{\"price\":\"83600\",\"qty\":\"0\",\"commission\":\"0\",\"commissionAsset\":\"BNB\"}"),
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL + "/api/v3/ticker/price?symbol=BNBUSDT"))
                .andRespond(withSuccess("{\"symbol\":\"BNBUSDT\",\"price\":\"600\"}", MediaType.APPLICATION_JSON));

        Execucao e = corretora.abrir(Direcao.QUEDA, new BigDecimal("5.45"), "est9a");

        servidor.verify();
        assertThat(e.fluxoUsdt()).isEqualByComparingTo("5.012656");         // 5,016 - 0,003344
        assertThat(e.fluxoBtc()).isEqualByComparingTo("-0.00006");
        assertThat(e.taxaOutraUsdt()).isEqualByComparingTo("0.0012");       // 0,000002 BNB x 600
        assertThat(e.taxaUsdt()).isEqualByComparingTo("0.004544");
    }

    @Test
    void semPrecoDeBnbATaxaEmBnbFicaZerada() {
        preco("BTCUSDT", "83600");
        esperarInfo();
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andRespond(withSuccess(ordem("BUY", "0.00006", "5.016",
                        "{\"price\":\"83600\",\"qty\":\"0.00006\",\"commission\":\"0.00001\",\"commissionAsset\":\"BNB\"}"),
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(URL + "/api/v3/ticker/price?symbol=BNBUSDT")).andRespond(withServerError());

        assertThat(corretora.abrir(Direcao.ALTA, new BigDecimal("5.45"), "x").taxaOutraUsdt()).isZero();
    }

    @Test
    void semPrecoNoCacheUsaOTickerDaBinanceEGuardaOsFiltros() {
        esperarInfo();
        servidor.expect(ExpectedCount.twice(), requestTo(URL + "/api/v3/ticker/price?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"price\":\"80000\"}", MediaType.APPLICATION_JSON));
        servidor.expect(ExpectedCount.twice(), requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andExpect(queryParam("quantity", "0.00007"))
                .andRespond(withSuccess(ordem("BUY", "0.00007", "5.6", ""), MediaType.APPLICATION_JSON));

        corretora.abrir(Direcao.ALTA, new BigDecimal("5.45"), "a1");
        corretora.abrir(Direcao.ALTA, new BigDecimal("5.45"), "a2");   // exchangeInfo so uma vez

        servidor.verify();
    }

    @Test
    void fecharRepeteComEmprestimoQuandoFaltaSaldo() {
        Execucao abertura = new Execucao("111", new BigDecimal("0.00006"), new BigDecimal("83600"),
                new BigDecimal("-5.016"), new BigDecimal("0.00005994"), BigDecimal.ZERO, BigDecimal.ZERO);
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andExpect(queryParam("side", "SELL"))
                .andExpect(queryParam("quantity", "0.00006"))
                .andExpect(queryParam("sideEffectType", "AUTO_REPAY"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-2010,\"msg\":\"Account has insufficient balance for requested action.\"}"));
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andExpect(queryParam("sideEffectType", "MARGIN_BUY"))
                .andExpect(queryParam("newClientOrderId", "estXf0b"))
                .andRespond(withSuccess(ordem("SELL", "0.00006", "5.022", ""), MediaType.APPLICATION_JSON));

        Execucao saida = corretora.fechar(Direcao.ALTA, abertura, "estXf0");

        servidor.verify();
        assertThat(saida.precoMedio()).isEqualByComparingTo("83700");
        assertThat(saida.fluxoUsdt()).isEqualByComparingTo("5.022");
    }

    @Test
    void quedaFechaRecomprandoEDevolvendoOEmprestimo() {
        Execucao abertura = new Execucao("222", new BigDecimal("0.00006"), new BigDecimal("83600"),
                new BigDecimal("5.016"), new BigDecimal("-0.00006"), BigDecimal.ZERO, BigDecimal.ZERO);
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andExpect(queryParam("side", "BUY"))
                .andExpect(queryParam("sideEffectType", "AUTO_REPAY"))
                .andRespond(withSuccess(ordem("BUY", "0.00006", "5.01", ""), MediaType.APPLICATION_JSON));

        assertThat(corretora.fechar(Direcao.QUEDA, abertura, "f").fluxoBtc()).isEqualByComparingTo("0.00006");
    }

    @Test
    void outrosErrosDaBinanceSobemComOCodigo() {
        Execucao abertura = new Execucao("1", new BigDecimal("0.00006"), BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-1021,\"msg\":\"Timestamp for this request is outside of the recvWindow.\"}"));

        assertThatThrownBy(() -> corretora.fechar(Direcao.ALTA, abertura, "f"))
                .isInstanceOfSatisfying(BinanceException.class, e -> assertThat(e.codigo()).isEqualTo(-1021))
                .hasMessageContaining("recvWindow");
    }

    @Test
    void erroSemJsonOuSemRedeViraErroClaro() {
        Execucao abertura = new Execucao("1", new BigDecimal("0.00006"), BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?"))).andRespond(withServerError().body("<html>"));
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andRespond(withException(new IOException("sem rede")));

        assertThatThrownBy(() -> corretora.fechar(Direcao.ALTA, abertura, "f"))
                .isInstanceOfSatisfying(BinanceException.class, e -> assertThat(e.codigo()).isEqualTo(-500));
        assertThatThrownBy(() -> corretora.fechar(Direcao.ALTA, abertura, "f"))
                .isInstanceOf(ServicoIndisponivelException.class)
                .hasMessageContaining("sem rede");
    }

    @Test
    void ordemSemExecucaoOuRespostaVaziaNaoContaComoOperacao() {
        Execucao abertura = new Execucao("1", new BigDecimal("0.00006"), BigDecimal.ONE, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?")))
                .andRespond(withSuccess(ordem("SELL", "0", "0", "").replace("FILLED", "EXPIRED"),
                        MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(startsWith(URL + "/sapi/v1/margin/order?"))).andRespond(withSuccess());

        assertThatThrownBy(() -> corretora.fechar(Direcao.ALTA, abertura, "f"))
                .isInstanceOf(BinanceException.class).hasMessageContaining("EXPIRED");
        assertThatThrownBy(() -> corretora.fechar(Direcao.ALTA, abertura, "f"))
                .isInstanceOf(BinanceException.class).hasMessageContaining("resposta vazia");
    }

    @Test
    void filtrosAntigosEProblemasNasRotasPublicas() {
        servidor.expect(requestTo(URL + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"symbols\":[{\"filters\":[{\"filterType\":\"MIN_NOTIONAL\",\"minNotional\":\"10\"}]}]}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> corretora.filtros())
                .isInstanceOf(ServicoIndisponivelException.class).hasMessageContaining("passo");

        servidor.reset();
        servidor.expect(requestTo(URL + "/api/v3/exchangeInfo?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"symbols\":[{\"filters\":[{\"filterType\":\"MIN_NOTIONAL\",\"minNotional\":\"10\"},"
                        + "{\"filterType\":\"LOT_SIZE\",\"stepSize\":\"0.00010\",\"minQty\":\"0.0001\"}]}]}",
                        MediaType.APPLICATION_JSON));
        assertThat(corretora.filtros()).isEqualTo(new Filtros(new BigDecimal("0.0001"), new BigDecimal("0.0001"),
                new BigDecimal("10")));

        servidor.reset();
        servidor.expect(requestTo(URL + "/api/v3/ticker/price?symbol=BTCUSDT"))
                .andRespond(withSuccess("{\"price\":\"0\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> corretora.abrir(Direcao.ALTA, BigDecimal.TEN, "x"))
                .isInstanceOf(ServicoIndisponivelException.class).hasMessageContaining("sem preco");

        servidor.reset();
        servidor.expect(requestTo(URL + "/api/v3/ticker/price?symbol=BTCUSDT"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":-1121,\"msg\":\"Invalid symbol.\"}"));
        assertThatThrownBy(() -> corretora.abrir(Direcao.ALTA, BigDecimal.TEN, "x"))
                .isInstanceOfSatisfying(BinanceException.class, e -> assertThat(e.codigo()).isEqualTo(-1121));

        servidor.reset();
        servidor.expect(requestTo(URL + "/api/v3/ticker/price?symbol=BTCUSDT"))
                .andRespond(withException(new IOException("caiu")));
        assertThatThrownBy(() -> corretora.abrir(Direcao.ALTA, BigDecimal.TEN, "x"))
                .isInstanceOf(ServicoIndisponivelException.class);
        assertThat(corretora.modo()).isEqualTo(ModoEstudo.REAL);
    }
}
