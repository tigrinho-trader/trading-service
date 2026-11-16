package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** Uma conexao so para todas as flechas: cada resposta e cada atualizacao de contrato vai para quem espera. */
class RoteadorDerivTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final RoteadorDeriv roteador = new RoteadorDeriv();

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }

    @Test
    void cadaRespostaVaiParaOPedidoDela() throws Exception {
        CompletableFuture<JsonNode> proposta = roteador.esperarResposta(101);
        CompletableFuture<JsonNode> compra = roteador.esperarResposta(102);

        roteador.receber(json("{\"msg_type\":\"buy\",\"echo_req\":{\"req_id\":102},\"buy\":{\"contract_id\":7}}"));
        roteador.receber(json("{\"msg_type\":\"ping\",\"ping\":\"pong\"}"));   // sem req_id: ignorado

        assertThat(compra).isCompleted();
        assertThat(compra.get().path("buy").path("contract_id").asInt()).isEqualTo(7);
        assertThat(proposta).isNotDone();
        roteador.receber(json("{\"msg_type\":\"proposal\",\"req_id\":101,\"proposal\":{\"id\":\"P\"}}"));
        assertThat(proposta.get().path("proposal").path("id").asText()).isEqualTo("P");
    }

    @Test
    void erroDaDerivFalhaSoOPedidoDele() throws Exception {
        CompletableFuture<JsonNode> compra = roteador.esperarResposta(5);
        CompletableFuture<JsonNode> outro = roteador.esperarResposta(6);

        roteador.receber(json("{\"echo_req\":{\"req_id\":5},\"error\":{\"code\":\"InsufficientBalance\","
                + "\"message\":\"Your account balance is insufficient\"}}"));

        assertThatThrownBy(compra::get).isInstanceOf(ExecutionException.class)
                .hasMessageContaining("Your account balance is insufficient");
        assertThat(outro).isNotDone();
    }

    @Test
    void contratoRecebeOTickDeEntradaEDepoisALiquidacao() throws Exception {
        RoteadorDeriv.Contrato contrato = roteador.acompanhar("3776585139");
        CompletableFuture<JsonNode> assinatura = roteador.esperarResposta(9);

        // logo depois da compra: ainda sem tick de entrada
        roteador.receber(json("{\"msg_type\":\"proposal_open_contract\",\"echo_req\":{\"req_id\":9},"
                + "\"proposal_open_contract\":{\"contract_id\":3776585139,\"is_sold\":0}}"));
        assertThat(assinatura).isCompleted();
        assertThat(contrato.entrada).isNotDone();

        roteador.receber(json("{\"msg_type\":\"proposal_open_contract\",\"proposal_open_contract\":{"
                + "\"contract_id\":3776585139,\"entry_spot\":1348.71,\"entry_spot_time\":1794791395,\"is_sold\":0}}"));
        assertThat(RoteadorDeriv.ticks(contrato.entrada.get()))
                .isEqualTo(new Execucao.Ticks(new java.math.BigDecimal("1348.71"), Instant.ofEpochSecond(1794791395),
                        null, null));
        assertThat(contrato.liquidacao).isNotDone();

        // outro contrato no meio nao atrapalha
        roteador.receber(json("{\"msg_type\":\"proposal_open_contract\",\"proposal_open_contract\":{"
                + "\"contract_id\":1,\"is_sold\":1,\"status\":\"won\"}}"));
        assertThat(contrato.liquidacao).isNotDone();

        roteador.receber(json("{\"msg_type\":\"proposal_open_contract\",\"proposal_open_contract\":{"
                + "\"contract_id\":3776585139,\"entry_spot\":1348.71,\"entry_spot_time\":1794791395,"
                + "\"exit_spot\":1348.97,\"exit_spot_time\":1794791400,\"is_sold\":1,\"status\":\"won\",\"profit\":1.57}}"));
        Execucao.Ticks t = RoteadorDeriv.ticks(contrato.liquidacao.get());
        assertThat(t.saida()).isEqualByComparingTo("1348.97");
        assertThat(t.saidaEm()).isEqualTo(Instant.ofEpochSecond(1794791400));
    }

    @Test
    void conexaoCaindoFalhaQuemEspera() {
        CompletableFuture<JsonNode> pedido = roteador.esperarResposta(1);
        RoteadorDeriv.Contrato contrato = roteador.acompanhar("42");

        roteador.conexaoCaiu(new IllegalStateException("conexão com a Deriv caiu"));

        assertThat(pedido).isCompletedExceptionally();
        assertThat(contrato.liquidacao).isCompletedExceptionally();
        assertThat(roteador.contrato("42")).isNull();   // o fechamento consulta de novo numa conexao nova
    }

    @Test
    void ticksDaApiAntigaTambemServem() throws Exception {
        Execucao.Ticks t = RoteadorDeriv.ticks(json("{\"entry_tick\":\"100.5\",\"entry_tick_time\":10,"
                + "\"exit_tick\":\"100.7\",\"exit_tick_time\":15}"));
        assertThat(t).isEqualTo(new Execucao.Ticks(new java.math.BigDecimal("100.5"), Instant.ofEpochSecond(10),
                new java.math.BigDecimal("100.7"), Instant.ofEpochSecond(15)));
        assertThat(RoteadorDeriv.ticks(json("{\"contract_id\":1}"))).isNull();
        assertThat(RoteadorDeriv.vendido(json("{\"status\":\"lost\"}"))).isTrue();
        assertThat(RoteadorDeriv.vendido(json("{\"status\":\"open\",\"is_sold\":0}"))).isFalse();
    }
}
