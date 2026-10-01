package com.tigrinhotrader.trading;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaEvento;
import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoListener;
import com.tigrinhotrader.trading.servico.ResolvedorRodadas;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Fluxo completo com Spring + JPA (H2): abre uma rodada pela API, o tempo passa,
 * chega um preco novo pela fila e a rodada e resolvida publicando ordem.executada.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradingServiceApplicationTests {

    /** Relogio que o teste consegue adiantar. */
    static class RelogioAjustavel extends Clock {
        private Instant agora = Instant.parse("2026-09-30T12:00:00Z");

        void avancar(Duration d) {
            agora = agora.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return agora;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RelogioAjustavel relogioAjustavel() {
            return new RelogioAjustavel();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RelogioAjustavel relogio;
    @Autowired
    private PrecoAtualizadoListener precoListener;
    @Autowired
    private ResolvedorRodadas resolvedor;

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private WalletClient walletClient;
    @MockBean
    private MarketDataClient marketDataClient;

    private void chegaPreco(String simbolo, String preco) {
        precoListener.aoReceber(new PrecoAtualizadoEvento(simbolo, new BigDecimal(preco), BigDecimal.ZERO,
                relogio.instant()));
    }

    @Test
    void rodadaCompletaDeAltaVencedora() throws Exception {
        when(walletClient.saldo("jogador-1")).thenReturn(new BigDecimal("1000.00"));
        chegaPreco("BTCUSDT", "65000.00");

        String corpo = mockMvc.perform(post("/ordens")
                        .header("X-Usuario-Id", "jogador-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"btcusdt\",\"tipo\":\"ALTA\",\"valor\":100,\"duracaoSegundos\":15}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.precoEntrada").value(65000.00))
                .andExpect(jsonPath("$.multiplicador").value(1.90))
                .andReturn().getResponse().getContentAsString();
        JsonNode criada = objectMapper.readTree(corpo);
        String id = criada.get("id").asText();

        // antes de vencer, nada acontece
        resolvedor.executar();
        mockMvc.perform(get("/ordens/" + id).header("X-Usuario-Id", "jogador-1"))
                .andExpect(jsonPath("$.status").value("ABERTA"));

        relogio.avancar(Duration.ofSeconds(16));
        chegaPreco("BTCUSDT", "65010.00");
        resolvedor.executar();

        mockMvc.perform(get("/ordens/" + id).header("X-Usuario-Id", "jogador-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GANHOU"))
                .andExpect(jsonPath("$.valorPago").value(190.00))
                .andExpect(jsonPath("$.valorLiquido").value(90.00));

        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("tigrinho.eventos"), eq("ordem.executada"), evento.capture());
        assertEvento((OrdemExecutadaEvento) evento.getValue());

        mockMvc.perform(get("/ordens").header("X-Usuario-Id", "jogador-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/ordens/" + id).header("X-Usuario-Id", "outro-jogador"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rodadaInsanaPagaQuatroVezesEModoFacilPerdeMetade() throws Exception {
        when(walletClient.saldo("jogador-insano")).thenReturn(new BigDecimal("1000.00"));
        chegaPreco("SOLUSDT", "100.00");

        String insana = mockMvc.perform(post("/ordens").header("X-Usuario-Id", "jogador-insano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"SOLUSDT\",\"tipo\":\"ALTA\",\"valor\":10,"
                                + "\"duracaoSegundos\":15,\"modo\":\"INSANO\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modo").value("INSANO"))
                .andExpect(jsonPath("$.multiplicador").value(4.00))
                .andReturn().getResponse().getContentAsString();
        String facil = mockMvc.perform(post("/ordens").header("X-Usuario-Id", "jogador-insano")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"SOLUSDT\",\"tipo\":\"BAIXA\",\"valor\":10,"
                                + "\"duracaoSegundos\":15,\"modo\":\"FACIL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.multiplicador").value(1.50))
                .andReturn().getResponse().getContentAsString();

        relogio.avancar(Duration.ofSeconds(16));
        chegaPreco("SOLUSDT", "100.03");
        resolvedor.executar();

        mockMvc.perform(get("/ordens/" + objectMapper.readTree(insana).get("id").asText())
                        .header("X-Usuario-Id", "jogador-insano"))
                .andExpect(jsonPath("$.status").value("GANHOU"))
                .andExpect(jsonPath("$.valorLiquido").value(30.00));
        mockMvc.perform(get("/ordens/" + objectMapper.readTree(facil).get("id").asText())
                        .header("X-Usuario-Id", "jogador-insano"))
                .andExpect(jsonPath("$.status").value("PERDEU"))
                .andExpect(jsonPath("$.valorLiquido").value(-5.00));
    }

    @Test
    void barreiraTocadaPeloPrecoDaFilaPerdeNaHora() throws Exception {
        when(walletClient.saldo("aviador")).thenReturn(new BigDecimal("1000.00"));
        chegaPreco("XRPUSDT", "2.0000");

        String corpo = mockMvc.perform(post("/ordens").header("X-Usuario-Id", "aviador")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"XRPUSDT\",\"tipo\":\"BARREIRA\",\"alvo\":2.0010,\"valor\":10,"
                                + "\"duracaoSegundos\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("BARREIRA"))
                .andExpect(jsonPath("$.alvo").value(2.0010))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(corpo).get("id").asText();

        relogio.avancar(Duration.ofSeconds(5));
        chegaPreco("XRPUSDT", "2.0005");
        mockMvc.perform(get("/ordens/" + id).header("X-Usuario-Id", "aviador"))
                .andExpect(jsonPath("$.status").value("ABERTA"));

        chegaPreco("XRPUSDT", "2.0011");
        mockMvc.perform(get("/ordens/" + id).header("X-Usuario-Id", "aviador"))
                .andExpect(jsonPath("$.status").value("PERDEU"))
                .andExpect(jsonPath("$.precoSaida").value(2.0011))
                .andExpect(jsonPath("$.valorLiquido").value(-10.00));

        // sem alvo e com alvo colado no preco
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "aviador").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"XRPUSDT\",\"tipo\":\"BARREIRA\",\"valor\":10,\"duracaoSegundos\":30}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "aviador").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"XRPUSDT\",\"tipo\":\"BARREIRA\",\"alvo\":2.00110001,\"valor\":10,"
                                + "\"duracaoSegundos\":30}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("colado")));
    }

    @Test
    void parametrosDaBarreira() throws Exception {
        mockMvc.perform(get("/ordens/barreira").param("simbolos", "btcusdt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.margem").value(0.95))
                .andExpect(jsonPath("$.multiplicadorMaximo").value(20.00))
                .andExpect(jsonPath("$.distanciaMinima").value(0.00001))
                .andExpect(jsonPath("$.volatilidadePorSegundo.BTCUSDT").isNumber());
    }

    @Test
    void regrasDoJogo() throws Exception {
        mockMvc.perform(get("/ordens/regras"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modos", hasSize(3)))
                .andExpect(jsonPath("$.modos[0].modo").value("FACIL"))
                .andExpect(jsonPath("$.modos[0].perdaPercentual").value(50))
                .andExpect(jsonPath("$.modos[2].multiplicadorLateral").value(6.00))
                .andExpect(jsonPath("$.modos[2].movimentoMinimoPercentual").value(0.02))
                .andExpect(jsonPath("$.duracoesSegundos", hasSize(4)))
                .andExpect(jsonPath("$.valorMaximo").value(10000.00));
    }

    private static void assertEvento(OrdemExecutadaEvento e) {
        org.assertj.core.api.Assertions.assertThat(e.usuarioId()).isEqualTo("jogador-1");
        org.assertj.core.api.Assertions.assertThat(e.valorLiquido()).isEqualByComparingTo("90.00");
        org.assertj.core.api.Assertions.assertThat(e.precoSaida()).isEqualByComparingTo("65010.00");
    }

    @Test
    void erros() throws Exception {
        when(walletClient.saldo(anyString())).thenReturn(new BigDecimal("50.00"));
        chegaPreco("ETHUSDT", "3000");

        // sem usuario
        mockMvc.perform(get("/ordens")).andExpect(status().isUnauthorized());
        // corpo invalido
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "j2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"\",\"valor\":-1}"))
                .andExpect(status().isBadRequest());
        // duracao fora das permitidas
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "j2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"ETHUSDT\",\"tipo\":\"BAIXA\",\"valor\":10,\"duracaoSegundos\":7}"))
                .andExpect(status().isUnprocessableEntity());
        // saldo insuficiente
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "j2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"ETHUSDT\",\"tipo\":\"BAIXA\",\"valor\":60,\"duracaoSegundos\":30}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 50.00"));
        // wallet fora do ar
        when(walletClient.saldo("j3")).thenThrow(new ServicoIndisponivelException("wallet fora"));
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "j3").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"ETHUSDT\",\"tipo\":\"LATERAL\",\"valor\":10,\"duracaoSegundos\":30}"))
                .andExpect(status().isServiceUnavailable());
        // ativo sem cotacao
        when(marketDataClient.precoAtual(any())).thenReturn(Optional.empty());
        mockMvc.perform(post("/ordens").header("X-Usuario-Id", "j2").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"simbolo\":\"DOGEUSDT\",\"tipo\":\"ALTA\",\"valor\":10,\"duracaoSegundos\":30}"))
                .andExpect(status().isServiceUnavailable());
    }
}
