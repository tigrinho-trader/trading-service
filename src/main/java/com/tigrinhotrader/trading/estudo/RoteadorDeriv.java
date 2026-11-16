package com.tigrinhotrader.trading.estudo;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encaminha as mensagens da conexao com a Deriv, que e uma so para todas as flechas: a resposta de cada pedido vai
 * para quem esperava aquele {@code req_id}, e as atualizacoes de um contrato ({@code proposal_open_contract}) vao
 * para o acompanhamento daquele {@code contract_id} (o tick de entrada e a liquidacao).
 */
final class RoteadorDeriv {

    /** O que se espera de um contrato comprado: o tick de entrada e a liquidacao (com o tick de saida). */
    static final class Contrato {
        final CompletableFuture<JsonNode> entrada = new CompletableFuture<>();
        final CompletableFuture<JsonNode> liquidacao = new CompletableFuture<>();
    }

    private final Map<Integer, CompletableFuture<JsonNode>> pedidos = new ConcurrentHashMap<>();
    private final Map<String, Contrato> contratos = new ConcurrentHashMap<>();

    /** Registra a espera da resposta do pedido {@code req} (antes de enviar, para nao perder uma resposta rapida). */
    CompletableFuture<JsonNode> esperarResposta(int req) {
        CompletableFuture<JsonNode> resposta = new CompletableFuture<>();
        pedidos.put(req, resposta);
        return resposta;
    }

    void desistir(int req) {
        pedidos.remove(req);
    }

    /** Comeca (ou continua) a acompanhar o contrato. */
    Contrato acompanhar(String contratoId) {
        return contratos.computeIfAbsent(contratoId, id -> new Contrato());
    }

    /** O acompanhamento do contrato, ou nulo se ninguem o acompanha (ex.: o servico reiniciou). */
    Contrato contrato(String contratoId) {
        return contratos.get(contratoId);
    }

    void esquecer(String contratoId) {
        contratos.remove(contratoId);
    }

    void receber(JsonNode mensagem) {
        int req = mensagem.path("echo_req").path("req_id").asInt(mensagem.path("req_id").asInt(0));
        CompletableFuture<JsonNode> resposta = pedidos.remove(req);
        if (mensagem.has("error")) {
            if (resposta != null) {
                resposta.completeExceptionally(new IllegalStateException(mensagemDeErro(mensagem)));
            }
            return;
        }
        JsonNode c = mensagem.path("proposal_open_contract");
        if (c.isObject() && c.hasNonNull("contract_id")) {
            Contrato contrato = contratos.get(c.path("contract_id").asText());
            if (contrato != null) {
                if (c.hasNonNull("entry_spot") || c.hasNonNull("entry_tick")) {
                    contrato.entrada.complete(c);
                }
                if (vendido(c)) {
                    contrato.entrada.complete(c);
                    contrato.liquidacao.complete(c);
                }
            }
        }
        if (resposta != null) {
            resposta.complete(mensagem);
        }
    }

    /**
     * A conexao caiu: os pedidos em espera falham. Os acompanhamentos tambem saem; o fechamento consulta o contrato
     * de novo numa conexao nova (ele continua valendo na Deriv).
     */
    void conexaoCaiu(Throwable motivo) {
        pedidos.values().forEach(f -> f.completeExceptionally(motivo));
        pedidos.clear();
        contratos.values().forEach(c -> {
            c.entrada.completeExceptionally(motivo);
            c.liquidacao.completeExceptionally(motivo);
        });
        contratos.clear();
    }

    /** O contrato ja terminou (vendido na expiracao: ganhou ou perdeu). */
    static boolean vendido(JsonNode c) {
        String status = c.path("status").asText();
        return c.path("is_sold").asInt() == 1 || "sold".equalsIgnoreCase(status) || "won".equalsIgnoreCase(status)
                || "lost".equalsIgnoreCase(status);
    }

    /**
     * Ticks do contrato. A API nova chama de {@code entry_spot}/{@code exit_spot} (com {@code *_time} em segundos);
     * a antiga, de {@code entry_tick}/{@code exit_tick}. Nulo se o contrato ainda nao tem nenhum.
     */
    static Execucao.Ticks ticks(JsonNode c) {
        BigDecimal entrada = numero(c, "entry_spot", "entry_tick");
        Instant entradaEm = instante(c, "entry_spot_time", "entry_tick_time");
        BigDecimal saida = numero(c, "exit_spot", "exit_tick");
        Instant saidaEm = instante(c, "exit_spot_time", "exit_tick_time");
        Instant expiraEm = instante(c, "date_expiry", "date_expiry");
        if (entrada == null && saida == null) {
            return null;
        }
        return new Execucao.Ticks(entrada, entradaEm, saida, saidaEm, expiraEm);
    }

    private static BigDecimal numero(JsonNode c, String campo, String antigo) {
        JsonNode n = c.hasNonNull(campo) ? c.get(campo) : c.get(antigo);
        if (n == null || n.isNull() || n.asText().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instante(JsonNode c, String campo, String antigo) {
        JsonNode n = c.hasNonNull(campo) ? c.get(campo) : c.get(antigo);
        return n == null || n.isNull() || !n.canConvertToLong() ? null : Instant.ofEpochSecond(n.asLong());
    }

    private static String mensagemDeErro(JsonNode mensagem) {
        String texto = mensagem.path("error").path("message").asText();
        return texto.isBlank() ? mensagem.path("error").path("code").asText("erro da Deriv") : texto;
    }
}
