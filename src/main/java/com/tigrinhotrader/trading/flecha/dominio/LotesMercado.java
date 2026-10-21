package com.tigrinhotrader.trading.flecha.dominio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Flechas de mercado da sessao agrupadas por tipo e valor, gravadas como texto JSON na coluna
 * {@code sessoes_flecha.lotes_mercado}: {@code [{"flecha":"btc","valor":10.00,"quantidade":3}]}. Os valores sao
 * comparados sem olhar a escala (20 e 20.00 sao o mesmo lote) e a lista sai ordenada por tipo e depois por valor.
 */
final class LotesMercado {

    static final String VAZIO = "[]";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Um lote como fica no JSON. */
    record Item(String flecha, BigDecimal valor, int quantidade) {
    }

    private final Map<TipoFlecha, TreeMap<BigDecimal, Integer>> porTipo = new EnumMap<>(TipoFlecha.class);

    /**
     * Le o JSON gravado. Texto vazio ou ilegivel vira nenhum lote (a sessao completa com o valor antigo a partir dos
     * totais da aljava); tipo desconhecido, que nao e de mercado ou sem valor e ignorado.
     */
    static LotesMercado ler(String json) {
        LotesMercado lotes = new LotesMercado();
        if (json == null || json.isBlank()) {
            return lotes;
        }
        try {
            for (Item item : JSON.readValue(json, Item[].class)) {
                if (item.valor() != null) {
                    TipoFlecha.porId(item.flecha()).filter(TipoFlecha::deMercado)
                            .ifPresent(tipo -> lotes.somar(tipo, item.valor(), item.quantidade()));
                }
            }
        } catch (JsonProcessingException e) {
            return new LotesMercado();
        }
        return lotes;
    }

    /** O JSON para gravar, na ordem de {@link #lista()}. */
    String json() {
        List<Item> itens = lista().stream().map(l -> new Item(l.flecha().id(), l.valor(), l.quantidade())).toList();
        try {
            return JSON.writeValueAsString(itens);
        } catch (JsonProcessingException e) {
            // so tipos simples: nao acontece
            throw new IllegalStateException(e);
        }
    }

    /** Soma flechas ao lote do tipo e valor (cria o lote se precisar). Quantidade menor que 1 nao faz nada. */
    void somar(TipoFlecha tipo, BigDecimal valor, int quantidade) {
        if (quantidade < 1) {
            return;
        }
        porTipo.computeIfAbsent(tipo, t -> new TreeMap<>())
                .merge(valor.setScale(2, RoundingMode.HALF_UP), quantidade, Integer::sum);
    }

    /** Tira uma flecha do lote do tipo e valor; falso se nao ha esse lote. */
    boolean tirar(TipoFlecha tipo, BigDecimal valor) {
        TreeMap<BigDecimal, Integer> lotes = porTipo.get(tipo);
        Integer quantidade = lotes == null ? null : lotes.get(valor);
        if (quantidade == null) {
            return false;
        }
        if (quantidade > 1) {
            lotes.put(valor, quantidade - 1);
        } else {
            lotes.remove(valor);
        }
        return true;
    }

    /** O menor valor com flecha do tipo, ou vazio se nao ha nenhuma. */
    Optional<BigDecimal> menorValor(TipoFlecha tipo) {
        TreeMap<BigDecimal, Integer> lotes = porTipo.get(tipo);
        return lotes == null || lotes.isEmpty() ? Optional.empty() : Optional.of(lotes.firstKey());
    }

    /** Ha flecha do tipo com este valor? */
    boolean tem(TipoFlecha tipo, BigDecimal valor) {
        TreeMap<BigDecimal, Integer> lotes = porTipo.get(tipo);
        return lotes != null && lotes.containsKey(valor);
    }

    /** Flechas do tipo, somando todos os valores. */
    int quantidade(TipoFlecha tipo) {
        TreeMap<BigDecimal, Integer> lotes = porTipo.get(tipo);
        return lotes == null ? 0 : lotes.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Todas as flechas de mercado. */
    int quantidade() {
        return lista().stream().mapToInt(LoteFlecha::quantidade).sum();
    }

    /** Soma de valor x quantidade de todos os lotes. */
    BigDecimal valorTotal() {
        return lista().stream().map(LoteFlecha::total).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** Os lotes, ordenados pelo tipo (ordem do enum) e depois pelo valor. */
    List<LoteFlecha> lista() {
        List<LoteFlecha> lista = new ArrayList<>();
        porTipo.forEach((tipo, lotes) -> lotes.forEach((valor, quantidade) ->
                lista.add(new LoteFlecha(tipo, valor, quantidade))));
        return lista;
    }
}
