package com.tigrinhotrader.trading.flecha.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Corpos dos pedidos do Flecha Certeira. As regras do jogo (limites, saldo) ficam no servico (422). */
public final class FlechaRequests {

    private FlechaRequests() {
    }

    /** {@code { "campo": "bosque", "flechas": { "wood": 10, "eth": 1 } }} */
    public record NovaSessao(@NotBlank String campo, @NotNull Map<String, Integer> flechas) {
    }

    /** {@code { "flecha": "gold", "acertos": ["red", "big"] }}: passaros atingidos por esta flecha, na ordem. */
    public record Tiro(@NotBlank String flecha, List<String> acertos) {
    }

    /** {@code { "flecha": "wood", "quantidade": 5 }} */
    public record CompraFlechas(@NotBlank String flecha, @NotNull Integer quantidade) {
    }
}
