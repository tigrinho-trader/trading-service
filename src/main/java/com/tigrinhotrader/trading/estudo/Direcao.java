package com.tigrinhotrader.trading.estudo;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Direcao prevista pelo jogador: ALTA abre comprado (long), QUEDA abre vendido (short). */
public enum Direcao {
    ALTA(1),
    QUEDA(-1);

    private final int sinal;

    Direcao(int sinal) {
        this.sinal = sinal;
    }

    public int sinal() {
        return sinal;
    }

    public static Optional<Direcao> porNome(String nome) {
        if (nome == null) {
            return Optional.empty();
        }
        String procurado = nome.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(d -> d.name().equals(procurado)).findFirst();
    }
}
