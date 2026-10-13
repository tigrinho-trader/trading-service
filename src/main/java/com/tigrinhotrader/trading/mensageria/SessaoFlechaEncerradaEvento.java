package com.tigrinhotrader.trading.mensageria;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de uma sessao do Flecha Certeira, publicado como ordem.executada no MESMO formato JSON que a carteira
 * consome: ordemId = id da sessao, simbolo = slug do campo, tipo FLECHA_CERTEIRA, valor = custo,
 * resultado GANHOU/PERDEU/EMPATOU e valorLiquido = ganhos - custo. A carteira e idempotente por ordemId.
 *
 * <p>Tambem leva o pagamento de uma flecha de mercado liquidada depois de a sessao encerrar (tipo FLECHA_MERCADO):
 * ordemId = referencia unica da operacao do estudo, simbolo = par do ativo, valor = aposta e valorLiquido = moedas
 * pagas (a aposta ja saiu no custo da sessao).
 *
 * <p>E o reembolso das flechas antigas guardadas (tipo FLECHA_REEMBOLSO, quando ferro, ouro e diamante sairam do
 * jogo): ordemId = referencia do reembolso da aljava, simbolo = slug do campo, valor = valorLiquido = moedas pagas
 * por elas.
 */
public record SessaoFlechaEncerradaEvento(UUID ordemId, String usuarioId, String simbolo, String tipo,
                                          BigDecimal valor, String resultado, BigDecimal valorLiquido,
                                          Instant executadaEm, BigDecimal ganhos, int acertos) {

    public static final String TIPO = "FLECHA_CERTEIRA";
    public static final String TIPO_FLECHA_MERCADO = "FLECHA_MERCADO";
    public static final String TIPO_REEMBOLSO = "FLECHA_REEMBOLSO";
}
