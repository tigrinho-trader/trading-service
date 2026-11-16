package com.tigrinhotrader.trading.estudo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Adaptador da opcao binaria CALL/PUT da Deriv: 5 ticks na Volatilidade 100 (1s) e 5 minutos no ouro e na prata
 * ({@link #para(AtivoEstudo)}; o contrato de cada ativo esta em {@link AtivoEstudo#contratoDeriv()}).
 * <p>
 * Uma conexao WebSocket autenticada fica aberta (com ping) e serve todas as flechas: abrir custa so a proposta e a
 * compra, sem buscar a conta, pedir OTP e conectar a cada flecha (isso levava ~2 s, e o contrato comecava bem
 * depois da flecha sair). Logo depois da compra o contrato e acompanhado na mesma conexao: a abertura espera o tick
 * de entrada (o preco real em que o contrato comeca) e o fechamento espera a liquidacao, com o tick de saida.
 * Esses ticks vao para a operacao e o jogo desenha o pássaro por eles.
 */
@Component
public class CorretoraDerivOptions implements Corretora {
    private static final Logger log = LoggerFactory.getLogger(CorretoraDerivOptions.class);
    private static final Duration ESPERA = Duration.ofSeconds(20);
    /** Quanto a abertura espera o tick de entrada depois da compra (chega ~1 s depois, no tick seguinte). */
    private static final Duration ESPERA_ENTRADA = Duration.ofSeconds(4);
    /** A Deriv fecha conexoes paradas; um ping a cada 30 s mantem a conexao viva. */
    private static final Duration PING = Duration.ofSeconds(30);

    private final DerivTradingProperties propriedades;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger requisicao = new AtomicInteger(100);
    private final RoteadorDeriv roteador = new RoteadorDeriv();
    private final Object conectando = new Object();
    private final Object enviando = new Object();
    private final ScheduledExecutorService pings = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "deriv-ping");
        t.setDaemon(true);
        return t;
    });
    private volatile WebSocket conexao;
    private volatile String contaEmCache;

    public CorretoraDerivOptions(DerivTradingProperties propriedades) {
        this.propriedades = propriedades;
        pings.scheduleWithFixedDelay(this::pingar, PING.toSeconds(), PING.toSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public ModoEstudo modo() {
        return propriedades.habilitado() ? ModoEstudo.REAL : ModoEstudo.SIMULADO;
    }

    /** Com a Deriv ligada, ja deixa a conexao aberta ao subir: a primeira flecha nao paga a conexao. */
    @EventListener(ApplicationReadyEvent.class)
    public void aquecer() {
        if (!propriedades.habilitado()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                conectada();
            } catch (Exception e) {
                log.warn("Deriv: conexao inicial falhou ({}); tenta de novo na primeira flecha", mensagem(e));
            }
        });
    }

    /** A Volatilidade 100 (o ativo de sempre desta corretora). */
    @Override
    public Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
        return abrir(AtivoEstudo.V100, direcao, nocionalUsdt, idCliente);
    }

    /**
     * Esta corretora para um ativo da Deriv: mesma conexao, contrato do ativo (simbolo e prazo). A V100 e a propria
     * corretora.
     */
    public Corretora para(AtivoEstudo ativo) {
        if (ativo == AtivoEstudo.V100) {
            return this;
        }
        ativo.contratoDeriv().orElseThrow(() -> new IllegalArgumentException(ativo + " nao e da Deriv"));
        CorretoraDerivOptions deriv = this;
        return new Corretora() {
            @Override
            public ModoEstudo modo() {
                return deriv.modo();
            }

            @Override
            public Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
                return deriv.abrir(ativo, direcao, nocionalUsdt, idCliente);
            }

            @Override
            public Execucao fechar(Direcao direcao, Execucao abertura, String idCliente) {
                return deriv.fechar(direcao, abertura, idCliente);
            }
        };
    }

    Execucao abrir(AtivoEstudo ativo, Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
        exigirReal();
        AtivoEstudo.Contrato prazo = ativo.contratoDeriv()
                .orElseThrow(() -> new IllegalArgumentException(ativo + " nao e da Deriv"));
        String contrato;
        BigDecimal ask;
        BigDecimal spot;
        try {
            String tipo = direcao == Direcao.ALTA ? "CALL" : "PUT";
            JsonNode p = pedir(mapper.createObjectNode()
                    .put("proposal", 1).put("amount", arredondarStake(nocionalUsdt)).put("basis", "stake")
                    .put("contract_type", tipo).put("currency", "USD").put("duration", prazo.duracao())
                    .put("duration_unit", prazo.unidade()).put("underlying_symbol", ativo.simbolo())).path("proposal");
            String proposalId = p.path("id").asText();
            ask = decimal(p, "ask_price");
            spot = decimal(p, "spot");
            if (proposalId.isBlank() || ask.signum() <= 0) throw new IllegalStateException("Proposta Deriv sem preço");
            JsonNode comprado = pedir(mapper.createObjectNode().put("buy", proposalId).put("price", ask));
            contrato = comprado.path("buy").path("contract_id").asText();
            if (contrato.isBlank()) throw new IllegalStateException("Compra Deriv sem contract_id");
        } catch (Exception e) {
            throw new RegraNegocioException("Deriv recusou a opção: " + motivoDaRecusa(mensagem(e), ativo));
        }
        // comprado: daqui em diante nada pode virar recusa (o contrato vale na Deriv)
        Execucao.Ticks ticks = esperarEntrada(contrato);
        BigDecimal preco = ticks != null && ticks.entrada() != null ? ticks.entrada() : spot;
        return new Execucao("deriv-" + contrato, BigDecimal.ZERO, preco, ask.negate(), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, ticks);
    }

    @Override
    public Execucao fechar(Direcao direcao, Execucao abertura, String idCliente) {
        exigirReal();
        String contrato = abertura.ordemId().replaceFirst("^deriv-", "");
        try {
            JsonNode contratoFinal = esperarLiquidacao(contrato);
            roteador.esquecer(contrato);
            BigDecimal recebido = recebido(contratoFinal, abertura.fluxoUsdt());
            BigDecimal lucro = contratoFinal.hasNonNull("profit") ? decimal(contratoFinal, "profit") : recebido.add(abertura.fluxoUsdt());
            BigDecimal preco = precoDeSaida(direcao, abertura.precoMedio(), contratoFinal, lucro);
            return new Execucao("deriv-fechado-" + contrato, BigDecimal.ZERO, preco, recebido,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, RoteadorDeriv.ticks(contratoFinal));
        } catch (Exception e) {
            throw new RegraNegocioException("Não foi possível liquidar a opção Deriv: " + mensagem(e));
        }
    }

    /** Mercado fechado (ouro e prata param no fim de semana): diz em portugues qual. */
    static String motivoDaRecusa(String mensagem, AtivoEstudo ativo) {
        String texto = mensagem == null ? "" : mensagem;
        if (texto.toLowerCase(java.util.Locale.ROOT).contains("closed")) {
            return "o mercado de " + ativo.flecha().nome() + " está fechado agora (" + texto + ")";
        }
        return texto;
    }

    /**
     * Acompanha o contrato recem-comprado e espera o tick de entrada. Sem ele no prazo (ou com a conexao caindo),
     * devolve nulo: a abertura segue com o preco da proposta e o fechamento traz os ticks.
     */
    private Execucao.Ticks esperarEntrada(String contrato) {
        RoteadorDeriv.Contrato acompanhamento = roteador.acompanhar(contrato);
        try {
            assinar(contrato);
            return RoteadorDeriv.ticks(acompanhamento.entrada.get(ESPERA_ENTRADA.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            log.warn("Deriv: contrato {} sem o tick de entrada a tempo ({})", contrato, mensagem(e));
            return null;
        }
    }

    /**
     * Espera o contrato terminar: pelo acompanhamento aberto na compra e, se ele nao existe mais (servico reiniciou,
     * conexao caiu), consultando o contrato de novo ate ele terminar.
     */
    private JsonNode esperarLiquidacao(String contrato) throws Exception {
        long limite = System.nanoTime() + ESPERA.toNanos();
        RoteadorDeriv.Contrato acompanhamento = roteador.contrato(contrato);
        if (acompanhamento != null) {
            try {
                return acompanhamento.liquidacao.get(ESPERA.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException | TimeoutException e) {
                log.warn("Deriv: contrato {} sem liquidação pelo acompanhamento ({}); consultando", contrato, mensagem(e));
                roteador.esquecer(contrato);
                limite = System.nanoTime() + ESPERA.toNanos();
            }
        }
        acompanhamento = roteador.acompanhar(contrato);
        while (System.nanoTime() < limite) {
            JsonNode c = pedir(mapper.createObjectNode().put("proposal_open_contract", 1)
                    .put("contract_id", Long.parseLong(contrato))).path("proposal_open_contract");
            if (RoteadorDeriv.vendido(c)) return c;
            try {
                return acompanhamento.liquidacao.get(1, TimeUnit.SECONDS);
            } catch (TimeoutException aindaAberto) {
                // consulta de novo
            } catch (ExecutionException conexaoCaiu) {
                acompanhamento = roteador.acompanhar(contrato); // a proxima consulta reconecta
            }
        }
        throw new IllegalStateException("tempo esgotado aguardando liquidação");
    }

    /** Assina as atualizacoes do contrato (tick de entrada, ticks e liquidacao) na conexao aberta. */
    private void assinar(String contrato) throws Exception {
        ObjectNode corpo = mapper.createObjectNode().put("proposal_open_contract", 1)
                .put("contract_id", Long.parseLong(contrato)).put("subscribe", 1)
                .put("req_id", requisicao.incrementAndGet());
        enviar(conectada(), corpo.toString());
    }

    /** Envia um pedido na conexao aberta e espera a resposta dele. */
    private JsonNode pedir(ObjectNode corpo) throws Exception {
        int req = requisicao.incrementAndGet();
        corpo.put("req_id", req);
        CompletableFuture<JsonNode> resposta = roteador.esperarResposta(req);
        try {
            enviar(conectada(), corpo.toString());
            return resposta.get(ESPERA.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception causa ? causa : e;
        } catch (TimeoutException e) {
            throw new IllegalStateException("tempo esgotado aguardando a Deriv");
        } finally {
            roteador.desistir(req);
        }
    }

    private void enviar(WebSocket ws, String json) throws Exception {
        synchronized (enviando) { // o WebSocket do Java nao aceita dois envios ao mesmo tempo
            ws.sendText(json, true).get(10, TimeUnit.SECONDS);
        }
    }

    /** A conexao aberta; sem ela (primeira vez ou caiu), conecta de novo com um OTP novo. */
    private WebSocket conectada() throws Exception {
        WebSocket ws = conexao;
        if (aberta(ws)) return ws;
        synchronized (conectando) {
            ws = conexao;
            if (aberta(ws)) return ws;
            ws = http.newWebSocketBuilder().buildAsync(URI.create(otp(obterConta())), new Ouvinte())
                    .get(10, TimeUnit.SECONDS);
            conexao = ws;
            log.info("Deriv: conexão aberta");
            return ws;
        }
    }

    private static boolean aberta(WebSocket ws) {
        return ws != null && !ws.isOutputClosed() && !ws.isInputClosed();
    }

    private void pingar() {
        WebSocket ws = conexao;
        if (!aberta(ws)) return;
        try {
            enviar(ws, "{\"ping\":1}");
        } catch (Exception e) {
            caiu(ws, e);
        }
    }

    private void caiu(WebSocket ws, Throwable motivo) {
        if (conexao != ws) {
            return; // uma conexao antiga terminando: a atual segue valendo
        }
        conexao = null;
        log.warn("Deriv: conexão caiu ({}); reconecta no próximo pedido", mensagem(motivo));
        roteador.conexaoCaiu(new IllegalStateException("conexão com a Deriv caiu"));
    }

    @PreDestroy
    void encerrar() {
        pings.shutdownNow();
        WebSocket ws = conexao;
        conexao = null;
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "servico encerrando").exceptionally(e -> null);
    }

    private void exigirReal() {
        if (!propriedades.habilitado()) throw new RegraNegocioException("Deriv está em modo SIMULADO ou sem credenciais");
    }

    private BigDecimal arredondarStake(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.DOWN).max(new BigDecimal("0.35"));
    }

    private String obterConta() throws Exception {
        if (!propriedades.accountId().isBlank()) return propriedades.accountId();
        String emCache = contaEmCache;
        if (emCache != null) return emCache;
        var request = HttpRequest.newBuilder(URI.create(propriedades.restUrl() + "/trading/v1/options/accounts"))
                .header("Authorization", "Bearer " + propriedades.apiToken()).header("Deriv-App-ID", propriedades.appId()).GET().build();
        JsonNode raiz = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        JsonNode data = raiz.path("data");
        String conta = null;
        if (data.isArray()) {
            for (JsonNode c : data) {
                if (propriedades.accountType().equalsIgnoreCase(c.path("account_type").asText())) {
                    conta = c.path("account_id").asText();
                    break;
                }
            }
        } else if (propriedades.accountType().equalsIgnoreCase(data.path("account_type").asText())) {
            conta = data.path("account_id").asText();
        }
        if (conta == null || conta.isBlank()) {
            throw new IllegalStateException("Nenhuma conta Deriv " + propriedades.accountType() + " encontrada");
        }
        contaEmCache = conta;
        return conta;
    }

    private String otp(String accountId) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(propriedades.restUrl() + "/trading/v1/options/accounts/" + accountId + "/otp"))
                .header("Authorization", "Bearer " + propriedades.apiToken()).header("Deriv-App-ID", propriedades.appId())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        JsonNode raiz = mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
        String url = raiz.path("data").path("url").asText();
        if (url.isBlank()) throw new IllegalStateException("OTP Deriv não retornou URL WebSocket");
        return url;
    }

    private static BigDecimal decimal(JsonNode n, String campo) { return new BigDecimal(n.path(campo).asText("0")); }

    /**
     * O que a Deriv pagou pelo contrato no fim ({@code sell_price}): o premio se ganhou, 0 se perdeu ou empatou.
     * O {@code payout} NAO serve: num contrato perdido ele continua trazendo o premio que seria pago (antes cada
     * perda entrava no resultado em reais como ganho). Sem sell_price: lucro + o que foi pago na compra
     * ({@code fluxoEntrada} e negativo); sem os dois, o payout so se ganhou.
     */
    static BigDecimal recebido(JsonNode contrato, BigDecimal fluxoEntrada) {
        if (contrato.hasNonNull("sell_price")) {
            return decimal(contrato, "sell_price");
        }
        if (contrato.hasNonNull("profit")) {
            return decimal(contrato, "profit").subtract(fluxoEntrada).max(BigDecimal.ZERO);
        }
        return "won".equalsIgnoreCase(contrato.path("status").asText()) && contrato.hasNonNull("payout")
                ? decimal(contrato, "payout") : BigDecimal.ZERO;
    }

    /**
     * Preco de saida sintetico. A Deriv diz quem ganhou (o lucro do contrato), nao um preco que o
     * {@link ResultadoOperacao} possa comparar; ele mede acerto como direcao x movimento. Por isso o preco anda
     * 1 ponto NA DIRECAO PREVISTA quando o contrato deu lucro e contra ela quando deu prejuizo. Sem o fator da
     * direcao, uma QUEDA (PUT) vencedora virava erro e uma perdedora virava acerto.
     * Empate (tick de saida igual ao de entrada): o preco nao anda e o jogo devolve a aposta, embora na Deriv o
     * contrato perca.
     */
    static BigDecimal precoDeSaida(Direcao direcao, BigDecimal precoEntrada, JsonNode contrato, BigDecimal lucro) {
        int marca = empatou(contrato) ? 0 : lucro.signum() > 0 ? 1 : -1;
        return precoEntrada.add(BigDecimal.valueOf((long) marca * direcao.sinal()));
    }

    /**
     * O contrato terminou no mesmo preco em que entrou. A API nova manda {@code entry_spot}/{@code exit_spot}; a
     * antiga, {@code entry_tick}/{@code exit_tick} (so olhar {@code exit_tick} deixava todo empate passar como erro).
     */
    static boolean empatou(JsonNode contrato) {
        Execucao.Ticks t = RoteadorDeriv.ticks(contrato);
        return t != null && t.entrada() != null && t.saida() != null && t.entrada().compareTo(t.saida()) == 0;
    }

    private static String mensagem(Throwable e) {
        Throwable causa = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
    }

    /** Recebe as mensagens da conexao (que podem chegar em pedacos) e passa ao roteador. */
    private final class Ouvinte implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence texto, boolean ultimo) {
            buffer.append(texto);
            if (ultimo) {
                try {
                    roteador.receber(mapper.readTree(buffer.toString()));
                } catch (Exception e) {
                    log.debug("Deriv: mensagem ignorada ({})", mensagem(e));
                }
                buffer.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int codigo, String motivo) {
            caiu(ws, new IllegalStateException("fechada (" + codigo + " " + motivo + ")"));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable erro) {
            caiu(ws, erro);
        }
    }
}
