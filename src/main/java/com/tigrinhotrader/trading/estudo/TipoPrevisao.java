package com.tigrinhotrader.trading.estudo;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Como o jogador previu (preferencia dele no perfil). BINARIA: so sobe ou desce; alem da operacao, o estudo conta
 * uma aposta binaria (+aposta no acerto, -aposta no erro). PREVISAO: mirou o preco exato daqui a 5 s.
 */
public enum TipoPrevisao {
    BINARIA,
    PREVISAO;

    public static Optional<TipoPrevisao> porNome(String nome) {
        if (nome == null) {
            return Optional.empty();
        }
        String procurado = nome.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.name().equals(procurado)).findFirst();
    }
}
