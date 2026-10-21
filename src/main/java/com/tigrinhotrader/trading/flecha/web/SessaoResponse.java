package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.StatusSessao;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Estado da sessao. {@code flechas} = restantes por tipo (as de mercado somando todos os valores); {@code mercado} =
 * lotes de mercado restantes, por flecha e depois por valor; resultado = ganhos + devolucao - custo.
 * {@code devolucaoFlechas}: as flechas de mercado que sobraram e voltaram em moedas ao encerrar (zeros antes de
 * encerrar ou sem sobra).
 */
public record SessaoResponse(UUID id, String campo, StatusSessao status, BigDecimal custo, BigDecimal ganhos,
                             BigDecimal resultado, Map<String, Integer> flechas, List<Lote> mercado,
                             Map<String, Integer> compradas, Map<String, Integer> disparadas, int acertos,
                             Devolucao devolucaoFlechas, Instant criadaEm, Instant encerradaEm) {

    /** {@code {"flecha":"btc","valor":10.00,"quantidade":2}}. */
    public record Lote(String flecha, BigDecimal valor, int quantidade) {
    }

    /** {@code {"quantidade":3,"valorBruto":30.00,"taxa":0.06,"valorDevolvido":29.94}}. */
    public record Devolucao(int quantidade, BigDecimal valorBruto, BigDecimal taxa, BigDecimal valorDevolvido) {
    }

    public static SessaoResponse de(SessaoFlecha s) {
        List<Lote> mercado = s.getLotesMercado().stream()
                .map(l -> new Lote(l.flecha().id(), l.valor(), l.quantidade())).toList();
        Devolucao devolucao = new Devolucao(s.getDevolucaoQuantidade(), s.getDevolucaoBruto(), s.getDevolucaoTaxa(),
                s.getDevolucaoValor());
        return new SessaoResponse(s.getId(), s.getCampo().slug(), s.getStatus(), s.getCusto(), s.getGanhos(),
                s.resultado(), s.getRestantes().comoMapa(), mercado, s.getCompradas().comoMapa(),
                s.getDisparadas().comoMapa(), s.getAcertos(), devolucao, s.getCriadaEm(), s.getEncerradaEm());
    }
}
