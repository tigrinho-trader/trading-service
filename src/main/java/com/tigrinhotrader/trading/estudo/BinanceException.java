package com.tigrinhotrader.trading.estudo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpStatusCodeException;

/** Erro devolvido pela API da Binance ({"code": -2010, "msg": "..."}). */
public class BinanceException extends RuntimeException {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int codigo;

    public BinanceException(int codigo, String mensagem) {
        super("Binance recusou (" + codigo + "): " + mensagem);
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }

    static BinanceException de(HttpStatusCodeException e) {
        try {
            JsonNode corpo = JSON.readTree(e.getResponseBodyAsString());
            if (corpo != null && corpo.has("code")) {
                return new BinanceException(corpo.path("code").asInt(), corpo.path("msg").asText(""));
            }
        } catch (Exception ignorado) {
            // corpo que nao e JSON: cai na mensagem generica
        }
        return new BinanceException(-e.getStatusCode().value(), "HTTP " + e.getStatusCode().value());
    }
}
