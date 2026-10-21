package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService.PedidoLote;
import com.tigrinhotrader.trading.mensageria.SessaoFlechaEncerradaEvento;
import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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

/** O estudo pela API: Spring + JPA (H2 proprio), corretora falsa e agendador controlado pelo teste. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:estudo;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class EstudoApiTest {

    static class Relogio extends Clock {
        private Instant agora = Instant.parse("2026-10-12T15:00:00Z");

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

    /** Roda "executar" na hora; as tarefas agendadas ficam numa fila que o teste dispara. */
    static class AgendadorManual implements Agendador {
        final List<Runnable> agendadas = new ArrayList<>();

        @Override
        public void executar(Runnable tarefa) {
            tarefa.run();
        }

        @Override
        public void agendar(Runnable tarefa, Instant quando) {
            agendadas.add(tarefa);
        }

        void rodarAgendadas() {
            List<Runnable> agora = new ArrayList<>(agendadas);
            agendadas.clear();
            agora.forEach(Runnable::run);
        }
    }

    /** Corretora programavel: cada abrir/fechar consome a proxima resposta da fila. */
    static class CorretoraFalsa implements Corretora {
        ModoEstudo modo = ModoEstudo.SIMULADO;
        final Deque<Supplier<Execucao>> aberturas = new ArrayDeque<>();
        final Deque<Supplier<Execucao>> fechamentos = new ArrayDeque<>();
        final List<BigDecimal> nocionais = new ArrayList<>();
        final List<String> ids = new ArrayList<>();

        @Override
        public ModoEstudo modo() {
            return modo;
        }

        @Override
        public Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
            nocionais.add(nocionalUsdt);
            ids.add(idCliente);
            return aberturas.pop().get();
        }

        @Override
        public Execucao fechar(Direcao direcao, Execucao abertura, String idCliente) {
            ids.add(idCliente);
            return fechamentos.pop().get();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        Relogio relogioEstudo() {
            return new Relogio();
        }

        @Bean
        @Primary
        AgendadorManual agendadorManual() {
            return new AgendadorManual();
        }

        @Bean
        @Primary
        CorretoraFalsa corretoraFalsa() {
            return new CorretoraFalsa();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private Relogio relogio;
    @Autowired
    private AgendadorManual agendador;
    @Autowired
    private CorretoraFalsa corretora;
    @Autowired
    private SessaoFlechaService sessoes;
    @Autowired
    private PrecoCache precos;
    @Autowired
    private OperacaoEstudoRepository operacoes;
    @Autowired
    private OperacaoEstudoService estudo;
    @Autowired
    private AljavaGuardadaRepository aljavasGuardadas;

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private WalletClient walletClient;
    @MockBean
    private MarketDataClient marketDataClient;

    private static int contador;

    @BeforeEach
    void preparar() {
        // flechas de mercado custam o valor escolhido (10 moedas sem valor)
        when(walletClient.saldo(anyString())).thenReturn(new BigDecimal("100000.00"));
        corretora.modo = ModoEstudo.SIMULADO;
        corretora.aberturas.clear();
        corretora.fechamentos.clear();
        corretora.nocionais.clear();
        corretora.ids.clear();
        agendador.agendadas.clear();
        relogio.avancar(Duration.ofHours(1));
        // cambio fixo: o pagamento das apostas segue o resultado liquido em reais
        precos.atualizar(new PrecoAtualizadoEvento("USDTBRL", new BigDecimal("5.00"), null, relogio.instant()));
    }

    private static String novoJogador() {
        return "jogador-estudo-" + (++contador);
    }

    private UUID sessao(String jogador, int flechasBitcoin) {
        return sessoes.criar(jogador, "vilarejo", Map.of("wood", 1, "btc", flechasBitcoin)).getId();
    }

    private static Execucao exec(String preco, String fluxoUsdt, String fluxoBtc) {
        return new Execucao("o-" + preco, new BigDecimal("0.0001"), new BigDecimal(preco), new BigDecimal(fluxoUsdt),
                new BigDecimal(fluxoBtc), new BigDecimal("0.01"), BigDecimal.ZERO);
    }

    private ResultActions registrar(String jogador, UUID sessao, String direcao) throws Exception {
        return registrar(jogador, sessao, direcao, "BINARIA");
    }

    private ResultActions registrar(String jogador, UUID sessao, String direcao, String tipo) throws Exception {
        return mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sessaoId", sessao, "direcao", direcao, "tipo", tipo,
                        "precoAgora", 80000, "precoPrevisto", 80010))));
    }

    /** Previsao com o valor da flecha gasta (o lote da aljava). */
    private ResultActions registrar(String jogador, UUID sessao, String direcao, String tipo, BigDecimal valor)
            throws Exception {
        return mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sessaoId", sessao, "direcao", direcao, "tipo", tipo,
                        "precoAgora", 80000, "precoPrevisto", 80010, "valor", valor))));
    }

    private UUID idDe(ResultActions r) throws Exception {
        return UUID.fromString(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asText());
    }

    private ResultActions consultar(String jogador, UUID id) throws Exception {
        return mockMvc.perform(get("/jogos/flecha/estudo/operacoes/" + id).header("X-Usuario-Id", jogador));
    }

    private SessaoFlecha sessaoAtual(String jogador) {
        return sessoes.atual(jogador).orElseThrow();
    }

    @Test
    void flechaBitcoinAbreNaHoraFechaEm5sEOJogadorVeOResultado() throws Exception {
        precos.atualizar(new PrecoAtualizadoEvento("USDTBRL", new BigDecimal("5.00"), null, relogio.instant()));
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 2);
        corretora.aberturas.add(() -> exec("80000", "-8.01", "0.0001"));
        corretora.fechamentos.add(() -> exec("80400", "8.03", "-0.0001"));

        UUID id = idDe(registrar(jogador, sessao, "alta")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.simbolo").value("BTCUSDT"))
                .andExpect(jsonPath("$.direcao").value("ALTA"))
                .andExpect(jsonPath("$.tipo").value("BINARIA"))
                .andExpect(jsonPath("$.modo").value("SIMULADO"))
                // a flecha bitcoin sem valor e de 10 moedas: a operacao e de R$ 10
                .andExpect(jsonPath("$.nocionalBrl").value(10))
                .andExpect(jsonPath("$.precoPrevistoJogo").value(80010))
                .andExpect(jsonPath("$.moedasPagas").isEmpty())
                // a flecha saiu da aljava da sessao, na mesma transacao
                .andExpect(jsonPath("$.sessao.id").value(sessao.toString()))
                .andExpect(jsonPath("$.sessao.status").value("ABERTA"))
                .andExpect(jsonPath("$.sessao.flechas.btc").value(1))
                .andExpect(jsonPath("$.sessao.disparadas.btc").value(1))
                .andExpect(jsonPath("$.sessao.compradas.btc").value(2))
                .andExpect(jsonPath("$.sessao.ganhos").value(0.0)));

        // R$ 10 a R$ 5,00 por USDT = US$ 2
        assertThat(corretora.nocionais).singleElement().satisfies(n -> assertThat(n).isEqualByComparingTo("2"));
        assertThat(corretora.ids.get(0)).startsWith("est").endsWith("a").hasSizeLessThanOrEqualTo(36);
        consultar(jogador, id).andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.precoEntrada").value(80000))
                .andExpect(jsonPath("$.moedasPagas").isEmpty());

        agendador.rodarAgendadas();   // 5 s depois

        consultar(jogador, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.precoSaida").value(80400))
                .andExpect(jsonPath("$.acertouDirecao").value(true))
                .andExpect(jsonPath("$.resultadoBrutoBrl").value(0.2))     // 0,0001 x 400 x 5
                .andExpect(jsonPath("$.taxasBrl").value(0.1))
                .andExpect(jsonPath("$.resultadoLiquidoBrl").value(0.1))   // (8,03 - 8,01) x 5
                .andExpect(jsonPath("$.resultadoBinarioBrl").value(10))    // aposta binaria de R$ 10: acertou
                .andExpect(jsonPath("$.fechadaEm").exists())
                // acertou: a aposta de 10 volta com o resultado liquido (R$ 0,10), nos ganhos da sessao aberta
                .andExpect(jsonPath("$.moedasPagas").value(10.1))
                .andExpect(jsonPath("$.sessao.ganhos").value(10.1))
                .andExpect(jsonPath("$.sessao.acertos").value(1));

        // liquidar de novo nao paga outra vez
        estudo.liquidar(id);
        assertThat(sessaoAtual(jogador).getGanhos()).isEqualByComparingTo("10.10");
        assertThat(operacoes.findById(id)).get().extracting(OperacaoEstudo::getMoedasPagas)
                .satisfies(m -> assertThat(m).isEqualByComparingTo("10.10"));

        // outra pessoa nao ve a operacao; sem usuario e 401
        consultar(novoJogador(), id).andExpect(status().isNotFound());
        mockMvc.perform(get("/jogos/flecha/estudo/operacoes/" + id)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/jogos/flecha/estudo/resumo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modoAtual").value("SIMULADO"))
                .andExpect(jsonPath("$.nocionalBrl").value(30))
                .andExpect(jsonPath("$.apostaBinariaBrl").value(10))
                .andExpect(jsonPath("$.simulado.placarBinarioBrl").isNumber())
                .andExpect(jsonPath("$.simulado.operacoes").isNumber())
                .andExpect(jsonPath("$.simulado.taxaAcertoPercentual").isNumber())
                .andExpect(jsonPath("$.real.operacoes").isNumber())
                .andExpect(jsonPath("$.porAtivo[?(@.simbolo == 'BTCUSDT' && @.modo == 'SIMULADO')].numeros.operacoes")
                        .isNotEmpty());
    }

    private UUID sessaoDeMercado(String jogador) {
        // o pico tem os quatro ativos; as flechas sem valor sao de 10 moedas
        return sessoes.criar(jogador, "pico", Map.of("wood", 1, "btc", 1, "eth", 1, "ouro", 1)).getId();
    }

    private ResultActions registrarNoAtivo(String jogador, UUID sessao, String simbolo, String direcao)
            throws Exception {
        return mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sessaoId", sessao, "direcao", direcao, "tipo", "BINARIA",
                        "simbolo", simbolo))));
    }

    @Test
    void ethereumEOuroSaoSempreSimuladosMesmoComOBitcoinNoModoReal() throws Exception {
        corretora.modo = ModoEstudo.REAL;
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2500"), null, relogio.instant()));
        precos.atualizar(new PrecoAtualizadoEvento("PAXGUSDT", new BigDecimal("3300"), null, relogio.instant()));
        String jogador = novoJogador();
        UUID sessao = sessaoDeMercado(jogador);

        UUID eth = idDe(registrarNoAtivo(jogador, sessao, " ethusdt ", "ALTA")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.simbolo").value("ETHUSDT"))
                .andExpect(jsonPath("$.modo").value("SIMULADO"))
                .andExpect(jsonPath("$.sessao.flechas.eth").value(0))
                .andExpect(jsonPath("$.sessao.flechas.ouro").value(1)));
        consultar(jogador, eth).andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.precoEntrada").value(2500));
        relogio.avancar(Duration.ofSeconds(2));
        UUID ouro = idDe(registrarNoAtivo(jogador, sessao, "PAXGUSDT", "QUEDA")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.simbolo").value("PAXGUSDT"))
                .andExpect(jsonPath("$.modo").value("SIMULADO")));

        // 5 s depois: ETH subiu (acertou), ouro subiu (errou)
        relogio.avancar(Duration.ofSeconds(5));
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2510"), null, relogio.instant()));
        precos.atualizar(new PrecoAtualizadoEvento("PAXGUSDT", new BigDecimal("3310"), null, relogio.instant()));
        agendador.rodarAgendadas();

        consultar(jogador, eth).andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.precoSaida").value(2510))
                .andExpect(jsonPath("$.acertouDirecao").value(true))
                .andExpect(jsonPath("$.resultadoBinarioBrl").value(10))
                // R$ 10 = US$ 2 = 0,0008 ETH de 2500 a 2510 menos 0,1% por ponta: R$ 0,0200 -> 10 + 0,02
                .andExpect(jsonPath("$.nocionalBrl").value(10))
                .andExpect(jsonPath("$.resultadoLiquidoBrl").value(0.02))
                .andExpect(jsonPath("$.moedasPagas").value(10.02));
        consultar(jogador, ouro).andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.acertouDirecao").value(false))
                .andExpect(jsonPath("$.resultadoBinarioBrl").value(-10))
                // 0,00060606 PAXG vendido a 3300 e recomprado a 3310, com taxas: R$ -0,0503; errou a direcao:
                // perde a aposta toda
                .andExpect(jsonPath("$.resultadoLiquidoBrl").value(-0.0503))
                .andExpect(jsonPath("$.moedasPagas").value(0))
                .andExpect(jsonPath("$.sessao.ganhos").value(10.02))
                .andExpect(jsonPath("$.sessao.acertos").value(1));
        // a corretora configurada (REAL) nem foi chamada
        assertThat(corretora.ids).isEmpty();
        assertThat(operacoes.findById(eth)).get().extracting(OperacaoEstudo::getModo).isEqualTo(ModoEstudo.SIMULADO);

        mockMvc.perform(get("/jogos/flecha/estudo/resumo"))
                .andExpect(jsonPath("$.modoAtual").value("REAL"))
                .andExpect(jsonPath("$.porAtivo[?(@.simbolo == 'ETHUSDT' && @.modo == 'SIMULADO')].numeros.operacoes")
                        .isNotEmpty())
                .andExpect(jsonPath("$.porAtivo[?(@.simbolo == 'PAXGUSDT' && @.modo == 'SIMULADO')].numeros.acertos")
                        .isNotEmpty())
                .andExpect(jsonPath("$.porAtivo[?(@.simbolo == 'ETHUSDT' && @.modo == 'REAL')]").doesNotExist())
                .andExpect(jsonPath("$.porAtivo[0].numeros.taxaAcertoPercentual").isNumber())
                .andExpect(jsonPath("$.porAtivo[0].numeros.placarBinarioBrl").isNumber());
    }

    @Test
    void cadaOperacaoGastaUmaFlechaDoProprioAtivo() throws Exception {
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2500"), null, relogio.instant()));
        String jogador = novoJogador();
        UUID sessao = sessaoDeMercado(jogador);

        registrarNoAtivo(jogador, sessao, "DOGEUSDT", "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Ativo nao suportado: DOGEUSDT"));

        registrarNoAtivo(jogador, sessao, "ETHUSDT", "ALTA").andExpect(status().isCreated());
        relogio.avancar(Duration.ofSeconds(2));
        registrarNoAtivo(jogador, sessao, "ETHUSDT", "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Ethereum na aljava"));
        // a operacao de ETH nao gasta a flecha do bitcoin (sem simbolo = BTCUSDT)
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        registrar(jogador, sessao, "QUEDA").andExpect(status().isCreated())
                .andExpect(jsonPath("$.simbolo").value("BTCUSDT"))
                .andExpect(jsonPath("$.sessao.flechas.btc").value(0))
                .andExpect(jsonPath("$.sessao.flechas.eth").value(0))
                .andExpect(jsonPath("$.sessao.flechas.ouro").value(1))
                .andExpect(jsonPath("$.sessao.flechas.wood").value(1));
        relogio.avancar(Duration.ofSeconds(2));
        registrar(jogador, sessao, "QUEDA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Bitcoin na aljava"));
        assertThat(sessaoAtual(jogador).getRestantes().get(TipoFlecha.OURO)).isEqualTo(1);

        // sessao sem flecha de ouro PAXG, num campo que tem o ativo
        String outro = novoJogador();
        UUID semOuro = sessoes.criar(outro, "pico", Map.of("btc", 1)).getId();
        registrarNoAtivo(outro, semOuro, "PAXGUSDT", "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Ouro PAXG na aljava"));
    }

    @Test
    void naoApostaNumAtivoQueOCampoNaoTemEAFlechaEDevolvida() throws Exception {
        String jogador = novoJogador();
        // ethereum e ouro guardados antes da mudanca (preco fixo de 100): entram em qualquer campo como lotes de 100
        AljavaGuardada deAntes = new AljavaGuardada(jogador, AljavaGuardada.MERCADO, relogio.instant());
        deAntes.guardar(Aljava.de(Map.of(TipoFlecha.ETH, 1, TipoFlecha.OURO, 1)), relogio.instant());
        aljavasGuardadas.save(deAntes);
        // no vilarejo (so bitcoin) as flechas entram na aljava...
        UUID vilarejo = sessoes.criar(jogador, "vilarejo", Map.of("wood", 1)).getId();
        assertThat(sessaoAtual(jogador).getRestantes().get(TipoFlecha.ETH)).isEqualTo(1);

        // ...mas o passaro do ativo nao voa ali: recusa sem gastar a flecha nem abrir operacao
        registrarNoAtivo(jogador, vilarejo, "ETHUSDT", "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("O passaro Ethereum nao aparece no Campo do Vilarejo"));
        registrarNoAtivo(jogador, vilarejo, "PAXGUSDT", "QUEDA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("O passaro Ouro PAXG nao aparece no Campo do Vilarejo"));
        SessaoFlecha aberta = sessaoAtual(jogador);
        assertThat(aberta.getRestantes().get(TipoFlecha.ETH)).isEqualTo(1);
        assertThat(aberta.getRestantes().get(TipoFlecha.OURO)).isEqualTo(1);
        assertThat(aberta.getDisparadas().total()).isZero();
        assertThat(operacoes.findFirstByUsuarioIdOrderByCriadaEmDesc(jogador)).isEmpty();

        // ao encerrar voltam em moedas (2 x 100 menos 0,2%), nao para a guarda
        SessaoFlecha encerrada = sessoes.encerrar(jogador, vilarejo);
        assertThat(encerrada.getDevolucaoQuantidade()).isEqualTo(2);
        assertThat(encerrada.getDevolucaoValor()).isEqualByComparingTo("199.60");
        UUID bosque = sessoes.criar(jogador, "bosque", Map.of("wood", 1, "eth", 1)).getId();
        registrarNoAtivo(jogador, bosque, "PAXGUSDT", "QUEDA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("O passaro Ouro PAXG nao aparece no Bosque dos Arqueiros"));
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2500"), null, relogio.instant()));
        registrarNoAtivo(jogador, bosque, "ETHUSDT", "ALTA").andExpect(status().isCreated())
                .andExpect(jsonPath("$.simbolo").value("ETHUSDT"))
                .andExpect(jsonPath("$.sessao.flechas.eth").value(0))
                .andExpect(jsonPath("$.sessao.flechas.ouro").value(0));
    }

    @Test
    void validacoesDaPrevisao() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 2);

        registrar(jogador, sessao, "lateral").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Direcao deve ser ALTA ou QUEDA"));
        registrar(novoJogador(), sessao, "ALTA").andExpect(status().isNotFound());
        registrar(jogador, UUID.randomUUID(), "ALTA").andExpect(status().isNotFound());
        mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"direcao\":\"ALTA\"}"))
                .andExpect(status().isBadRequest());
        // as duas flechas bitcoin sao de 10: nao ha de 20
        registrar(jogador, sessao, "QUEDA", "BINARIA", new BigDecimal("20")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Bitcoin de 20 moedas na aljava"));
        // nada disso gastou flecha
        assertThat(sessaoAtual(jogador).getRestantes().get(TipoFlecha.BTC)).isEqualTo(2);

        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        // cliente antigo, sem o tipo, ou com qualquer tipo: a aposta e sempre binaria
        mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sessaoId", sessao, "direcao", "QUEDA"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("BINARIA"));
        relogio.avancar(Duration.ofSeconds(2));
        registrar(jogador, sessao, "ALTA", "PREVISAO").andExpect(status().isCreated())
                .andExpect(jsonPath("$.tipo").value("BINARIA"));
        // so comprou 2 flechas bitcoin
        relogio.avancar(Duration.ofSeconds(2));
        registrar(jogador, sessao, "QUEDA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Bitcoin na aljava"));

        // sem flecha bitcoin na sessao
        String outro = novoJogador();
        UUID semBitcoin = sessoes.criar(outro, "vilarejo", Map.of("wood", 2)).getId();
        registrar(outro, semBitcoin, "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Bitcoin na aljava"));

        // sessao encerrada
        String terceiro = novoJogador();
        UUID encerrada = sessao(terceiro, 2);
        sessoes.encerrar(terceiro, encerrada);
        registrar(terceiro, encerrada, "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sessao ja encerrada"));
    }

    @Test
    void umaOperacaoPorVezPorJogador() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 3);
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));

        registrar(jogador, sessao, "ALTA").andExpect(status().isCreated());
        registrar(jogador, sessao, "ALTA").andExpect(status().isTooManyRequests());
        // a recusada por ritmo nao gastou a flecha
        assertThat(sessaoAtual(jogador).getRestantes().get(TipoFlecha.BTC)).isEqualTo(2);
        relogio.avancar(Duration.ofMillis(1001));
        registrar(jogador, sessao, "ALTA").andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessao.flechas.btc").value(1));
    }

    @Test
    void naoAbrirOuFicarAbaixoDoMinimoDevolveAAposta() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 3);
        corretora.aberturas.add(() -> {
            throw new RegraNegocioException("Operacao de US$ 5,45 abaixo do minimo da Binance (US$ 10)");
        });
        corretora.aberturas.add(() -> {
            throw new IllegalStateException("Binance fora do ar");
        });

        UUID recusada = idDe(registrar(jogador, sessao, "ALTA"));
        consultar(jogador, recusada).andExpect(jsonPath("$.status").value("RECUSADA"))
                .andExpect(jsonPath("$.motivo").value("Operacao de US$ 5,45 abaixo do minimo da Binance (US$ 10)"))
                .andExpect(jsonPath("$.moedasPagas").value(10))
                .andExpect(jsonPath("$.sessao.ganhos").value(10.0));

        relogio.avancar(Duration.ofSeconds(2));
        UUID falhou = idDe(registrar(jogador, sessao, "ALTA"));
        consultar(jogador, falhou).andExpect(jsonPath("$.status").value("FALHOU"))
                .andExpect(jsonPath("$.motivo").value("Nao abriu: Binance fora do ar"))
                .andExpect(jsonPath("$.moedasPagas").value(10))
                .andExpect(jsonPath("$.sessao.ganhos").value(20.0))
                .andExpect(jsonPath("$.sessao.acertos").value(0));
        assertThat(agendador.agendadas).isEmpty();
    }

    @Test
    void fechamentoTentaDeNovoEDesisteDepoisDeTresFalhas() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 2);
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.fechamentos.add(() -> {
            throw new IllegalStateException("timeout");
        });
        corretora.fechamentos.add(() -> exec("79900", "7.98", "-0.0001"));
        UUID id = idDe(registrar(jogador, sessao, "ALTA"));

        agendador.rodarAgendadas();   // 1a tentativa falha e reagenda
        assertThat(agendador.agendadas).hasSize(1);
        consultar(jogador, id).andExpect(jsonPath("$.status").value("ABERTA"));
        agendador.rodarAgendadas();   // 2a fecha
        consultar(jogador, id).andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.acertouDirecao").value(false))
                .andExpect(jsonPath("$.resultadoBinarioBrl").value(-10))
                // previu alta e caiu: perde a aposta toda
                .andExpect(jsonPath("$.resultadoLiquidoBrl").value(-0.1))
                .andExpect(jsonPath("$.moedasPagas").value(0));
        assertThat(corretora.ids).anyMatch(i -> i.endsWith("f1"));

        // outra operacao que nunca fecha
        relogio.avancar(Duration.ofSeconds(2));
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        for (int i = 0; i < OperacaoEstudoService.MAX_TENTATIVAS_FECHAR; i++) {
            corretora.fechamentos.add(() -> {
                throw new IllegalStateException("Binance fora do ar");
            });
        }
        UUID presa = idDe(registrar(jogador, sessao, "QUEDA"));
        for (int i = 0; i < OperacaoEstudoService.MAX_TENTATIVAS_FECHAR; i++) {
            agendador.rodarAgendadas();
        }
        consultar(jogador, presa).andExpect(jsonPath("$.status").value("FALHOU"))
                .andExpect(jsonPath("$.motivo").value("Nao fechou: Binance fora do ar"))
                .andExpect(jsonPath("$.moedasPagas").value(10))
                .andExpect(jsonPath("$.sessao.ganhos").value(10.0));
        assertThat(agendador.agendadas).isEmpty();
        // agendamento atrasado de uma operacao ja terminada nao faz nada
        assertThat(operacoes.findById(presa)).get().extracting(OperacaoEstudo::getTentativasFechar)
                .isEqualTo(OperacaoEstudoService.MAX_TENTATIVAS_FECHAR);
    }

    @Test
    void modoRealRecusaQuandoUmaTravaEstaAtingida() throws Exception {
        corretora.modo = ModoEstudo.REAL;
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 10);
        // 5 abertas ao mesmo tempo (padrao): a 6a e recusada sem chamar a corretora
        for (int i = 0; i < 5; i++) {
            corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        }
        long abertasAntes = operacoes.countByModoAndStatusIn(ModoEstudo.REAL,
                List.of(StatusOperacao.ABRINDO, StatusOperacao.ABERTA));
        for (long i = abertasAntes; i < 5; i++) {
            registrar(jogador, sessao, "ALTA").andExpect(status().isCreated());
            relogio.avancar(Duration.ofSeconds(2));
        }
        UUID recusada = idDe(registrar(jogador, sessao, "ALTA").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RECUSADA"))
                .andExpect(jsonPath("$.modo").value("REAL"))
                .andExpect(jsonPath("$.motivo").value("Muitas operacoes abertas ao mesmo tempo"))
                // recusada na hora: a aposta ja volta na resposta, como numero com centavos
                .andExpect(jsonPath("$.moedasPagas").value(10))
                .andExpect(content().string(containsString("\"moedasPagas\":10.00")))
                .andExpect(jsonPath("$.sessao.ganhos").value(10.0)));
        assertThat(operacoes.findById(recusada)).get().extracting(OperacaoEstudo::getStatus)
                .isEqualTo(StatusOperacao.RECUSADA);

        // fecha tudo com prejuizo grande: a perda maxima do dia trava as proximas
        while (!agendador.agendadas.isEmpty()) {
            corretora.fechamentos.add(() -> exec("60000", "-20", "-0.0001"));
            List<Runnable> uma = List.of(agendador.agendadas.remove(0));
            uma.forEach(Runnable::run);
        }
        relogio.avancar(Duration.ofSeconds(2));
        registrar(jogador, sessao, "ALTA").andExpect(jsonPath("$.status").value("RECUSADA"))
                .andExpect(jsonPath("$.motivo").value("Perda maxima do dia atingida"))
                .andExpect(jsonPath("$.moedasPagas").value(10));

        mockMvc.perform(get("/jogos/flecha/estudo/resumo"))
                .andExpect(jsonPath("$.modoAtual").value("REAL"))
                .andExpect(jsonPath("$.real.acertos").value(0))
                .andExpect(jsonPath("$.real.taxaAcertoPercentual").value(0.0));
    }

    /** Eventos ordem.executada de pagamento de flecha de mercado publicados ate agora. */
    private List<SessaoFlechaEncerradaEvento> pagamentosPublicados() {
        ArgumentCaptor<Object> eventos = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate, atLeastOnce()).convertAndSend(anyString(), eq("ordem.executada"), eventos.capture());
        return eventos.getAllValues().stream()
                .filter(SessaoFlechaEncerradaEvento.class::isInstance)
                .map(SessaoFlechaEncerradaEvento.class::cast)
                .filter(e -> SessaoFlechaEncerradaEvento.TIPO_FLECHA_MERCADO.equals(e.tipo()))
                .toList();
    }

    @Test
    void apostaLiquidadaDepoisDeEncerrarVaiDiretoParaACarteiraUmaVezSo() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 4);
        // acerta com +R$ 0,30, empata (preco parado, sem taxa), acerta mas as taxas deixam -R$ 0,10 e erra (nao paga)
        for (int i = 0; i < 4; i++) {
            corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        }
        corretora.fechamentos.add(() -> exec("80400", "8.06", "-0.0001"));
        corretora.fechamentos.add(() -> exec("80000", "8", "-0.0001"));
        corretora.fechamentos.add(() -> exec("80010", "7.98", "-0.0001"));
        corretora.fechamentos.add(() -> exec("60000", "-20", "-0.0001"));
        UUID ganhou = idDe(registrar(jogador, sessao, "ALTA"));
        relogio.avancar(Duration.ofSeconds(2));
        UUID empatou = idDe(registrar(jogador, sessao, "QUEDA"));
        relogio.avancar(Duration.ofSeconds(2));
        UUID perdeu = idDe(registrar(jogador, sessao, "ALTA"));
        relogio.avancar(Duration.ofSeconds(2));
        UUID zerou = idDe(registrar(jogador, sessao, "ALTA"));

        // o jogador sai antes dos 5 s: a sessao vai para a carteira sem as apostas (e sem flecha para devolver)
        SessaoFlecha encerrada = sessoes.encerrar(jogador, sessao);
        assertThat(encerrada.getGanhos()).isEqualByComparingTo("0");
        assertThat(encerrada.getDevolucaoQuantidade()).isZero();
        agendador.rodarAgendadas();

        consultar(jogador, ganhou).andExpect(jsonPath("$.moedasPagas").value(10.3))
                .andExpect(jsonPath("$.sessao.status").value("ENCERRADA"))
                .andExpect(jsonPath("$.sessao.ganhos").value(0.0));
        consultar(jogador, empatou).andExpect(jsonPath("$.resultadoBinarioBrl").value(0))
                .andExpect(jsonPath("$.moedasPagas").value(10));
        consultar(jogador, perdeu).andExpect(jsonPath("$.acertouDirecao").value(true))
                .andExpect(jsonPath("$.moedasPagas").value(9.9));
        consultar(jogador, zerou).andExpect(jsonPath("$.acertouDirecao").value(false))
                .andExpect(jsonPath("$.moedasPagas").value(0));

        List<SessaoFlechaEncerradaEvento> pagamentos = pagamentosPublicados();
        // a que nao pagou nada nao gera evento
        assertThat(pagamentos).hasSize(3);
        OperacaoEstudo opGanhou = operacoes.findById(ganhou).orElseThrow();
        OperacaoEstudo opEmpatou = operacoes.findById(empatou).orElseThrow();
        OperacaoEstudo opPerdeu = operacoes.findById(perdeu).orElseThrow();
        SessaoFlechaEncerradaEvento pagGanhou = pagamentos.get(0);
        assertThat(pagGanhou.ordemId()).isEqualTo(opGanhou.referenciaPagamento()).isNotEqualTo(ganhou)
                .isNotEqualTo(sessao);
        assertThat(pagGanhou.usuarioId()).isEqualTo(jogador);
        assertThat(pagGanhou.simbolo()).isEqualTo("BTCUSDT");
        assertThat(pagGanhou.valor()).isEqualByComparingTo("10");
        assertThat(pagGanhou.resultado()).isEqualTo("GANHOU");
        assertThat(pagGanhou.valorLiquido()).isEqualByComparingTo("10.30");
        assertThat(pagGanhou.acertos()).isEqualTo(1);
        SessaoFlechaEncerradaEvento pagEmpatou = pagamentos.get(1);
        assertThat(pagEmpatou.ordemId()).isEqualTo(opEmpatou.referenciaPagamento())
                .isNotEqualTo(pagGanhou.ordemId());
        assertThat(pagEmpatou.resultado()).isEqualTo("EMPATOU");
        assertThat(pagEmpatou.valorLiquido()).isEqualByComparingTo("10");
        SessaoFlechaEncerradaEvento pagPerdeu = pagamentos.get(2);
        assertThat(pagPerdeu.ordemId()).isEqualTo(opPerdeu.referenciaPagamento());
        assertThat(pagPerdeu.resultado()).isEqualTo("PERDEU");
        assertThat(pagPerdeu.valorLiquido()).isEqualByComparingTo("9.90");
        assertThat(pagPerdeu.acertos()).isEqualTo(1);

        // liquidar de novo (revisao, agendamento repetido) nao publica outra vez
        estudo.liquidar(ganhou);
        estudo.liquidar(empatou);
        estudo.liquidar(perdeu);
        estudo.liquidar(zerou);
        assertThat(pagamentosPublicados()).hasSize(3);
    }

    @Test
    void moedasPagasSaiComoNumeroComCentavosNoJson() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 1);
        // o preco subiu (acertou a direcao), mas as taxas comeram o lucro: -0,012 US$ x 5 = -R$ 0,06
        corretora.aberturas.add(() -> exec("80000", "-8.01", "0.0001"));
        corretora.fechamentos.add(() -> exec("80010", "7.998", "-0.0001"));

        UUID id = idDe(registrar(jogador, sessao, "ALTA").andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"moedasPagas\":null"))));
        agendador.rodarAgendadas();

        consultar(jogador, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.acertouDirecao").value(true))
                .andExpect(jsonPath("$.resultadoLiquidoBrl").value(-0.06))
                .andExpect(jsonPath("$.moedasPagas").isNumber())
                .andExpect(jsonPath("$.moedasPagas").value(9.94))
                .andExpect(content().string(containsString("\"moedasPagas\":9.94")))
                // acerto na sessao continua sendo a direcao; os ganhos recebem os centavos
                .andExpect(jsonPath("$.sessao.acertos").value(1))
                .andExpect(jsonPath("$.sessao.ganhos").value(9.94));
        assertThat(operacoes.findById(id)).get().extracting(OperacaoEstudo::getMoedasPagas)
                .satisfies(m -> assertThat(m).isEqualByComparingTo("9.94").hasScaleOf(2));
        assertThat(sessaoAtual(jogador).getGanhos()).isEqualByComparingTo("9.94");
    }

    private ResultActions meusAcertos(String jogador, UUID sessao) throws Exception {
        MockHttpServletRequestBuilder pedido = get("/jogos/flecha/estudo/meus-acertos").header("X-Usuario-Id", jogador);
        return mockMvc.perform(sessao == null ? pedido : pedido.param("sessaoId", sessao.toString()));
    }

    @Test
    void meusAcertosContaSoAsFechadasDoJogadorPorAtivoEPorSessao() throws Exception {
        precos.atualizar(new PrecoAtualizadoEvento("USDTBRL", new BigDecimal("5.00"), null, relogio.instant()));
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2500"), null, relogio.instant()));
        String jogador = novoJogador();
        UUID primeira = sessao(jogador, 4);
        for (int i = 0; i < 3; i++) {
            corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        }
        corretora.aberturas.add(() -> {
            throw new RegraNegocioException("abaixo do minimo");
        });
        // acerta (+0,04 US$), empata com o preco parado (-0,01 de taxa) e erra (-0,10), a R$ 5,00
        corretora.fechamentos.add(() -> exec("80400", "8.04", "-0.0001"));
        corretora.fechamentos.add(() -> exec("80000", "7.99", "-0.0001"));
        corretora.fechamentos.add(() -> exec("79000", "7.9", "-0.0001"));
        for (int i = 0; i < 4; i++) {
            registrar(jogador, primeira, "ALTA").andExpect(status().isCreated());
            relogio.avancar(Duration.ofSeconds(2));
        }
        // ainda abertas: nao contam
        meusAcertos(jogador, null).andExpect(jsonPath("$.total.operacoes").value(0));
        agendador.rodarAgendadas();
        sessoes.encerrar(jogador, primeira);
        UUID segunda = sessoes.criar(jogador, "bosque", Map.of("eth", 1)).getId();
        registrarNoAtivo(jogador, segunda, "ETHUSDT", "ALTA").andExpect(status().isCreated());
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2510"), null, relogio.instant()));
        agendador.rodarAgendadas();

        // a recusada nao conta; o empate fica fora da taxa
        meusAcertos(jogador, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.porAtivo", hasSize(4)))
                .andExpect(jsonPath("$.porAtivo[0].simbolo").value("BTCUSDT"))
                .andExpect(jsonPath("$.porAtivo[0].operacoes").value(3))
                .andExpect(jsonPath("$.porAtivo[0].acertos").value(1))
                .andExpect(jsonPath("$.porAtivo[0].erros").value(1))
                .andExpect(jsonPath("$.porAtivo[0].empates").value(1))
                .andExpect(jsonPath("$.porAtivo[0].taxaAcertoPercentual").value(50.0))
                .andExpect(jsonPath("$.porAtivo[0].resultadoLiquidoBrl").value(-0.35))
                .andExpect(jsonPath("$.porAtivo[1].simbolo").value("ETHUSDT"))
                .andExpect(jsonPath("$.porAtivo[1].operacoes").value(1))
                .andExpect(jsonPath("$.porAtivo[1].acertos").value(1))
                .andExpect(jsonPath("$.porAtivo[1].taxaAcertoPercentual").value(100.0))
                // R$ 10: 0,0008 ETH de 2500 a 2510 menos 0,1% por ponta: 0,003992 US$ x 5
                .andExpect(jsonPath("$.porAtivo[1].resultadoLiquidoBrl").value(0.02))
                .andExpect(jsonPath("$.porAtivo[2].simbolo").value("PAXGUSDT"))
                .andExpect(jsonPath("$.porAtivo[2].operacoes").value(0))
                .andExpect(jsonPath("$.porAtivo[2].empates").value(0))
                .andExpect(jsonPath("$.porAtivo[2].taxaAcertoPercentual").isEmpty())
                .andExpect(jsonPath("$.porAtivo[2].resultadoLiquidoBrl").value(0))
                .andExpect(jsonPath("$.porAtivo[3].simbolo").value("1HZ100V"))
                .andExpect(jsonPath("$.porAtivo[3].operacoes").value(0))
                .andExpect(jsonPath("$.total.simbolo").doesNotExist())
                .andExpect(jsonPath("$.total.operacoes").value(4))
                .andExpect(jsonPath("$.total.acertos").value(2))
                .andExpect(jsonPath("$.total.erros").value(1))
                .andExpect(jsonPath("$.total.empates").value(1))
                .andExpect(jsonPath("$.total.taxaAcertoPercentual").value(66.7))
                .andExpect(jsonPath("$.total.resultadoLiquidoBrl").value(-0.33));

        meusAcertos(jogador, primeira)
                .andExpect(jsonPath("$.porAtivo[1].operacoes").value(0))
                .andExpect(jsonPath("$.total.operacoes").value(3))
                .andExpect(jsonPath("$.total.taxaAcertoPercentual").value(50.0))
                .andExpect(jsonPath("$.total.resultadoLiquidoBrl").value(-0.35));
        meusAcertos(jogador, segunda)
                .andExpect(jsonPath("$.porAtivo[0].operacoes").value(0))
                .andExpect(jsonPath("$.total.operacoes").value(1))
                .andExpect(jsonPath("$.total.acertos").value(1));
        // so as operacoes do proprio jogador: a sessao de outro da tudo zerado
        meusAcertos(novoJogador(), primeira)
                .andExpect(jsonPath("$.total.operacoes").value(0))
                .andExpect(jsonPath("$.total.taxaAcertoPercentual").isEmpty())
                .andExpect(jsonPath("$.total.resultadoLiquidoBrl").value(0));
        meusAcertos(novoJogador(), null).andExpect(jsonPath("$.porAtivo[0].operacoes").value(0));
        mockMvc.perform(get("/jogos/flecha/estudo/meus-acertos")).andExpect(status().isUnauthorized());
    }

    @Test
    void valorDaFlechaEAApostaEOTamanhoDaOperacao() throws Exception {
        String jogador = novoJogador();
        // vilarejo: 5 + 1 bitcoin de 10 + 2 de 20 = 55
        UUID sessao = sessoes.criar(jogador, "vilarejo", Map.of(), List.of(
                new PedidoLote("btc", BigDecimal.TEN, 1), new PedidoLote("btc", new BigDecimal("20"), 2))).getId();
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        // a de 20 acerta (+R$ 0,30); a de 10 empata (preco parado)
        corretora.fechamentos.add(() -> exec("80400", "8.06", "-0.0001"));
        corretora.fechamentos.add(() -> exec("80000", "8", "-0.0001"));

        UUID vinte = idDe(registrar(jogador, sessao, "ALTA", "BINARIA", new BigDecimal("20"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nocionalBrl").value(20))
                .andExpect(jsonPath("$.sessao.flechas.btc").value(2))
                .andExpect(jsonPath("$.sessao.mercado[*].valor", contains(10.0, 20.0)))
                .andExpect(jsonPath("$.sessao.mercado[*].quantidade", contains(1, 1))));
        // R$ 20 a R$ 5,00 por USDT = US$ 4
        assertThat(corretora.nocionais).singleElement().satisfies(n -> assertThat(n).isEqualByComparingTo("4"));
        assertThat(operacoes.findById(vinte)).get().extracting(OperacaoEstudo::getApostaMoedas)
                .satisfies(a -> assertThat(a).isEqualByComparingTo("20.00"));
        // sem valor (cliente antigo): gasta a de menor valor
        relogio.avancar(Duration.ofSeconds(2));
        UUID dez = idDe(registrar(jogador, sessao, "QUEDA")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nocionalBrl").value(10))
                .andExpect(jsonPath("$.sessao.mercado[*].valor", contains(20.0))));
        // nao tem mais flecha de 10: 422 sem gastar a de 20
        relogio.avancar(Duration.ofSeconds(2));
        registrar(jogador, sessao, "ALTA", "BINARIA", BigDecimal.TEN).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Bitcoin de 10 moedas na aljava"));
        assertThat(sessaoAtual(jogador).getRestantes().get(TipoFlecha.BTC)).isEqualTo(1);

        agendador.rodarAgendadas();
        // acertou: 20 + 0,30; empatou: devolve 10
        consultar(jogador, vinte).andExpect(jsonPath("$.moedasPagas").value(20.3));
        consultar(jogador, dez).andExpect(jsonPath("$.moedasPagas").value(10))
                .andExpect(jsonPath("$.sessao.ganhos").value(30.3));

        // a flecha de 20 que sobrou volta ao encerrar: 20 - 0,2% = 19,96; resultado 30,30 + 19,96 - 55
        SessaoFlecha encerrada = sessoes.encerrar(jogador, sessao);
        assertThat(encerrada.getDevolucaoValor()).isEqualByComparingTo("19.96");
        assertThat(encerrada.resultado()).isEqualByComparingTo("-4.74");
    }
}
