package com.tigrinhotrader.trading.web;

import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrdemResponse(UUID id, String simbolo, TipoOrdem tipo, BigDecimal valor, BigDecimal multiplicador,
                            BigDecimal precoEntrada, BigDecimal precoSaida, int duracaoSegundos, StatusOrdem status,
                            BigDecimal valorPago, BigDecimal valorLiquido, Instant criadaEm, Instant expiraEm,
                            Instant resolvidaEm) {

    public static OrdemResponse de(Ordem o) {
        return new OrdemResponse(o.getId(), o.getSimbolo(), o.getTipo(), o.getValor(), o.getMultiplicador(),
                o.getPrecoEntrada(), o.getPrecoSaida(), o.getDuracaoSegundos(), o.getStatus(), o.getValorPago(),
                o.valorLiquido(), o.getCriadaEm(), o.getExpiraEm(), o.getResolvidaEm());
    }
}
