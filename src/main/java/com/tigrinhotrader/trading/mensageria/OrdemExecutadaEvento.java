package com.tigrinhotrader.trading.mensageria;

import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento ordem.executada, consumido por wallet-service (ajusta saldo) e notification-service.
 * valorLiquido = lucro (positivo) ou prejuizo (negativo) a aplicar no saldo.
 */
public record OrdemExecutadaEvento(UUID ordemId, String usuarioId, String simbolo, TipoOrdem tipo,
                                   BigDecimal valor, StatusOrdem resultado, BigDecimal valorLiquido,
                                   BigDecimal precoEntrada, BigDecimal precoSaida, Instant executadaEm) {

    public static OrdemExecutadaEvento de(Ordem ordem) {
        return new OrdemExecutadaEvento(ordem.getId(), ordem.getUsuarioId(), ordem.getSimbolo(), ordem.getTipo(),
                ordem.getValor(), ordem.getStatus(), ordem.valorLiquido(), ordem.getPrecoEntrada(),
                ordem.getPrecoSaida(), ordem.getResolvidaEm());
    }
}
