package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService;
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

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private WalletClient walletClient;
    @MockBean
    private MarketDataClient marketDataClient;

    private static int contador;

    @BeforeEach
    void preparar() {
        // flechas de mercado custam 100 moedas cada
        when(walletClient.saldo(anyString())).thenReturn(new BigDecimal("100000.00"));
        corretora.modo = ModoEstudo.SIMULADO;
        corretora.aberturas.clear();
        corretora.fechamentos.clear();
        corretora.nocionais.clear();
        corretora.ids.clear();
        agendador.agendadas.clear();
        relogio.avancar(Duration.ofHours(1));
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
                .andExpect(jsonPath("$.nocionalBrl").value(30))
                .andExpect(jsonPath("$.precoPrevistoJogo").value(80010))
                .andExpect(jsonPath("$.moedasPagas").isEmpty())
                // a flecha saiu da aljava da sessao, na mesma transacao
                .andExpect(jsonPath("$.sessao.id").value(sessao.toString()))
                .andExpect(jsonPath("$.sessao.status").value("ABERTA"))
                .andExpect(jsonPath("$.sessao.flechas.btc").value(1))
                .andExpect(jsonPath("$.sessao.disparadas.btc").value(1))
                .andExpect(jsonPath("$.sessao.compradas.btc").value(2))
                .andExpect(jsonPath("$.sessao.ganhos").value(0.0)));

        // R$ 30 a R$ 5,00 por USDT = US$ 6
        assertThat(corretora.nocionais).singleElement().satisfies(n -> assertThat(n).isEqualByComparingTo("6"));
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
                // acertou: a aposta de 100 volta com mais 100, nos ganhos da sessao aberta
                .andExpect(jsonPath("$.moedasPagas").value(200))
                .andExpect(jsonPath("$.sessao.ganhos").value(200.0))
                .andExpect(jsonPath("$.sessao.acertos").value(1));

        // liquidar de novo nao paga outra vez
        estudo.liquidar(id);
        assertThat(sessaoAtual(jogador).getGanhos()).isEqualByComparingTo("200");
        assertThat(operacoes.findById(id)).get().extracting(OperacaoEstudo::getMoedasPagas).isEqualTo(200);

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
        return sessoes.criar(jogador, "vilarejo", Map.of("wood", 1, "btc", 1, "eth", 1, "ouro", 1)).getId();
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
                .andExpect(jsonPath("$.moedasPagas").value(200));
        consultar(jogador, ouro).andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.acertouDirecao").value(false))
                .andExpect(jsonPath("$.resultadoBinarioBrl").value(-10))
                .andExpect(jsonPath("$.moedasPagas").value(0))
                .andExpect(jsonPath("$.sessao.ganhos").value(200.0))
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

        // sessao sem flecha de ouro PAXG
        String outro = novoJogador();
        UUID semOuro = sessao(outro, 1);
        registrarNoAtivo(outro, semOuro, "PAXGUSDT", "ALTA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Sem flechas Ouro PAXG na aljava"));
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
                .andExpect(jsonPath("$.moedasPagas").value(100))
                .andExpect(jsonPath("$.sessao.ganhos").value(100.0));

        relogio.avancar(Duration.ofSeconds(2));
        UUID falhou = idDe(registrar(jogador, sessao, "ALTA"));
        consultar(jogador, falhou).andExpect(jsonPath("$.status").value("FALHOU"))
                .andExpect(jsonPath("$.motivo").value("Nao abriu: Binance fora do ar"))
                .andExpect(jsonPath("$.moedasPagas").value(100))
                .andExpect(jsonPath("$.sessao.ganhos").value(200.0))
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
                .andExpect(jsonPath("$.moedasPagas").value(100))
                .andExpect(jsonPath("$.sessao.ganhos").value(100.0));
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
                // recusada na hora: a aposta ja volta na resposta
                .andExpect(jsonPath("$.moedasPagas").value(100))
                .andExpect(jsonPath("$.sessao.ganhos").value(100.0)));
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
                .andExpect(jsonPath("$.moedasPagas").value(100));

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
        UUID sessao = sessao(jogador, 3);
        // acerta, empata (preco parado) e erra
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        corretora.fechamentos.add(() -> exec("80400", "8", "-0.0001"));
        corretora.fechamentos.add(() -> exec("80000", "8", "-0.0001"));
        corretora.fechamentos.add(() -> exec("79000", "7.9", "-0.0001"));
        UUID ganhou = idDe(registrar(jogador, sessao, "ALTA"));
        relogio.avancar(Duration.ofSeconds(2));
        UUID empatou = idDe(registrar(jogador, sessao, "QUEDA"));
        relogio.avancar(Duration.ofSeconds(2));
        UUID perdeu = idDe(registrar(jogador, sessao, "ALTA"));

        // o jogador sai antes dos 5 s: a sessao vai para a carteira sem as apostas
        SessaoFlecha encerrada = sessoes.encerrar(jogador, sessao);
        assertThat(encerrada.getGanhos()).isEqualByComparingTo("0");
        agendador.rodarAgendadas();

        consultar(jogador, ganhou).andExpect(jsonPath("$.moedasPagas").value(200))
                .andExpect(jsonPath("$.sessao.status").value("ENCERRADA"))
                .andExpect(jsonPath("$.sessao.ganhos").value(0.0));
        consultar(jogador, empatou).andExpect(jsonPath("$.resultadoBinarioBrl").value(0))
                .andExpect(jsonPath("$.moedasPagas").value(100));
        consultar(jogador, perdeu).andExpect(jsonPath("$.moedasPagas").value(0));

        List<SessaoFlechaEncerradaEvento> pagamentos = pagamentosPublicados();
        // errou nao paga nada: nenhum evento
        assertThat(pagamentos).hasSize(2);
        OperacaoEstudo opGanhou = operacoes.findById(ganhou).orElseThrow();
        OperacaoEstudo opEmpatou = operacoes.findById(empatou).orElseThrow();
        SessaoFlechaEncerradaEvento pagGanhou = pagamentos.get(0);
        assertThat(pagGanhou.ordemId()).isEqualTo(opGanhou.referenciaPagamento()).isNotEqualTo(ganhou)
                .isNotEqualTo(sessao);
        assertThat(pagGanhou.usuarioId()).isEqualTo(jogador);
        assertThat(pagGanhou.simbolo()).isEqualTo("BTCUSDT");
        assertThat(pagGanhou.valor()).isEqualByComparingTo("100");
        assertThat(pagGanhou.resultado()).isEqualTo("GANHOU");
        assertThat(pagGanhou.valorLiquido()).isEqualByComparingTo("200");
        assertThat(pagGanhou.acertos()).isEqualTo(1);
        SessaoFlechaEncerradaEvento pagEmpatou = pagamentos.get(1);
        assertThat(pagEmpatou.ordemId()).isEqualTo(opEmpatou.referenciaPagamento())
                .isNotEqualTo(pagGanhou.ordemId());
        assertThat(pagEmpatou.resultado()).isEqualTo("EMPATOU");
        assertThat(pagEmpatou.valorLiquido()).isEqualByComparingTo("100");

        // liquidar de novo (revisao, agendamento repetido) nao publica outra vez
        estudo.liquidar(ganhou);
        estudo.liquidar(empatou);
        estudo.liquidar(perdeu);
        assertThat(pagamentosPublicados()).hasSize(2);
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
        UUID segunda = sessoes.criar(jogador, "vilarejo", Map.of("eth", 1)).getId();
        registrarNoAtivo(jogador, segunda, "ETHUSDT", "ALTA").andExpect(status().isCreated());
        precos.atualizar(new PrecoAtualizadoEvento("ETHUSDT", new BigDecimal("2510"), null, relogio.instant()));
        agendador.rodarAgendadas();

        // a recusada nao conta; o empate fica fora da taxa
        meusAcertos(jogador, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.porAtivo", hasSize(3)))
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
                // 0,0024 ETH de 2500 a 2510 menos 0,1% por ponta: 0,011976 US$ x 5
                .andExpect(jsonPath("$.porAtivo[1].resultadoLiquidoBrl").value(0.0599))
                .andExpect(jsonPath("$.porAtivo[2].simbolo").value("PAXGUSDT"))
                .andExpect(jsonPath("$.porAtivo[2].operacoes").value(0))
                .andExpect(jsonPath("$.porAtivo[2].empates").value(0))
                .andExpect(jsonPath("$.porAtivo[2].taxaAcertoPercentual").isEmpty())
                .andExpect(jsonPath("$.porAtivo[2].resultadoLiquidoBrl").value(0))
                .andExpect(jsonPath("$.total.simbolo").doesNotExist())
                .andExpect(jsonPath("$.total.operacoes").value(4))
                .andExpect(jsonPath("$.total.acertos").value(2))
                .andExpect(jsonPath("$.total.erros").value(1))
                .andExpect(jsonPath("$.total.empates").value(1))
                .andExpect(jsonPath("$.total.taxaAcertoPercentual").value(66.7))
                .andExpect(jsonPath("$.total.resultadoLiquidoBrl").value(-0.2901));

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
}
