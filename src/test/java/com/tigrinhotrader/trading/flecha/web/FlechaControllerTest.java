package com.tigrinhotrader.trading.flecha.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Fluxos do Flecha Certeira pela API com Spring + JPA (H2 proprio), carteira e fila simuladas. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:flecha;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class FlechaControllerTest {

    /** Relogio que o teste consegue adiantar. */
    static class RelogioAjustavel extends Clock {
        private Instant agora = Instant.parse("2026-10-01T12:00:00Z");

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

    /** O que o wallet-service le do evento (mesmos campos e tipos do OrdemExecutadaEvento de la). */
    record EventoNaCarteira(UUID ordemId, String usuarioId, String simbolo, String tipo, BigDecimal valor,
                            String resultado, BigDecimal valorLiquido, Instant executadaEm) {
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RelogioAjustavel relogio;
    @Autowired
    private SessaoFlechaService service;

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private WalletClient walletClient;
    @MockBean
    private MarketDataClient marketDataClient;

    @BeforeEach
    void saldoPadrao() {
        when(walletClient.saldo(anyString())).thenReturn(new BigDecimal("1000.00"));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String usuario, String corpo) {
        return req.header("X-Usuario-Id", usuario).contentType(MediaType.APPLICATION_JSON).content(corpo);
    }

    private ResultActions abrir(String usuario, String corpo) throws Exception {
        return mockMvc.perform(json(post("/jogos/flecha/sessoes"), usuario, corpo));
    }

    private String abrirId(String usuario, String corpo) throws Exception {
        String resposta = abrir(usuario, corpo).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(resposta).get("id").asText();
    }

    private ResultActions atirar(String usuario, String id, String corpo) throws Exception {
        return mockMvc.perform(json(post("/jogos/flecha/sessoes/" + id + "/tiros"), usuario, corpo));
    }

    private ResultActions comprar(String usuario, String id, String corpo) throws Exception {
        return mockMvc.perform(json(post("/jogos/flecha/sessoes/" + id + "/flechas"), usuario, corpo));
    }

    private ResultActions encerrar(String usuario, String id) throws Exception {
        return mockMvc.perform(post("/jogos/flecha/sessoes/" + id + "/encerrar").header("X-Usuario-Id", usuario));
    }

    private EventoNaCarteira eventoPublicado() throws Exception {
        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq("tigrinho.eventos"), eq("ordem.executada"), evento.capture());
        // ida e volta pelo JSON, como a carteira recebe
        return objectMapper.readValue(objectMapper.writeValueAsString(evento.getValue()), EventoNaCarteira.class);
    }

    @Test
    void regrasSaoPublicas() throws Exception {
        mockMvc.perform(get("/jogos/flecha/regras"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campos", hasSize(4)))
                .andExpect(jsonPath("$.campos[0].slug").value("vilarejo"))
                .andExpect(jsonPath("$.campos[0].nome").value("Campo do Vilarejo"))
                .andExpect(jsonPath("$.campos[0].entrada").value(5))
                .andExpect(jsonPath("$.campos[0].flechaBase").value(1))
                .andExpect(jsonPath("$.campos[0].mult").value(2))
                .andExpect(jsonPath("$.campos[0].pool.blue").value(0))
                .andExpect(jsonPath("$.campos[0].pool.gold").value(0.3))
                .andExpect(jsonPath("$.campos[3].slug").value("reserva-real"))
                .andExpect(jsonPath("$.campos[3].pool.gold").value(1.2))
                .andExpect(jsonPath("$.passaros.red").value(2))
                .andExpect(jsonPath("$.passaros.gold").value(15))
                .andExpect(jsonPath("$.flechas", hasSize(4)))
                .andExpect(jsonPath("$.flechas[1].id").value("iron"))
                .andExpect(jsonPath("$.flechas[1].nome").value("Ferro"))
                .andExpect(jsonPath("$.flechas[1].fatorPreco").value(2))
                .andExpect(jsonPath("$.flechas[1].fatorMoedas").value(2.5))
                .andExpect(jsonPath("$.maxFlechas").value(40))
                .andExpect(jsonPath("$.maxCombo").value(5));
    }

    @Test
    void partidaCompletaComGanhoVaiParaACarteira() throws Exception {
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "arqueiro"))
                .andExpect(status().isNoContent());

        Instant inicio = relogio.instant();
        // vilarejo: entrada 5 + 10 madeira (10) + 5 ferro (10) = 25
        String id = objectMapper.readTree(abrir("arqueiro", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":10,\"iron\":5}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campo").value("vilarejo"))
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.custo").value(25.0))
                .andExpect(jsonPath("$.ganhos").value(0.0))
                .andExpect(jsonPath("$.resultado").value(-25.0))
                .andExpect(jsonPath("$.flechas.wood").value(10))
                .andExpect(jsonPath("$.flechas.iron").value(5))
                .andExpect(jsonPath("$.flechas.gold").value(0))
                .andExpect(jsonPath("$.compradas.iron").value(5))
                .andExpect(jsonPath("$.disparadas.wood").value(0))
                .andExpect(jsonPath("$.acertos").value(0))
                .andExpect(jsonPath("$.criadaEm").value(inicio.toString()))
                .andExpect(jsonPath("$.encerradaEm").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "arqueiro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // ferro: red (10) + red no 2o do combo (20)
        atirar("arqueiro", id, "{\"flecha\":\"iron\",\"acertos\":[\"red\",\"red\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(30))
                .andExpect(jsonPath("$.sessao.ganhos").value(30.0))
                .andExpect(jsonPath("$.sessao.flechas.iron").value(4))
                .andExpect(jsonPath("$.sessao.disparadas.iron").value(1))
                .andExpect(jsonPath("$.sessao.acertos").value(2));

        // rapido demais
        relogio.avancar(Duration.ofMillis(149));
        atirar("arqueiro", id, "{\"flecha\":\"wood\",\"acertos\":[]}")
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("150 ms")));
        relogio.avancar(Duration.ofMillis(1));
        atirar("arqueiro", id, "{\"flecha\":\"wood\",\"acertos\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(0))
                .andExpect(jsonPath("$.sessao.flechas.wood").value(9));

        // compra 1 de ferro (2): custo 27
        comprar("arqueiro", id, "{\"flecha\":\"iron\",\"quantidade\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(27.0))
                .andExpect(jsonPath("$.compradas.iron").value(6))
                .andExpect(jsonPath("$.flechas.iron").value(5));

        relogio.avancar(Duration.ofSeconds(5));
        encerrar("arqueiro", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCERRADA"))
                .andExpect(jsonPath("$.resultado").value(3.0))
                .andExpect(jsonPath("$.encerradaEm").value(relogio.instant().toString()));

        EventoNaCarteira evento = eventoPublicado();
        assertThat(evento.ordemId()).isEqualTo(UUID.fromString(id));
        assertThat(evento.usuarioId()).isEqualTo("arqueiro");
        assertThat(evento.simbolo()).isEqualTo("vilarejo");
        assertThat(evento.tipo()).isEqualTo("FLECHA_CERTEIRA");
        assertThat(evento.valor()).isEqualByComparingTo("27");
        assertThat(evento.resultado()).isEqualTo("GANHOU");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("3");
        assertThat(evento.executadaEm()).isEqualTo(inicio.plusMillis(5150));

        // encerrar de novo so devolve, sem publicar outra vez
        encerrar("arqueiro", id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ENCERRADA"));
        verify(rabbitTemplate, times(1)).convertAndSend(eq("tigrinho.eventos"), eq("ordem.executada"),
                org.mockito.ArgumentMatchers.<Object>any());

        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "arqueiro"))
                .andExpect(status().isNoContent());
        atirar("arqueiro", id, "{\"flecha\":\"wood\"}").andExpect(status().isConflict());
        comprar("arqueiro", id, "{\"flecha\":\"wood\",\"quantidade\":1}").andExpect(status().isConflict());

        // depois de encerrar pode abrir outra
        abrir("arqueiro", "{\"campo\":\"pico\",\"flechas\":{\"wood\":1}}").andExpect(status().isCreated());
    }

    @Test
    void partidaPerdidaPublicaPrejuizo() throws Exception {
        String id = abrirId("perdedor", "{\"campo\":\"reserva-real\",\"flechas\":{\"diamond\":1}}");
        atirar("perdedor", id, "{\"flecha\":\"diamond\",\"acertos\":[]}").andExpect(status().isOk());
        encerrar("perdedor", id).andExpect(jsonPath("$.custo").value(260.0));

        EventoNaCarteira evento = eventoPublicado();
        assertThat(evento.resultado()).isEqualTo("PERDEU");
        assertThat(evento.simbolo()).isEqualTo("reserva-real");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("-260");
    }

    @Test
    void semUsuarioDa401() throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(post("/jogos/flecha/sessoes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":1}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Cabecalho X-Usuario-Id ausente"));
        mockMvc.perform(get("/jogos/flecha/sessoes/atual")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/jogos/flecha/sessoes/" + id + "/tiros").contentType(MediaType.APPLICATION_JSON)
                .content("{\"flecha\":\"wood\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/jogos/flecha/sessoes/" + id + "/flechas").contentType(MediaType.APPLICATION_JSON)
                .content("{\"flecha\":\"wood\",\"quantidade\":1}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/jogos/flecha/sessoes/" + id + "/encerrar")).andExpect(status().isUnauthorized());
    }

    @Test
    void validacoesAoAbrir() throws Exception {
        abrir("v1", "{\"flechas\":{\"wood\":1}}").andExpect(status().isBadRequest());
        abrir("v1", "{\"campo\":\"vilarejo\"}").andExpect(status().isBadRequest());
        abrir("v1", "{\"campo\":\"lua\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Campo desconhecido: lua"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"laser\":1}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Flecha desconhecida: laser"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":-1,\"iron\":2}}")
                .andExpect(status().isUnprocessableEntity());
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{}}").andExpect(status().isUnprocessableEntity());
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":30,\"gold\":11}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Escolha de 1 a 40 flechas"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":30,\"gold\":10}}").andExpect(status().isCreated());
        // uma sessao aberta por jogador
        abrir("v1", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void saldoInsuficienteOuCarteiraForaDoAr() throws Exception {
        when(walletClient.saldo("pobre")).thenReturn(new BigDecimal("20.00"));
        // reserva real: 100 + 20 = 120
        abrir("pobre", "{\"campo\":\"reserva-real\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 20.00"));
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "pobre"))
                .andExpect(status().isNoContent());

        when(walletClient.saldo("sem-carteira")).thenThrow(new ServicoIndisponivelException("wallet fora"));
        abrir("sem-carteira", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void validacoesDoTiro() throws Exception {
        String id = abrirId("t1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":3}}");

        atirar("t1", id, "{\"acertos\":[]}").andExpect(status().isBadRequest());
        atirar("t1", id, "{\"flecha\":\"laser\"}").andExpect(status().isUnprocessableEntity());
        atirar("t1", id, "{\"flecha\":\"diamond\",\"acertos\":[\"red\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas de Diamante na aljava"));
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"red\",\"red\",\"red\",\"red\",\"red\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Uma flecha acerta no maximo 5 passaros"));
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"blue\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("blue")));
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"pinguim\"]}")
                .andExpect(status().isUnprocessableEntity());
        // combo maximo: 4 + 8 + 12 + 16 + 20
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"red\",\"red\",\"red\",\"red\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(60))
                .andExpect(jsonPath("$.sessao.acertos").value(5));

        // sessao de outro jogador ou inexistente
        atirar("intruso", id, "{\"flecha\":\"wood\"}").andExpect(status().isNotFound());
        atirar("t1", UUID.randomUUID().toString(), "{\"flecha\":\"wood\"}").andExpect(status().isNotFound());

        // acabam as flechas
        relogio.avancar(Duration.ofSeconds(1));
        atirar("t1", id, "{\"flecha\":\"wood\"}").andExpect(status().isOk());
        relogio.avancar(Duration.ofSeconds(1));
        atirar("t1", id, "{\"flecha\":\"wood\"}").andExpect(status().isOk());
        relogio.avancar(Duration.ofSeconds(1));
        atirar("t1", id, "{\"flecha\":\"wood\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void validacoesDaCompra() throws Exception {
        when(walletClient.saldo("c1")).thenReturn(new BigDecimal("100.00"));
        // bosque: 15 + 25 x 3 = 90
        String id = abrirId("c1", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":25}}");

        comprar("c1", id, "{\"flecha\":\"wood\"}").andExpect(status().isBadRequest());
        comprar("c1", id, "{\"flecha\":\"laser\",\"quantidade\":1}").andExpect(status().isUnprocessableEntity());
        comprar("c1", id, "{\"flecha\":\"wood\",\"quantidade\":0}").andExpect(status().isUnprocessableEntity());
        comprar("c1", id, "{\"flecha\":\"wood\",\"quantidade\":21}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Compre de 1 a 20 flechas por vez"));
        comprar("c1", id, "{\"flecha\":\"wood\",\"quantidade\":16}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("A aljava comporta no maximo 40 flechas"));
        // disponivel = 100 - 90 = 10: 4 de madeira (12) nao cabem, 3 (9) cabem
        comprar("c1", id, "{\"flecha\":\"wood\",\"quantidade\":4}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 10.00"));
        comprar("c1", id, "{\"flecha\":\"wood\",\"quantidade\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(99.0))
                .andExpect(jsonPath("$.flechas.wood").value(28));
        comprar("outro", id, "{\"flecha\":\"wood\",\"quantidade\":1}").andExpect(status().isNotFound());
        encerrar("outro", id).andExpect(status().isNotFound());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(),
                org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void ordensDeCriptoDescontamOCustoDasSessoesAbertas() throws Exception {
        when(walletClient.saldo("misto")).thenReturn(new BigDecimal("100.00"));
        when(marketDataClient.precoAtual("BTCUSDT")).thenReturn(Optional.of(new BigDecimal("65000")));
        // bosque: 15 + 10 x 3 = 45
        abrirId("misto", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":10}}");

        mockMvc.perform(json(post("/ordens"), "misto",
                        "{\"simbolo\":\"BTCUSDT\",\"tipo\":\"ALTA\",\"valor\":60,\"duracaoSegundos\":30}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 55.00"));
        mockMvc.perform(json(post("/ordens"), "misto",
                        "{\"simbolo\":\"BTCUSDT\",\"tipo\":\"ALTA\",\"valor\":50,\"duracaoSegundos\":30}"))
                .andExpect(status().isCreated());
        // e a ordem aberta (50) tambem conta para o Flecha: sobram 5
        String id = objectMapper.readTree(mockMvc.perform(get("/jogos/flecha/sessoes/atual")
                .header("X-Usuario-Id", "misto")).andReturn().getResponse().getContentAsString()).get("id").asText();
        comprar("misto", id, "{\"flecha\":\"wood\",\"quantidade\":2}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 5.00"));
    }

    @Test
    void sessoesParadasSaoEncerradasPeloAgendador() throws Exception {
        String parada = abrirId("dorminhoco", "{\"campo\":\"vilarejo\",\"flechas\":{\"gold\":1}}");
        atirar("dorminhoco", parada, "{\"flecha\":\"gold\",\"acertos\":[\"red\"]}").andExpect(status().isOk());

        relogio.avancar(Duration.ofMinutes(9));
        String ativa = abrirId("ativo", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":1}}");
        relogio.avancar(Duration.ofMinutes(1).plusSeconds(1));

        assertThat(service.encerrarOciosas()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "dorminhoco"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "ativo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ativa));
        encerrar("dorminhoco", parada)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCERRADA"))
                .andExpect(jsonPath("$.ganhos").value(22.0));

        ArgumentCaptor<Object> eventos = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(eq("tigrinho.eventos"), eq("ordem.executada"), eventos.capture());
        EventoNaCarteira evento = null;
        for (Object e : eventos.getAllValues()) {
            EventoNaCarteira lido = objectMapper.readValue(objectMapper.writeValueAsString(e), EventoNaCarteira.class);
            if (lido.ordemId().toString().equals(parada)) {
                evento = lido;
            }
        }
        assertThat(evento).isNotNull();
        // 5 + 4 = 9 de custo, 22 de ganho
        assertThat(evento.resultado()).isEqualTo("GANHOU");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("13");
        assertThat(evento.executadaEm()).isEqualTo(relogio.instant());
    }
}
