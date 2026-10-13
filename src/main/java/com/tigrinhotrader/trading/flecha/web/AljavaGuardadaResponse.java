package com.tigrinhotrader.trading.flecha.web;

import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService.Guardadas;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flechas guardadas do jogador, com todo campo e todo tipo (zeros inclusive):
 * {@code { "campos": { "vilarejo": { "wood": 0 }, ... },
 * "mercado": { "btc": 0, "eth": 0, "ouro": 0 } }}. As comuns so valem no campo onde foram compradas; as de mercado
 * valem em qualquer campo.
 */
public record AljavaGuardadaResponse(Map<String, Map<String, Integer>> campos, Map<String, Integer> mercado) {

    public static AljavaGuardadaResponse de(Guardadas guardadas) {
        Map<String, Map<String, Integer>> campos = new LinkedHashMap<>();
        for (Campo campo : Campo.values()) {
            campos.put(campo.slug(), mapa(guardadas.porCampo().get(campo), false));
        }
        return new AljavaGuardadaResponse(campos, mapa(guardadas.mercado(), true));
    }

    private static Map<String, Integer> mapa(Aljava aljava, boolean mercado) {
        Map<String, Integer> mapa = new LinkedHashMap<>();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            if (tipo.deMercado() == mercado) {
                mapa.put(tipo.id(), aljava.get(tipo));
            }
        }
        return mapa;
    }
}
