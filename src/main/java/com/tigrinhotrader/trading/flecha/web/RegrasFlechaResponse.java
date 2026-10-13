package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.estudo.AtivoEstudo;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.RegrasFlecha;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tabelas do Flecha Certeira para o frontend conferir/mostrar os mesmos numeros que o servidor cobra. Passaros valem
 * 0 (so as apostas das flechas de mercado rendem moedas) e a madeira e gratis.
 */
public record RegrasFlechaResponse(List<CampoInfo> campos, Map<String, Integer> passaros, List<FlechaInfo> flechas,
                                   int maxFlechas, int maxCombo) {

    /**
     * {@code passarosMercado}: ids dos passaros (e flechas) de mercado do campo, ex. ["btc", "eth"]; {@code ativos}:
     * os pares desses ativos no estudo, na mesma ordem, ex. ["BTCUSDT", "ETHUSDT"].
     */
    public record CampoInfo(String slug, String nome, BigDecimal entrada, int flechaBase, int mult,
                            Map<String, BigDecimal> pool, List<String> passarosMercado, List<String> ativos) {

        static CampoInfo de(Campo c) {
            Map<String, BigDecimal> pool = new LinkedHashMap<>();
            c.pool().forEach((passaro, peso) -> pool.put(passaro.id(), peso));
            return new CampoInfo(c.slug(), c.nome(), c.entrada(), c.flechaBase(), c.mult(), pool,
                    c.passarosMercado().stream().map(Passaro::id).toList(),
                    Arrays.stream(AtivoEstudo.values()).filter(a -> c.temFlecha(a.flecha())).map(AtivoEstudo::simbolo)
                            .toList());
        }
    }

    /**
     * {@code passaroAlvo}: id do unico passaro que a flecha acerta (flechas de mercado), ou nulo se acerta todos.
     * {@code precoFixo}: preco da flecha em qualquer campo (flechas de mercado, a aposta binaria do estudo), ou nulo
     * quando o preco e fatorPreco x flechaBase do campo (a madeira tem fator 0: gratis).
     */
    public record FlechaInfo(String id, String nome, int fatorPreco, BigDecimal fatorMoedas, String passaroAlvo,
                             Integer precoFixo) {

        static FlechaInfo de(TipoFlecha t) {
            return new FlechaInfo(t.id(), t.nome(), t.fatorPreco(), t.fatorMoedas(),
                    t.passaroAlvo().map(Passaro::id).orElse(null),
                    t.deMercado() ? RegrasFlecha.PRECO_FLECHA_MERCADO : null);
        }
    }

    public static RegrasFlechaResponse atuais() {
        Map<String, Integer> passaros = new LinkedHashMap<>();
        for (Passaro p : Passaro.values()) {
            passaros.put(p.id(), p.moedas());
        }
        return new RegrasFlechaResponse(
                Arrays.stream(Campo.values()).map(CampoInfo::de).toList(),
                passaros,
                Arrays.stream(TipoFlecha.values()).map(FlechaInfo::de).toList(),
                RegrasFlecha.MAX_FLECHAS,
                RegrasFlecha.MAX_COMBO);
    }
}
