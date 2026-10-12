package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resultado de uma operacao fechada.
 *
 * <p>Bruto: o que a previsao rendeu so pelo movimento do preco (sem taxas). Liquido: soma dos fluxos de caixa das
 * duas ordens, com o BTC que sobrou ou faltou (taxas cobradas em BTC, arredondamento) avaliado pelo preco de
 * saida e menos as taxas pagas em outro ativo. Acertou: o preco andou na direcao prevista. Binario: o placar de
 * uma aposta de sobe-ou-desce de {@code apostaBinaria} reais (+aposta no acerto, -aposta no erro, 0 se o preco
 * nao mexeu), sem taxas.
 */
public record ResultadoOperacao(BigDecimal brutoUsdt, BigDecimal taxasUsdt, BigDecimal liquidoUsdt,
                                BigDecimal brutoBrl, BigDecimal taxasBrl, BigDecimal liquidoBrl, boolean acertou,
                                BigDecimal binarioBrl) {

    public static ResultadoOperacao calcular(Direcao direcao, Execucao abertura, Execucao fechamento,
                                             BigDecimal cambio, BigDecimal apostaBinaria) {
        BigDecimal sinal = BigDecimal.valueOf(direcao.sinal());
        BigDecimal bruto = fechamento.precoMedio().subtract(abertura.precoMedio())
                .multiply(abertura.quantidadeBtc()).multiply(sinal);
        BigDecimal taxas = abertura.taxaUsdt().add(fechamento.taxaUsdt());
        BigDecimal liquido = abertura.fluxoUsdt().add(fechamento.fluxoUsdt())
                .add(abertura.fluxoBtc().add(fechamento.fluxoBtc()).multiply(fechamento.precoMedio()))
                .subtract(abertura.taxaOutraUsdt())
                .subtract(fechamento.taxaOutraUsdt());
        int movimento = direcao.sinal() * fechamento.precoMedio().compareTo(abertura.precoMedio());
        BigDecimal binario = apostaBinaria.multiply(BigDecimal.valueOf(movimento)).setScale(4, RoundingMode.HALF_UP);
        return new ResultadoOperacao(escala(bruto), escala(taxas), escala(liquido),
                emReais(bruto, cambio), emReais(taxas, cambio), emReais(liquido, cambio), movimento > 0, binario);
    }

    private static BigDecimal escala(BigDecimal valor) {
        return valor.setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal emReais(BigDecimal usdt, BigDecimal cambio) {
        return usdt.multiply(cambio).setScale(4, RoundingMode.HALF_UP);
    }
}
