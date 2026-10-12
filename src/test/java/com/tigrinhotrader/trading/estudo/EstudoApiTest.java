package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService;
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

    @MockBean
    private RabbitTemplate rabbitTemplate;
    @MockBean
    private WalletClient walletClient;
    @MockBean
    private MarketDataClient marketDataClient;

    private static int contador;

    @BeforeEach
    void preparar() {
        when(walletClient.saldo(anyString())).thenReturn(new BigDecimal("1000.00"));
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
        return mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("sessaoId", sessao, "direcao", direcao,
                        "precoAgora", 80000, "precoPrevisto", 80010))));
    }

    private UUID idDe(ResultActions r) throws Exception {
        return UUID.fromString(json.readTree(r.andReturn().getResponse().getContentAsString()).get("id").asText());
    }

    private ResultActions consultar(String jogador, UUID id) throws Exception {
        return mockMvc.perform(get("/jogos/flecha/estudo/operacoes/" + id).header("X-Usuario-Id", jogador));
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
                .andExpect(jsonPath("$.direcao").value("ALTA"))
                .andExpect(jsonPath("$.modo").value("SIMULADO"))
                .andExpect(jsonPath("$.nocionalBrl").value(30))
                .andExpect(jsonPath("$.precoPrevistoJogo").value(80010)));

        // R$ 30 a R$ 5,00 por USDT = US$ 6
        assertThat(corretora.nocionais).singleElement().satisfies(n -> assertThat(n).isEqualByComparingTo("6"));
        assertThat(corretora.ids.get(0)).startsWith("est").endsWith("a").hasSizeLessThanOrEqualTo(36);
        consultar(jogador, id).andExpect(jsonPath("$.status").value("ABERTA"))
                .andExpect(jsonPath("$.precoEntrada").value(80000));

        agendador.rodarAgendadas();   // 5 s depois

        consultar(jogador, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FECHADA"))
                .andExpect(jsonPath("$.precoSaida").value(80400))
                .andExpect(jsonPath("$.acertouDirecao").value(true))
                .andExpect(jsonPath("$.resultadoBrutoBrl").value(0.2))     // 0,0001 x 400 x 5
                .andExpect(jsonPath("$.taxasBrl").value(0.1))
                .andExpect(jsonPath("$.resultadoLiquidoBrl").value(0.1))   // (8,03 - 8,01) x 5
                .andExpect(jsonPath("$.fechadaEm").exists());

        // outra pessoa nao ve a operacao; sem usuario e 401
        consultar(novoJogador(), id).andExpect(status().isNotFound());
        mockMvc.perform(get("/jogos/flecha/estudo/operacoes/" + id)).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/jogos/flecha/estudo/resumo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modoAtual").value("SIMULADO"))
                .andExpect(jsonPath("$.nocionalBrl").value(30))
                .andExpect(jsonPath("$.simulado.operacoes").isNumber())
                .andExpect(jsonPath("$.simulado.taxaAcertoPercentual").isNumber())
                .andExpect(jsonPath("$.real.operacoes").isNumber());
    }

    @Test
    void validacoesDaPrevisao() throws Exception {
        String jogador = novoJogador();
        UUID sessao = sessao(jogador, 1);

        registrar(jogador, sessao, "lateral").andExpect(status().isUnprocessableEntity());
        registrar(novoJogador(), sessao, "ALTA").andExpect(status().isNotFound());
        registrar(jogador, UUID.randomUUID(), "ALTA").andExpect(status().isNotFound());
        mockMvc.perform(post("/jogos/flecha/estudo/operacoes").header("X-Usuario-Id", jogador)
                .contentType(MediaType.APPLICATION_JSON).content("{\"direcao\":\"ALTA\"}"))
                .andExpect(status().isBadRequest());

        corretora.aberturas.add(() -> exec("80000", "-8", "0.0001"));
        registrar(jogador, sessao, "QUEDA").andExpect(status().isCreated());
        // so comprou 1 flecha bitcoin
        relogio.avancar(Duration.ofSeconds(2));
        registrar(jogador, sessao, "QUEDA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Cada flecha bitcoin comprada vale uma operacao"));

        // sem flecha bitcoin na sessao
        String outro = novoJogador();
        UUID semBitcoin = sessoes.criar(outro, "vilarejo", Map.of("wood", 2)).getId();
        registrar(outro, semBitcoin, "ALTA").andExpect(status().isUnprocessableEntity());

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
        relogio.avancar(Duration.ofMillis(1001));
        registrar(jogador, sessao, "ALTA").andExpect(status().isCreated());
    }

    @Test
    void naoAbrirOuFicarAbaixoDoMinimoAparecemParaOJogador() throws Exception {
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
                .andExpect(jsonPath("$.motivo").value("Operacao de US$ 5,45 abaixo do minimo da Binance (US$ 10)"));

        relogio.avancar(Duration.ofSeconds(2));
        UUID falhou = idDe(registrar(jogador, sessao, "ALTA"));
        consultar(jogador, falhou).andExpect(jsonPath("$.status").value("FALHOU"))
                .andExpect(jsonPath("$.motivo").value("Nao abriu: Binance fora do ar"));
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
                .andExpect(jsonPath("$.acertouDirecao").value(false));
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
                .andExpect(jsonPath("$.motivo").value("Nao fechou: Binance fora do ar"));
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
                .andExpect(jsonPath("$.motivo").value("Muitas operacoes abertas ao mesmo tempo")));
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
                .andExpect(jsonPath("$.motivo").value("Perda maxima do dia atingida"));

        mockMvc.perform(get("/jogos/flecha/estudo/resumo"))
                .andExpect(jsonPath("$.modoAtual").value("REAL"))
                .andExpect(jsonPath("$.real.acertos").value(0))
                .andExpect(jsonPath("$.real.taxaAcertoPercentual").value(0.0));
    }
}
