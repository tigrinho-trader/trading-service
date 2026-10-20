package com.tigrinhotrader.trading.estudo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptador da opção binária CALL/PUT de 5 ticks da Deriv.
 * A sessão fica aberta entre a compra e a liquidação para receber o resultado do contrato.
 */
@Component
public class CorretoraDerivOptions implements Corretora {
    private static final Logger log = LoggerFactory.getLogger(CorretoraDerivOptions.class);
    private static final String SIMBOLO = "1HZ100V";
    private static final int TICKS = 5;
    private static final Duration ESPERA = Duration.ofSeconds(20);

    private final DerivTradingProperties propriedades;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicInteger requisicao = new AtomicInteger(100);
    private final java.util.concurrent.ConcurrentHashMap<String, Sessao> sessoes = new java.util.concurrent.ConcurrentHashMap<>();

    public CorretoraDerivOptions(DerivTradingProperties propriedades) {
        this.propriedades = propriedades;
    }

    @Override
    public ModoEstudo modo() {
        return propriedades.habilitado() ? ModoEstudo.REAL : ModoEstudo.SIMULADO;
    }

    @Override
    public Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
        exigirReal();
        try {
            String accountId = obterConta();
            String wsUrl = otp(accountId);
            Sessao sessao = new Sessao(wsUrl);
            sessao.conectar();
            int req = requisicao.incrementAndGet();
            String tipo = direcao == Direcao.ALTA ? "CALL" : "PUT";
            sessao.enviar(mapper.createObjectNode()
                    .put("proposal", 1).put("amount", arredondarStake(nocionalUsdt)).put("basis", "stake")
                    .put("contract_type", tipo).put("currency", "USD").put("duration", TICKS)
                    .put("duration_unit", "t").put("underlying_symbol", SIMBOLO).put("req_id", req).toString());
            JsonNode proposta = sessao.esperar("proposal", req);
            JsonNode p = proposta.path("proposal");
            String proposalId = p.path("id").asText();
            BigDecimal ask = decimal(p, "ask_price");
            if (proposalId.isBlank() || ask.signum() <= 0) throw new IllegalStateException("Proposta Deriv sem preço");

            req = requisicao.incrementAndGet();
            sessao.enviar(mapper.createObjectNode().put("buy", proposalId).put("price", ask)
                    .put("req_id", req).toString());
            JsonNode comprado = sessao.esperar("buy", req);
            String contrato = comprado.path("buy").path("contract_id").asText();
            if (contrato.isBlank()) throw new IllegalStateException("Compra Deriv sem contract_id");
            sessoes.put(contrato, sessao);
            return new Execucao("deriv-" + contrato, BigDecimal.ZERO, decimal(p, "spot"),
                    ask.negate(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        } catch (Exception e) {
            throw new RegraNegocioException("Deriv recusou a opção: " + mensagem(e));
        }
    }

    @Override
    public Execucao fechar(Direcao direcao, Execucao abertura, String idCliente) {
        exigirReal();
        String contrato = abertura.ordemId().replaceFirst("^deriv-", "");
        Sessao sessao = sessoes.get(contrato);
        if (sessao == null) throw new RegraNegocioException("Sessão Deriv perdida; contrato " + contrato + " precisa ser conferido");
        try {
            int req = requisicao.incrementAndGet();
            sessao.enviar(mapper.createObjectNode().put("proposal_open_contract", 1)
                    .put("contract_id", Long.parseLong(contrato)).put("subscribe", 1).put("req_id", req).toString());
            JsonNode contratoFinal = sessao.esperarLiquidacao(contrato);
            BigDecimal payout = contratoFinal.hasNonNull("payout") ? decimal(contratoFinal, "payout") : BigDecimal.ZERO;
            BigDecimal lucro = contratoFinal.hasNonNull("profit") ? decimal(contratoFinal, "profit") : payout.add(abertura.fluxoUsdt());
            BigDecimal marca = lucro.signum() > 0 ? BigDecimal.ONE : BigDecimal.ONE.negate();
            BigDecimal preco = abertura.precoMedio().add(marca.multiply(BigDecimal.ONE));
            sessoes.remove(contrato);
            sessao.fechar();
            return new Execucao("deriv-fechado-" + contrato, BigDecimal.ZERO, preco, payout,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        } catch (Exception e) {
            throw new RegraNegocioException("Não foi possível liquidar a opção Deriv: " + mensagem(e));
        }
    }

    private void exigirReal() {
        if (!propriedades.habilitado()) throw new RegraNegocioException("Deriv está em modo SIMULADO ou sem credenciais");
    }

    private BigDecimal arredondarStake(BigDecimal valor) {
        return valor.setScale(2, java.math.RoundingMode.DOWN).max(new BigDecimal("0.35"));
    }

    private String obterConta() throws Exception {
        if (!propriedades.accountId().isBlank()) return propriedades.accountId();
        var request = java.net.http.HttpRequest.newBuilder(URI.create(propriedades.restUrl() + "/trading/v1/options/accounts"))
                .header("Authorization", "Bearer " + propriedades.apiToken()).header("Deriv-App-ID", propriedades.appId()).GET().build();
        JsonNode raiz = mapper.readTree(http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        JsonNode data = raiz.path("data");
        if (data.isArray()) {
            for (JsonNode conta : data) if (propriedades.accountType().equalsIgnoreCase(conta.path("account_type").asText())) return conta.path("account_id").asText();
        } else if (propriedades.accountType().equalsIgnoreCase(data.path("account_type").asText())) {
            return data.path("account_id").asText();
        }
        throw new IllegalStateException("Nenhuma conta Deriv " + propriedades.accountType() + " encontrada");
    }

    private String otp(String accountId) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(URI.create(propriedades.restUrl() + "/trading/v1/options/accounts/" + accountId + "/otp"))
                .header("Authorization", "Bearer " + propriedades.apiToken()).header("Deriv-App-ID", propriedades.appId())
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
        JsonNode raiz = mapper.readTree(http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        String url = raiz.path("data").path("url").asText();
        if (url.isBlank()) throw new IllegalStateException("OTP Deriv não retornou URL WebSocket");
        return url;
    }

    private static BigDecimal decimal(JsonNode n, String campo) { return new BigDecimal(n.path(campo).asText("0")); }
    private static String mensagem(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    private final class Sessao {
        private final String url; private final LinkedBlockingQueue<JsonNode> fila = new LinkedBlockingQueue<>(); private WebSocket socket;
        Sessao(String url) { this.url = url; }
        void conectar() throws Exception { socket = http.newWebSocketBuilder().buildAsync(URI.create(url), new Listener()).get(10, TimeUnit.SECONDS); }
        void enviar(String json) { socket.sendText(json, true); }
        JsonNode esperar(String tipo, int req) throws Exception { long limite = System.nanoTime() + ESPERA.toNanos();
            while (System.nanoTime() < limite) { JsonNode n = fila.poll(1, TimeUnit.SECONDS); if (n == null) continue; if (n.has("error")) throw new IllegalStateException(n.path("error").path("message").asText());
                if (tipo.equals(n.path("msg_type").asText()) && (req == 0 || n.path("echo_req").path("req_id").asInt() == req)) return n; }
            throw new IllegalStateException("tempo esgotado aguardando " + tipo);
        }
        JsonNode esperarLiquidacao(String contrato) throws Exception { long limite = System.nanoTime() + ESPERA.toNanos();
            while (System.nanoTime() < limite) { JsonNode n = fila.poll(1, TimeUnit.SECONDS); if (n == null) continue; if (n.has("error")) throw new IllegalStateException(n.path("error").path("message").asText());
                JsonNode c = n.path("proposal_open_contract"); if (contrato.equals(c.path("contract_id").asText()) && (c.path("is_sold").asInt() == 1 || "sold".equalsIgnoreCase(c.path("status").asText()))) return c; }
            throw new IllegalStateException("tempo esgotado aguardando liquidação");
        }
        void fechar() { if (socket != null) socket.sendClose(WebSocket.NORMAL_CLOSURE, "contrato liquidado").exceptionally(e -> null); }
        final class Listener implements WebSocket.Listener { private final StringBuilder buffer = new StringBuilder();
            @Override public void onOpen(WebSocket ws) { ws.request(1); }
            @Override public CompletionStage<?> onText(WebSocket ws, CharSequence texto, boolean ultimo) { buffer.append(texto); if (ultimo) { try { fila.offer(mapper.readTree(buffer.toString())); } catch (Exception ignored) {} buffer.setLength(0); } ws.request(1); return null; }
        }
    }
}
