package com.tigrinhotrader.trading.estudo;

import com.fasterxml.jackson.databind.JsonNode;
import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Ordens a mercado na margem isolada da Binance (par BTCUSDT, {@code isIsolated=TRUE}).
 *
 * <ul>
 *   <li>ALTA: compra {@code q} BTC e, ao fechar, vende {@code q}.</li>
 *   <li>QUEDA: vende {@code q} BTC (emprestado se a conta nao tiver: MARGIN_BUY) e, ao fechar, recompra {@code q}
 *       devolvendo o emprestimo (AUTO_REPAY).</li>
 * </ul>
 * As duas pontas usam a mesma quantidade. A taxa cobrada em BTC deixa uma diferenca minuscula de BTC na conta
 * isolada; se faltar BTC para fechar, a ordem e repetida pegando emprestada a diferenca. O resultado de cada
 * operacao conta essa diferenca pelo preco de saida, entao o liquido fica certo.
 *
 * <p>A conta isolada BTCUSDT precisa estar ativada e com USDT de garantia (passo manual, na Binance).
 */
public class CorretoraBinanceMargem implements Corretora {

    private static final Logger LOG = LoggerFactory.getLogger(CorretoraBinanceMargem.class);

    /** "Account has insufficient balance for requested action." */
    static final int SALDO_INSUFICIENTE = -2010;
    /** Mais que isso acima do valor pedido para atingir o minimo da Binance: recusa em vez de operar. */
    static final BigDecimal FOLGA_MAXIMA_DO_MINIMO = new BigDecimal("1.5");

    record Filtros(BigDecimal passo, BigDecimal quantidadeMinima, BigDecimal nocionalMinimo) {
    }

    private final RestClient http;
    private final EstudoProperties.Binance acesso;
    private final String simbolo;
    private final PrecoCache precos;
    private final Clock relogio;
    private volatile Filtros filtros;

    public CorretoraBinanceMargem(RestClient http, EstudoProperties propriedades, PrecoCache precos, Clock relogio) {
        this.http = http;
        this.acesso = propriedades.binance();
        this.simbolo = propriedades.simbolo();
        this.precos = precos;
        this.relogio = relogio;
    }

    @Override
    public ModoEstudo modo() {
        return ModoEstudo.REAL;
    }

    @Override
    public Execucao abrir(Direcao direcao, BigDecimal nocionalUsdt, String idCliente) {
        BigDecimal quantidade = quantidadePara(nocionalUsdt, precoAtual(), filtros());
        return ordem(direcao == Direcao.ALTA ? "BUY" : "SELL", quantidade, "MARGIN_BUY", idCliente);
    }

    @Override
    public Execucao fechar(Direcao direcao, Execucao abertura, String idCliente) {
        String lado = direcao == Direcao.ALTA ? "SELL" : "BUY";
        try {
            return ordem(lado, abertura.quantidadeBtc(), "AUTO_REPAY", idCliente);
        } catch (BinanceException e) {
            if (e.codigo() != SALDO_INSUFICIENTE) {
                throw e;
            }
            LOG.info("Fechamento {} sem saldo suficiente; repetindo com emprestimo da diferenca", idCliente);
            return ordem(lado, abertura.quantidadeBtc(), "MARGIN_BUY", idCliente + "b");
        }
    }

    /**
     * Quantidade de BTC para um nocional: arredonda para baixo no passo do par e sobe de passo em passo ate
     * atingir os minimos da Binance. Se para isso a posicao ficar bem maior que a pedida, recusa.
     */
    static BigDecimal quantidadePara(BigDecimal nocionalUsdt, BigDecimal preco, Filtros f) {
        BigDecimal quantidade = nocionalUsdt.divide(preco, 12, RoundingMode.DOWN)
                .divide(f.passo(), 0, RoundingMode.DOWN).multiply(f.passo());
        while (quantidade.compareTo(f.quantidadeMinima()) < 0
                || quantidade.multiply(preco).compareTo(f.nocionalMinimo()) < 0) {
            quantidade = quantidade.add(f.passo());
        }
        BigDecimal valor = quantidade.multiply(preco);
        if (valor.compareTo(nocionalUsdt.multiply(FOLGA_MAXIMA_DO_MINIMO)) > 0) {
            throw new RegraNegocioException("Operacao de US$ " + nocionalUsdt.setScale(2, RoundingMode.HALF_UP)
                    + " abaixo do minimo da Binance (US$ " + f.nocionalMinimo().stripTrailingZeros().toPlainString()
                    + ")");
        }
        return quantidade.stripTrailingZeros();
    }

