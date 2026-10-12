package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resultado de uma operacao fechada.
 *
 * <p>Bruto: o que a previsao rendeu so pelo movimento do preco (sem taxas). Liquido: soma dos fluxos de caixa das
 * duas ordens, com o BTC que sobrou ou faltou (taxas cobradas em BTC, arredondamento) avaliado pelo preco de
 * saida e menos as taxas pagas em outro ativo. Acertou: o preco andou na direcao prevista.
 */
public record ResultadoOperacao(BigDecimal brutoUsdt, BigDecimal taxasUsdt, BigDecimal liquidoUsdt,
                                BigDecimal brutoBrl, BigDecimal taxasBrl, BigDecimal liquidoBrl, boolean acertou) {

    public static ResultadoOperacao calcular(Direcao direcao, Execucao abertura, Execucao fechamento,
                                             BigDecimal cambio) {
        BigDecimal sinal = BigDecimal.valueOf(direcao.sinal());
        BigDecimal bruto = fechamento.precoMedio().subtract(abertura.precoMedio())
                .multiply(abertura.quantidadeBtc()).multiply(sinal);
        BigDecimal taxas = abertura.taxaUsdt().add(fechamento.taxaUsdt());
        BigDecimal liquido = abertura.fluxoUsdt().add(fechamento.fluxoUsdt())
                .add(abertura.fluxoBtc().add(fechamento.fluxoBtc()).multiply(fechamento.precoMedio()))
                .subtract(abertura.taxaOutraUsdt())
                .subtract(fechamento.taxaOutraUsdt());
        boolean acertou = direcao.sinal() * fechamento.precoMedio().compareTo(abertura.precoMedio()) > 0;
        return new ResultadoOperacao(escala(bruto), escala(taxas), escala(liquido),
                emReais(bruto, cambio), emReais(taxas, cambio), emReais(liquido, cambio), acertou);
    }

    private static BigDecimal escala(BigDecimal valor) {
        return valor.setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal emReais(BigDecimal usdt, BigDecimal cambio) {
        return usdt.multiply(cambio).setScale(4, RoundingMode.HALF_UP);
    }
}
