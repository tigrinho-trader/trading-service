package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.RegrasFlecha;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tabelas do Flecha Certeira para o frontend conferir/mostrar os mesmos numeros que o servidor cobra. */
public record RegrasFlechaResponse(List<CampoInfo> campos, Map<String, Integer> passaros, List<FlechaInfo> flechas,
                                   int maxFlechas, int maxCombo) {

    public record CampoInfo(String slug, String nome, BigDecimal entrada, int flechaBase, int mult,
                            Map<String, BigDecimal> pool) {

        static CampoInfo de(Campo c) {
            Map<String, BigDecimal> pool = new LinkedHashMap<>();
            c.pool().forEach((passaro, peso) -> pool.put(passaro.id(), peso));
            return new CampoInfo(c.slug(), c.nome(), c.entrada(), c.flechaBase(), c.mult(), pool);
        }
    }

    public record FlechaInfo(String id, String nome, int fatorPreco, BigDecimal fatorMoedas) {

        static FlechaInfo de(TipoFlecha t) {
            return new FlechaInfo(t.id(), t.nome(), t.fatorPreco(), t.fatorMoedas());
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
