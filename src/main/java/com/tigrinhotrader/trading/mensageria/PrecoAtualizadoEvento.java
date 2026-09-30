package com.tigrinhotrader.trading.mensageria;

import java.math.BigDecimal;
import java.time.Instant;

/** Evento preco.atualizado publicado pelo market-data-service. */
public record PrecoAtualizadoEvento(String simbolo, BigDecimal preco, BigDecimal variacaoPercentual24h,
                                    Instant atualizadoEm) {
}
