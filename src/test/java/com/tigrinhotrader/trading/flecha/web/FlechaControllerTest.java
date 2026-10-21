package com.tigrinhotrader.trading.flecha.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
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
import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.AljavaGuardada;
import com.tigrinhotrader.trading.flecha.dominio.AljavaGuardadaRepository;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
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

    private static final String REMOVIDAS = "Flechas de ferro, ouro e diamante foram removidas do jogo: use "
            + "madeira (gratis) ou as flechas de mercado";

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
    @Autowired
    private AljavaGuardadaRepository aljavasGuardadas;

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

    private ResultActions aljava(String usuario) throws Exception {
        return mockMvc.perform(get("/jogos/flecha/aljava").header("X-Usuario-Id", usuario));
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
                .andExpect(jsonPath("$.campos[3].entrada").value(100))
                .andExpect(jsonPath("$.campos[0].flechaBase").value(1))
                .andExpect(jsonPath("$.campos[0].mult").value(2))
                .andExpect(jsonPath("$.campos[0].pool.blue").value(0))
                .andExpect(jsonPath("$.campos[0].pool.gold").value(0.3))
                .andExpect(jsonPath("$.campos[3].slug").value("reserva-real"))
                .andExpect(jsonPath("$.campos[3].pool.gold").value(1.2))
                // quanto mais caro o campo, mais ativos para apostar
                .andExpect(jsonPath("$.campos[0].passarosMercado", contains("btc")))
                .andExpect(jsonPath("$.campos[1].passarosMercado", contains("btc", "eth")))
                .andExpect(jsonPath("$.campos[2].passarosMercado", contains("btc", "eth", "ouro", "v100")))
                .andExpect(jsonPath("$.campos[3].passarosMercado", contains("btc", "eth", "ouro", "v100")))
                .andExpect(jsonPath("$.campos[0].ativos", contains("BTCUSDT")))
                .andExpect(jsonPath("$.campos[1].ativos", contains("BTCUSDT", "ETHUSDT")))
                .andExpect(jsonPath("$.campos[2].ativos", contains("BTCUSDT", "ETHUSDT", "PAXGUSDT", "1HZ100V")))
                .andExpect(jsonPath("$.campos[3].ativos", contains("BTCUSDT", "ETHUSDT", "PAXGUSDT", "1HZ100V")))
                // passaros nao pagam moedas
                .andExpect(jsonPath("$.passaros.red").value(0))
                .andExpect(jsonPath("$.passaros.gold").value(0))
                .andExpect(jsonPath("$.passaros.btc").value(0))
                .andExpect(jsonPath("$.passaros.eth").value(0))
                .andExpect(jsonPath("$.passaros.ouro").value(0))
                .andExpect(jsonPath("$.passaros.v100").value(0))
                .andExpect(jsonPath("$.campos[0].pool.btc").doesNotExist())
                .andExpect(jsonPath("$.campos[0].pool.eth").doesNotExist())
                .andExpect(jsonPath("$.flechas", hasSize(5)))
                // so madeira (gratis) e as de mercado; ferro, ouro e diamante sairam do jogo
                .andExpect(jsonPath("$.flechas[*].id", contains("wood", "btc", "eth", "ouro", "v100")))
                .andExpect(jsonPath("$.flechas[0].id").value("wood"))
                .andExpect(jsonPath("$.flechas[0].nome").value("Madeira"))
                .andExpect(jsonPath("$.flechas[0].fatorPreco").value(0))
                .andExpect(jsonPath("$.flechas[0].fatorMoedas").value(1))
                .andExpect(jsonPath("$.flechas[0].precoFixo").isEmpty())
                .andExpect(jsonPath("$.flechas[0].passaroAlvo").isEmpty())
                .andExpect(jsonPath("$.flechas[0].vendida").doesNotExist())
                .andExpect(jsonPath("$.flechas[1].id").value("btc"))
                .andExpect(jsonPath("$.flechas[1].fatorPreco").value(1))
                .andExpect(jsonPath("$.flechas[1].fatorMoedas").value(2))
                .andExpect(jsonPath("$.flechas[1].passaroAlvo").value("btc"))
                // as de mercado custam o valor escolhido pelo jogador: sem preco fixo
                .andExpect(jsonPath("$.flechas[1].precoFixo").isEmpty())
                .andExpect(jsonPath("$.flechas[2].precoFixo").isEmpty())
                .andExpect(jsonPath("$.flechas[3].precoFixo").isEmpty())
                .andExpect(jsonPath("$.flechas[4].precoFixo").isEmpty())
                .andExpect(jsonPath("$.flechas[4].id").value("v100"))
                .andExpect(jsonPath("$.flechas[4].passaroAlvo").value("v100"))
                .andExpect(jsonPath("$.flechas[2].id").value("eth"))
                .andExpect(jsonPath("$.flechas[2].nome").value("Ethereum"))
                .andExpect(jsonPath("$.flechas[2].passaroAlvo").value("eth"))
                .andExpect(jsonPath("$.flechas[3].id").value("ouro"))
                .andExpect(jsonPath("$.flechas[3].nome").value("Ouro PAXG"))
                .andExpect(jsonPath("$.flechas[3].fatorMoedas").value(2))
                .andExpect(jsonPath("$.flechas[3].passaroAlvo").value("ouro"))
                .andExpect(jsonPath("$.maxFlechas").value(40))
                .andExpect(jsonPath("$.maxCombo").value(5))
                .andExpect(jsonPath("$.niveisFlecha", contains(10, 20, 30)))
                .andExpect(jsonPath("$.valorMinimoFlecha").value(1))
                .andExpect(jsonPath("$.taxaDevolucaoFlechas").value(0.002))
                .andExpect(content().string(containsString("\"valorMinimoFlecha\":1,")))
                .andExpect(content().string(containsString("\"taxaDevolucaoFlechas\":0.002")));
    }

    @Test
    void partidaCompletaDeTreinoSoCustaAEntrada() throws Exception {
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "arqueiro"))
                .andExpect(status().isNoContent());

        Instant inicio = relogio.instant();
        // vilarejo: entrada 5 + 10 madeira (gratis) = 5
        String id = objectMapper.readTree(abrir("arqueiro", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":10}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campo").value("vilarejo"))
                .andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.custo").value(5.0))
                .andExpect(jsonPath("$.ganhos").value(0.0))
                .andExpect(jsonPath("$.resultado").value(-5.0))
                .andExpect(jsonPath("$.flechas.wood").value(10))
                .andExpect(jsonPath("$.flechas.btc").value(0))
                .andExpect(jsonPath("$.flechas.iron").doesNotExist())
                .andExpect(jsonPath("$.compradas.gold").doesNotExist())
                .andExpect(jsonPath("$.compradas.wood").value(10))
                .andExpect(jsonPath("$.disparadas.wood").value(0))
                .andExpect(jsonPath("$.mercado", hasSize(0)))
                .andExpect(jsonPath("$.devolucaoFlechas.quantidade").value(0))
                .andExpect(jsonPath("$.devolucaoFlechas.valorDevolvido").value(0))
                .andExpect(jsonPath("$.acertos").value(0))
                .andExpect(jsonPath("$.criadaEm").value(inicio.toString()))
                .andExpect(jsonPath("$.encerradaEm").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "arqueiro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        // combo de 2 passaros: conta os acertos, mas passaro nao paga moedas
        atirar("arqueiro", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"gold\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(0))
                .andExpect(jsonPath("$.sessao.ganhos").value(0.0))
                .andExpect(jsonPath("$.sessao.flechas.wood").value(9))
                .andExpect(jsonPath("$.sessao.disparadas.wood").value(1))
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
                .andExpect(jsonPath("$.sessao.flechas.wood").value(8));

        // mais madeira no meio da sessao: gratis, o custo continua so a entrada
        comprar("arqueiro", id, "{\"flecha\":\"wood\",\"quantidade\":20}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(5.0))
                .andExpect(jsonPath("$.compradas.wood").value(30))
                .andExpect(jsonPath("$.flechas.wood").value(28));

        relogio.avancar(Duration.ofSeconds(5));
        encerrar("arqueiro", id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENCERRADA"))
                .andExpect(jsonPath("$.resultado").value(-5.0))
                .andExpect(jsonPath("$.encerradaEm").value(relogio.instant().toString()));

        EventoNaCarteira evento = eventoPublicado();
        assertThat(evento.ordemId()).isEqualTo(UUID.fromString(id));
        assertThat(evento.usuarioId()).isEqualTo("arqueiro");
        assertThat(evento.simbolo()).isEqualTo("vilarejo");
        assertThat(evento.tipo()).isEqualTo("FLECHA_CERTEIRA");
        assertThat(evento.valor()).isEqualByComparingTo("5");
        assertThat(evento.resultado()).isEqualTo("PERDEU");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("-5");
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
        // reserva real: 100 + 1 madeira (gratis) + 1 bitcoin sem valor (cliente antigo: 10) que nao foi apostada
        String id = abrirId("perdedor", "{\"campo\":\"reserva-real\",\"flechas\":{\"wood\":1,\"btc\":1}}");
        atirar("perdedor", id, "{\"flecha\":\"wood\",\"acertos\":[\"gold\"]}").andExpect(status().isOk());
        // a bitcoin que sobrou volta: 10 - 0,2% = 9,98
        encerrar("perdedor", id).andExpect(jsonPath("$.custo").value(110.0))
                .andExpect(jsonPath("$.resultado").value(-100.02))
                .andExpect(jsonPath("$.devolucaoFlechas.valorDevolvido").value(9.98));

        EventoNaCarteira evento = eventoPublicado();
        assertThat(evento.resultado()).isEqualTo("PERDEU");
        assertThat(evento.simbolo()).isEqualTo("reserva-real");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("-100.02");
        // e nao fica guardada
        aljava("perdedor").andExpect(jsonPath("$.mercado.btc").value(0));
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
        mockMvc.perform(get("/jogos/flecha/aljava")).andExpect(status().isUnauthorized());
    }

    @Test
    void aljavaVaziaTemTodoCampoETodoTipo() throws Exception {
        aljava("sem-nada").andExpect(status().isOk())
                .andExpect(jsonPath("$.campos.vilarejo.wood").value(0))
                .andExpect(jsonPath("$.campos.vilarejo.iron").doesNotExist())
                .andExpect(jsonPath("$.campos.vilarejo.gold").doesNotExist())
                .andExpect(jsonPath("$.campos.vilarejo.diamond").doesNotExist())
                .andExpect(jsonPath("$.campos.vilarejo.btc").doesNotExist())
                .andExpect(jsonPath("$.campos.bosque.wood").value(0))
                .andExpect(jsonPath("$.campos.pico.wood").value(0))
                .andExpect(jsonPath("$.campos['reserva-real'].wood").value(0))
                .andExpect(jsonPath("$.mercado.btc").value(0))
                .andExpect(jsonPath("$.mercado.eth").value(0))
                .andExpect(jsonPath("$.mercado.ouro").value(0))
                .andExpect(jsonPath("$.mercado.wood").doesNotExist());
    }

    @Test
    void sobrasComunsFicamGuardadasNoCampoEAsDeMercadoVoltamEmMoedas() throws Exception {
        // vilarejo: 5 + 3 madeira (gratis) + 1 bitcoin sem valor (10) = 15
        String vilarejo = abrirId("guarda", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":3,\"btc\":1}}");
        atirar("guarda", vilarejo, "{\"flecha\":\"wood\",\"acertos\":[]}").andExpect(status().isOk());
        // a bitcoin volta com 0,2% a menos (9,98): resultado = ganhos + devolucao - custo
        encerrar("guarda", vilarejo).andExpect(status().isOk())
                .andExpect(jsonPath("$.resultado").value(-5.02))
                .andExpect(jsonPath("$.flechas.wood").value(0))
                .andExpect(jsonPath("$.flechas.btc").value(0))
                .andExpect(jsonPath("$.mercado", hasSize(0)));
        assertThat(eventoPublicado().valorLiquido()).isEqualByComparingTo("-5.02");

        aljava("guarda").andExpect(status().isOk())
                .andExpect(jsonPath("$.campos.vilarejo.wood").value(2))
                .andExpect(jsonPath("$.campos.bosque.wood").value(0))
                .andExpect(jsonPath("$.mercado.btc").value(0));

        // no bosque a madeira do vilarejo nao vale
        String bosque = objectMapper.readTree(abrir("guarda", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(15.0))
                .andExpect(jsonPath("$.flechas.wood").value(1))
                .andExpect(jsonPath("$.flechas.btc").value(0))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        aljava("guarda").andExpect(jsonPath("$.campos.vilarejo.wood").value(2));
        encerrar("guarda", bosque).andExpect(status().isOk());
        aljava("guarda").andExpect(jsonPath("$.campos.bosque.wood").value(1));

        // de volta ao vilarejo sem comprar nada: so a entrada, com as flechas guardadas
        String volta = objectMapper.readTree(abrir("guarda", "{\"campo\":\"vilarejo\",\"flechas\":{}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(5.0))
                .andExpect(jsonPath("$.flechas.wood").value(2))
                .andExpect(jsonPath("$.compradas.wood").value(0))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        aljava("guarda")
                .andExpect(jsonPath("$.campos.vilarejo.wood").value(0))
                .andExpect(jsonPath("$.campos.bosque.wood").value(1));
        atirar("guarda", volta, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"btc\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(0))
                .andExpect(jsonPath("$.sessao.acertos").value(2));
    }

    @Test
    void flechasAntigasGuardadasSomemDaAljavaESaoReembolsadas() throws Exception {
        // 1 ferro e 1 diamante comprados no bosque antes de sairem do jogo, mais 2 madeiras
        AljavaGuardada deAntes = new AljavaGuardada("reembolsado", "bosque", relogio.instant());
        deAntes.guardar(Aljava.antigas(1, 0, 1), relogio.instant());
        deAntes.guardar(Aljava.de(Map.of(TipoFlecha.WOOD, 2)), relogio.instant());
        aljavasGuardadas.save(deAntes);

        aljava("reembolsado").andExpect(jsonPath("$.campos.bosque.wood").value(2))
                .andExpect(jsonPath("$.campos.bosque.iron").doesNotExist())
                .andExpect(jsonPath("$.campos.bosque.diamond").doesNotExist());
        // abrir no bosque leva so a madeira; as antigas nao entram na sessao
        String id = objectMapper.readTree(abrir("reembolsado", "{\"campo\":\"bosque\",\"flechas\":{}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flechas.wood").value(2))
                .andExpect(jsonPath("$.flechas.iron").doesNotExist())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        atirar("reembolsado", id, "{\"flecha\":\"iron\",\"acertos\":[]}").andExpect(status().isBadRequest());

        // o agendador devolve o que custaram: bosque (3) x (2 + 8) = 30, uma vez so
        assertThat(service.reembolsarFlechasAntigas()).isGreaterThanOrEqualTo(1);
        assertThat(service.reembolsarFlechasAntigas()).isZero();
        assertThat(aljavasGuardadas.findById(deAntes.getId())).get()
                .satisfies(g -> assertThat(g.getFlechas().antigas()).isZero());
        EventoNaCarteira evento = eventoPublicado();
        assertThat(evento.usuarioId()).isEqualTo("reembolsado");
        assertThat(evento.tipo()).isEqualTo("FLECHA_REEMBOLSO");
        assertThat(evento.simbolo()).isEqualTo("bosque");
        assertThat(evento.resultado()).isEqualTo("GANHOU");
        assertThat(evento.valor()).isEqualByComparingTo("30");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("30");
        assertThat(evento.ordemId()).isNotEqualTo(deAntes.getId());
    }

    @Test
    void flechasDeMercadoGuardadasDoPrecoAntigoEntramComoLotesDeCemESaoDevolvidas() throws Exception {
        // ethereum guardada antes da mudanca (preco fixo de 100): entra na aljava do vilarejo como lote de 100, mas
        // nao pode ser apostada la (o vilarejo nao tem o ativo)
        AljavaGuardada deAntes = new AljavaGuardada("viajante", AljavaGuardada.MERCADO, relogio.instant());
        deAntes.guardar(Aljava.de(Map.of(TipoFlecha.ETH, 1)), relogio.instant());
        aljavasGuardadas.save(deAntes);
        aljava("viajante").andExpect(jsonPath("$.mercado.eth").value(1));

        String vilarejo = objectMapper.readTree(abrir("viajante", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(5.0))
                .andExpect(jsonPath("$.flechas.eth").value(1))
                .andExpect(jsonPath("$.compradas.eth").value(0))
                .andExpect(jsonPath("$.mercado", hasSize(1)))
                .andExpect(jsonPath("$.mercado[0].flecha").value("eth"))
                .andExpect(jsonPath("$.mercado[0].valor").value(100.0))
                .andExpect(jsonPath("$.mercado[0].quantidade").value(1))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        aljava("viajante").andExpect(jsonPath("$.mercado.eth").value(0));
        atirar("viajante", vilarejo, "{\"flecha\":\"wood\",\"acertos\":[\"eth\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Passaro eth nao aparece no Campo do Vilarejo"));
        // ao encerrar volta em moedas: 100 - 0,2% = 99,80, somado ao resultado (99,80 - 5)
        encerrar("viajante", vilarejo).andExpect(status().isOk())
                .andExpect(jsonPath("$.devolucaoFlechas.quantidade").value(1))
                .andExpect(jsonPath("$.devolucaoFlechas.valorBruto").value(100.0))
                .andExpect(jsonPath("$.devolucaoFlechas.taxa").value(0.2))
                .andExpect(jsonPath("$.devolucaoFlechas.valorDevolvido").value(99.8))
                .andExpect(jsonPath("$.resultado").value(94.8));
        assertThat(eventoPublicado().valorLiquido()).isEqualByComparingTo("94.80");
        aljava("viajante").andExpect(jsonPath("$.mercado.eth").value(0));
    }

    @Test
    void limiteDaAljavaContaAsGuardadas() throws Exception {
        String id = abrirId("cheio", "{\"campo\":\"pico\",\"flechas\":{\"wood\":30}}");
        encerrar("cheio", id).andExpect(status().isOk());

        abrir("cheio", "{\"campo\":\"pico\",\"flechas\":{\"wood\":11}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("A aljava comporta no maximo 40 flechas e voce ja tem 30 guardadas"));
        // no vilarejo as 30 do pico nao contam
        String vilarejo = abrirId("cheio", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":11}}");
        encerrar("cheio", vilarejo).andExpect(status().isOk());
        abrir("cheio", "{\"campo\":\"pico\",\"flechas\":{\"wood\":10}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flechas.wood").value(40))
                .andExpect(jsonPath("$.compradas.wood").value(10));
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
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":-1,\"btc\":2}}")
                .andExpect(status().isUnprocessableEntity());
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{}}").andExpect(status().isUnprocessableEntity());
        for (String flecha : new String[] {"iron", "gold", "diamond"}) {
            abrir("v1", "{\"campo\":\"reserva-real\",\"flechas\":{\"wood\":1,\"" + flecha + "\":1}}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(REMOVIDAS));
        }
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"eth\":1}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Flechas Ethereum nao sao vendidas no Campo do Vilarejo"));
        abrir("v1", "{\"campo\":\"bosque\",\"flechas\":{\"ouro\":1}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Flechas Ouro PAXG nao sao vendidas no Bosque dos Arqueiros"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":30,\"btc\":11}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Escolha de 1 a 40 flechas"));
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "v1"))
                .andExpect(status().isNoContent());
        // lotes de mercado: valor obrigatorio, de 1 moeda pra cima, 2 casas; elemento nulo e 400
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{},\"mercado\":[{\"flecha\":\"btc\",\"quantidade\":1}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Informe o valor das flechas Bitcoin"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{},\"mercado\":[{\"flecha\":\"btc\",\"valor\":0.5,"
                + "\"quantidade\":1}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("pelo menos 1 moeda")));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{},\"mercado\":[{\"flecha\":\"btc\",\"valor\":1.001,"
                + "\"quantidade\":1}]}")
                .andExpect(status().isUnprocessableEntity());
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{},\"mercado\":[{\"flecha\":\"eth\",\"valor\":10,"
                + "\"quantidade\":1}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Flechas Ethereum nao sao vendidas no Campo do Vilarejo"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":30},\"mercado\":[{\"flecha\":\"btc\","
                + "\"valor\":10,\"quantidade\":11}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Escolha de 1 a 40 flechas"));
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{},\"mercado\":[null]}").andExpect(status().isBadRequest());
        // com 0 de ferro no pedido (o frontend manda todos os tipos) abre normalmente; a bitcoin sem valor vale 10
        abrir("v1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":39,\"btc\":1,\"iron\":0,\"gold\":0,\"diamond\":0}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(15.0));
        // uma sessao aberta por jogador
        abrir("v1", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void saldoInsuficienteOuCarteiraForaDoAr() throws Exception {
        when(walletClient.saldo("pobre")).thenReturn(new BigDecimal("20.00"));
        // reserva real: a entrada (100) continua cobrada, mesmo so com madeira
        abrir("pobre", "{\"campo\":\"reserva-real\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 20.00"));
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "pobre"))
                .andExpect(status().isNoContent());
        // no bosque (15) da para treinar
        abrir("pobre", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":40}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(15.0));

        when(walletClient.saldo("sem-carteira")).thenThrow(new ServicoIndisponivelException("wallet fora"));
        abrir("sem-carteira", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":1}}")
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void flechasDeMercadoCustamOValorEscolhidoENaoPassamPelosTiros() throws Exception {
        // pico: entrada 40 + 1 bitcoin, 1 ethereum, 1 de ouro PAXG e 1 V100 sem valor (cliente antigo: 10 cada)
        String id = objectMapper.readTree(abrir("fmerc",
                        "{\"campo\":\"pico\",\"flechas\":{\"btc\":1,\"eth\":1,\"ouro\":1,\"v100\":1}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(80.0))
                .andExpect(jsonPath("$.mercado", hasSize(4)))
                .andExpect(jsonPath("$.mercado[*].flecha", contains("btc", "eth", "ouro", "v100")))
                .andExpect(jsonPath("$.mercado[3].valor").value(10.0))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        for (String flecha : new String[] {"btc", "eth", "ouro", "v100"}) {
            atirar("fmerc", id, "{\"flecha\":\"" + flecha + "\",\"acertos\":[\"" + flecha + "\"]}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.detail").value("Flechas de mercado sao registradas pelo estudo"));
        }
        // comprar no meio da sessao pede o valor de cada flecha (sem teto: so o saldo limita)
        comprar("fmerc", id, "{\"flecha\":\"btc\",\"quantidade\":2}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Informe o valor das flechas Bitcoin"));
        comprar("fmerc", id, "{\"flecha\":\"btc\",\"quantidade\":2,\"valor\":100}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(280.0))
                .andExpect(jsonPath("$.flechas.btc").value(3));
        String real = abrirId("fmerc-real", "{\"campo\":\"reserva-real\",\"flechas\":{\"eth\":1}}");
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "fmerc-real"))
                .andExpect(jsonPath("$.id").value(real))
                .andExpect(jsonPath("$.custo").value(110.0));
    }

    @Test
    void passarosNaoPagamMoedasEOsDeMercadoSoVoamNosCamposComOAtivo() throws Exception {
        String id = abrirId("btc1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":2}}");
        atirar("btc1", id, "{\"flecha\":\"wood\",\"acertos\":[\"btc\",\"red\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(0))
                .andExpect(jsonPath("$.sessao.ganhos").value(0.0))
                .andExpect(jsonPath("$.sessao.acertos").value(2));
        atirar("btc1", id, "{\"flecha\":\"wood\",\"acertos\":[\"ouro\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Passaro ouro nao aparece no Campo do Vilarejo"));

        // no pico tem os tres
        String pico = abrirId("btc2", "{\"campo\":\"pico\",\"flechas\":{\"wood\":1}}");
        atirar("btc2", pico, "{\"flecha\":\"wood\",\"acertos\":[\"btc\",\"eth\",\"ouro\",\"gold\",\"blue\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(0))
                .andExpect(jsonPath("$.sessao.acertos").value(5));
    }

    @Test
    void validacoesDoTiro() throws Exception {
        String id = abrirId("t1", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":3}}");

        atirar("t1", id, "{\"acertos\":[]}").andExpect(status().isBadRequest());
        atirar("t1", id, "{\"flecha\":\"laser\"}").andExpect(status().isUnprocessableEntity());
        atirar("t1", id, "{\"flecha\":\"diamond\",\"acertos\":[\"red\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(REMOVIDAS));
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"red\",\"red\",\"red\",\"red\",\"red\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Uma flecha acerta no maximo 5 passaros"));
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"blue\"]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("blue")));
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"pinguim\"]}")
                .andExpect(status().isUnprocessableEntity());
        // combo maximo: 5 acertos, 0 moedas
        atirar("t1", id, "{\"flecha\":\"wood\",\"acertos\":[\"red\",\"red\",\"red\",\"red\",\"red\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ganhosTiro").value(0))
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
        // bosque: so a entrada 15
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
        for (String flecha : new String[] {"iron", "gold", "diamond"}) {
            comprar("c1", id, "{\"flecha\":\"" + flecha + "\",\"quantidade\":1}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(REMOVIDAS));
        }
        comprar("c1", id, "{\"flecha\":\"ouro\",\"quantidade\":1}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Flechas Ouro PAXG nao sao vendidas no Bosque dos Arqueiros"));
        // disponivel = 100 - 15 = 85: uma ethereum de 90 nao cabe
        comprar("c1", id, "{\"flecha\":\"eth\",\"quantidade\":1,\"valor\":90}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 85.00"));
        // madeira e gratis: nem precisa de saldo
        comprar("c1", id, "{\"flecha\":\"wood\",\"quantidade\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(15.0))
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
        // bosque: 15 + 10 madeira (gratis) = 15
        abrirId("misto", "{\"campo\":\"bosque\",\"flechas\":{\"wood\":10}}");

        mockMvc.perform(json(post("/ordens"), "misto",
                        "{\"simbolo\":\"BTCUSDT\",\"tipo\":\"ALTA\",\"valor\":90,\"duracaoSegundos\":30}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 85.00"));
        mockMvc.perform(json(post("/ordens"), "misto",
                        "{\"simbolo\":\"BTCUSDT\",\"tipo\":\"ALTA\",\"valor\":80,\"duracaoSegundos\":30}"))
                .andExpect(status().isCreated());
        // e a ordem aberta (80) tambem conta para o Flecha: sobram 5
        String id = objectMapper.readTree(mockMvc.perform(get("/jogos/flecha/sessoes/atual")
                .header("X-Usuario-Id", "misto")).andReturn().getResponse().getContentAsString()).get("id").asText();
        comprar("misto", id, "{\"flecha\":\"btc\",\"quantidade\":1,\"valor\":10}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente: disponivel 5.00"));
    }

    @Test
    void sessoesParadasSaoEncerradasPeloAgendador() throws Exception {
        // vilarejo: 5 + 2 madeira (gratis) + 1 bitcoin sem valor (10) = 15
        String parada = abrirId("dorminhoco", "{\"campo\":\"vilarejo\",\"flechas\":{\"wood\":2,\"btc\":1}}");
        atirar("dorminhoco", parada, "{\"flecha\":\"wood\",\"acertos\":[\"red\"]}").andExpect(status().isOk());

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
                .andExpect(jsonPath("$.ganhos").value(0.0))
                .andExpect(jsonPath("$.devolucaoFlechas.valorDevolvido").value(9.98))
                .andExpect(jsonPath("$.acertos").value(1));

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
        assertThat(evento.resultado()).isEqualTo("PERDEU");
        // a bitcoin que sobrou voltou: 9,98 - 15
        assertThat(evento.valorLiquido()).isEqualByComparingTo("-5.02");
        assertThat(evento.executadaEm()).isEqualTo(relogio.instant());
        // a madeira que sobrou ficou guardada; a bitcoin nao
        aljava("dorminhoco")
                .andExpect(jsonPath("$.campos.vilarejo.wood").value(1))
                .andExpect(jsonPath("$.mercado.btc").value(0));
    }

    @Test
    void contratoDosLotesDeMercadoComValorEscolhido() throws Exception {
        // bosque: 15 + 3 bitcoin de 10 + 1 ethereum de 25,50 = 70,50 (madeira gratis)
        String corpo = "{\"campo\":\"bosque\",\"flechas\":{\"wood\":10},\"mercado\":[{\"flecha\":\"btc\",\"valor\":10,"
                + "\"quantidade\":3},{\"flecha\":\"eth\",\"valor\":25.5,\"quantidade\":1}]}";
        String id = objectMapper.readTree(abrir("lotes", corpo)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custo").value(70.5))
                .andExpect(jsonPath("$.flechas.wood").value(10))
                .andExpect(jsonPath("$.flechas.btc").value(3))
                .andExpect(jsonPath("$.flechas.eth").value(1))
                .andExpect(jsonPath("$.compradas.btc").value(3))
                .andExpect(jsonPath("$.mercado", hasSize(2)))
                .andExpect(content().string(containsString("\"mercado\":[{\"flecha\":\"btc\",\"valor\":10.00,"
                        + "\"quantidade\":3},{\"flecha\":\"eth\",\"valor\":25.50,\"quantidade\":1}]")))
                .andExpect(content().string(containsString("\"devolucaoFlechas\":{\"quantidade\":0,"
                        + "\"valorBruto\":0.00,\"taxa\":0.00,\"valorDevolvido\":0.00}")))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        // compra no meio: lote novo de 20; na madeira o valor e ignorado
        comprar("lotes", id, "{\"flecha\":\"btc\",\"quantidade\":2,\"valor\":20}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(110.5))
                .andExpect(jsonPath("$.flechas.btc").value(5))
                .andExpect(jsonPath("$.mercado[*].flecha", contains("btc", "btc", "eth")))
                .andExpect(jsonPath("$.mercado[*].valor", contains(10.0, 20.0, 25.5)))
                .andExpect(jsonPath("$.mercado[*].quantidade", contains(3, 2, 1)));
        comprar("lotes", id, "{\"flecha\":\"wood\",\"quantidade\":1,\"valor\":7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custo").value(110.5));
        mockMvc.perform(get("/jogos/flecha/sessoes/atual").header("X-Usuario-Id", "lotes"))
                .andExpect(jsonPath("$.mercado", hasSize(3)));

        // ao encerrar: 30 + 40 + 25,50 = 95,50 menos 0,2% = 95,31 (95,309 HALF_UP); resultado 95,31 - 110,50
        encerrar("lotes", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.mercado", hasSize(0)))
                .andExpect(jsonPath("$.flechas.btc").value(0))
                .andExpect(jsonPath("$.compradas.btc").value(5))
                .andExpect(jsonPath("$.resultado").value(-15.19))
                .andExpect(content().string(containsString("\"devolucaoFlechas\":{\"quantidade\":6,"
                        + "\"valorBruto\":95.50,\"taxa\":0.19,\"valorDevolvido\":95.31}")));
        EventoNaCarteira evento = eventoPublicado();
        assertThat(evento.valor()).isEqualByComparingTo("110.50");
        assertThat(evento.valorLiquido()).isEqualByComparingTo("-15.19");
        assertThat(evento.resultado()).isEqualTo("PERDEU");
        // nada de mercado vai para a guarda; a madeira sim
        aljava("lotes").andExpect(jsonPath("$.mercado.btc").value(0))
                .andExpect(jsonPath("$.mercado.eth").value(0))
                .andExpect(jsonPath("$.campos.bosque.wood").value(11));
        // a devolucao fica registrada na sessao (encerrar de novo so devolve)
        encerrar("lotes", id).andExpect(jsonPath("$.devolucaoFlechas.valorDevolvido").value(95.31));
    }
}