    private Execucao ordem(String lado, BigDecimal quantidade, String efeito, String idCliente) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", simbolo);
        params.put("isIsolated", "TRUE");
        params.put("side", lado);
        params.put("type", "MARKET");
        params.put("quantity", quantidade.toPlainString());
        params.put("sideEffectType", efeito);
        params.put("newOrderRespType", "FULL");
        params.put("newClientOrderId", idCliente);
        return interpretar("BUY".equals(lado), assinado(HttpMethod.POST, "/sapi/v1/margin/order", params));
    }

    /** Resposta FULL de uma ordem: quantidade e preco medio, fluxos e taxas por ativo. */
    Execucao interpretar(boolean compra, JsonNode r) {
        BigDecimal quantidade = new BigDecimal(r.path("executedQty").asText("0"));
        BigDecimal valor = new BigDecimal(r.path("cummulativeQuoteQty").asText("0"));
        if (quantidade.signum() <= 0) {
            throw new BinanceException(0, "ordem sem execucao (status " + r.path("status").asText() + ")");
        }
        BigDecimal preco = valor.divide(quantidade, 8, RoundingMode.HALF_UP);
        BigDecimal fluxoUsdt = compra ? valor.negate() : valor;
        BigDecimal fluxoBtc = compra ? quantidade : quantidade.negate();
        BigDecimal taxaUsdt = BigDecimal.ZERO;
        BigDecimal taxaOutra = BigDecimal.ZERO;
        String base = simbolo.substring(0, simbolo.length() - 4);
        String cotacao = simbolo.substring(simbolo.length() - 4);
        for (JsonNode fill : r.path("fills")) {
            BigDecimal comissao = new BigDecimal(fill.path("commission").asText("0"));
            if (comissao.signum() == 0) {
                continue;
            }
            String ativo = fill.path("commissionAsset").asText("");
            if (ativo.equals(cotacao)) {
                fluxoUsdt = fluxoUsdt.subtract(comissao);
                taxaUsdt = taxaUsdt.add(comissao);
            } else if (ativo.equals(base)) {
                fluxoBtc = fluxoBtc.subtract(comissao);
                taxaUsdt = taxaUsdt.add(comissao.multiply(new BigDecimal(fill.path("price").asText("0"))));
            } else {
                BigDecimal emUsdt = comissao.multiply(precoEmUsdt(ativo));
                taxaOutra = taxaOutra.add(emUsdt);
                taxaUsdt = taxaUsdt.add(emUsdt);
            }
        }
        return new Execucao(r.path("orderId").asText(), quantidade, preco, fluxoUsdt, fluxoBtc, taxaUsdt, taxaOutra);
    }

    /** Preco de um ativo em USDT (para converter taxa paga em BNB, por exemplo); zero se nao der para saber. */
    private BigDecimal precoEmUsdt(String ativo) {
        try {
            return ticker(ativo + "USDT");
        } catch (RuntimeException e) {
            LOG.warn("Sem preco de {} para converter a taxa: {}", ativo, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal precoAtual() {
        return precos.preco(simbolo).filter(p -> p.signum() > 0).orElseGet(() -> ticker(simbolo));
    }

    private BigDecimal ticker(String par) {
        JsonNode r = publico("/api/v3/ticker/price?symbol=" + par);
        BigDecimal preco = new BigDecimal(r.path("price").asText("0"));
        if (preco.signum() <= 0) {
            throw new ServicoIndisponivelException("Binance sem preco de " + par);
        }
        return preco;
    }

    /** Passo de quantidade e minimos do par (exchangeInfo), lidos uma vez. */
    Filtros filtros() {
        Filtros atual = filtros;
        if (atual != null) {
            return atual;
        }
        JsonNode simbolos = publico("/api/v3/exchangeInfo?symbol=" + simbolo).path("symbols");
        BigDecimal passo = null;
        BigDecimal minimo = BigDecimal.ZERO;
        BigDecimal nocional = BigDecimal.ZERO;
        for (JsonNode filtro : simbolos.path(0).path("filters")) {
            switch (filtro.path("filterType").asText()) {
                case "LOT_SIZE" -> {
                    passo = new BigDecimal(filtro.path("stepSize").asText());
                    minimo = new BigDecimal(filtro.path("minQty").asText("0"));
                }
                case "NOTIONAL", "MIN_NOTIONAL" -> nocional = new BigDecimal(filtro.path("minNotional").asText("0"));
                default -> { }
            }
        }
        if (passo == null || passo.signum() <= 0) {
            throw new ServicoIndisponivelException("Binance nao informou o passo de quantidade de " + simbolo);
        }
        filtros = new Filtros(passo.stripTrailingZeros(), minimo, nocional);
        return filtros;
    }

    private JsonNode publico(String caminhoComConsulta) {
        try {
            return http.get().uri(URI.create(acesso.url() + caminhoComConsulta)).retrieve().body(JsonNode.class);
        } catch (HttpStatusCodeException e) {
            throw BinanceException.de(e);
        } catch (RestClientException e) {
            throw new ServicoIndisponivelException("Binance indisponivel: " + e.getMessage());
        }
    }

    private JsonNode assinado(HttpMethod metodo, String caminho, Map<String, String> params) {
        params.put("recvWindow", String.valueOf(acesso.recvWindow()));
        params.put("timestamp", String.valueOf(relogio.millis()));
        String consulta = params.entrySet().stream()
                .map(e -> codificar(e.getKey()) + "=" + codificar(e.getValue()))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(acesso.url() + caminho + "?" + consulta + "&signature=" + assinar(consulta, acesso.apiSecret()));
        try {
            JsonNode corpo = http.method(metodo).uri(uri).header("X-MBX-APIKEY", acesso.apiKey())
                    .retrieve().body(JsonNode.class);
            if (corpo == null) {
                throw new BinanceException(0, "resposta vazia");
            }
            return corpo;
        } catch (HttpStatusCodeException e) {
            throw BinanceException.de(e);
        } catch (RestClientException e) {
            throw new ServicoIndisponivelException("Binance indisponivel: " + e.getMessage());
        }
    }

    /** Assinatura HMAC-SHA256 em hexadecimal, como a Binance exige nas rotas privadas. */
    static String assinar(String dados, String segredo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(dados.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponivel", e);
        }
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }
}
