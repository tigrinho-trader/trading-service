package com.tigrinhotrader.trading.estudo;

/**
 * ABRINDO: registrada, ordem de entrada a caminho. ABERTA: posicao aberta, fecha em 5 s. FECHADA: resultado
 * calculado. RECUSADA: nao operou (limite, valor abaixo do minimo). FALHOU: erro ao abrir ou ao fechar.
 */
public enum StatusOperacao {
    ABRINDO,
    ABERTA,
    FECHADA,
    RECUSADA,
    FALHOU
}
