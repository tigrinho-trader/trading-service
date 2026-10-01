package com.tigrinhotrader.trading.web;

import com.tigrinhotrader.trading.estrategia.CalculadoraBarreira;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** O que o frontend precisa pra repetir a conta da {@link CalculadoraBarreira} enquanto o jogador escolhe o alvo. */
public record BarreiraResponse(double margem, BigDecimal multiplicadorMinimo, BigDecimal multiplicadorMaximo,
                               double distanciaMinima, Map<String, Double> volatilidadePorSegundo) {

    static BarreiraResponse de(List<String> simbolos, ToDoubleFunction<String> volatilidade) {
        Map<String, Double> porAtivo = new LinkedHashMap<>();
        for (String simbolo : simbolos) {
            String ativo = simbolo.trim().toUpperCase(Locale.ROOT);
            porAtivo.put(ativo, CalculadoraBarreira.volatilidadeLimitada(volatilidade.applyAsDouble(ativo)));
        }
        return new BarreiraResponse(CalculadoraBarreira.MARGEM, CalculadoraBarreira.MULTIPLICADOR_MINIMO,
                CalculadoraBarreira.MULTIPLICADOR_MAXIMO, CalculadoraBarreira.DISTANCIA_MINIMA, porAtivo);
    }
}
