package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService.PedidoLote;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Corpos dos pedidos do Flecha Certeira. As regras do jogo (limites, saldo, valor) ficam no servico (422). */
public final class FlechaRequests {

    private FlechaRequests() {
    }

    /**
     * {@code { "campo": "bosque", "flechas": { "wood": 10 },
     * "mercado": [ { "flecha": "btc", "valor": 10, "quantidade": 3 } ] }}. {@code mercado} e opcional; flechas de
     * mercado em {@code flechas} (clientes antigos) valem como lotes de 10 moedas.
     */
    public record NovaSessao(@NotBlank String campo, @NotNull Map<String, Integer> flechas,
                             List<@NotNull PedidoLote> mercado) {
    }

    /** {@code { "flecha": "gold", "acertos": ["red", "big"] }}: passaros atingidos por esta flecha, na ordem. */
    public record Tiro(@NotBlank String flecha, List<String> acertos) {
    }

    /**
     * {@code { "flecha": "btc", "quantidade": 2, "valor": 20 }}: {@code valor} (moedas por flecha) e obrigatorio nas
     * flechas de mercado e ignorado na madeira.
     */
    public record CompraFlechas(@NotBlank String flecha, @NotNull Integer quantidade, BigDecimal valor) {
    }
}
