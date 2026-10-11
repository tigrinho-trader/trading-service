package com.tigrinhotrader.trading.mensageria;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de uma sessao do Flecha Certeira, publicado como ordem.executada no MESMO formato JSON que a carteira
 * consome: ordemId = id da sessao, simbolo = slug do campo, tipo FLECHA_CERTEIRA, valor = custo,
 * resultado GANHOU/PERDEU/EMPATOU e valorLiquido = ganhos - custo. A carteira e idempotente por ordemId.
 */
public record SessaoFlechaEncerradaEvento(UUID ordemId, String usuarioId, String simbolo, String tipo,
                                          BigDecimal valor, String resultado, BigDecimal valorLiquido,
                                          Instant executadaEm, BigDecimal ganhos, int acertos) {

    public static final String TIPO = "FLECHA_CERTEIRA";
}
