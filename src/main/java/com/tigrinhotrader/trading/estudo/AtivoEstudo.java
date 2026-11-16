package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Ativos do estudo e a flecha de mercado de cada um. Os da Deriv (V100, ouro e prata) operam a opcao Sobe/Desce de
 * verdade quando a Deriv esta ligada; bitcoin e ethereum ficam simulados com o preco real.
 */
public enum AtivoEstudo {
    BTC("BTCUSDT", TipoFlecha.BTC, null),
    ETH("ETHUSDT", TipoFlecha.ETH, null),
    /** Ouro (Gold/USD) na Deriv: 5 minutos e o menor prazo de Sobe/Desce que ela oferece para metais. */
    OURO("frxXAUUSD", TipoFlecha.OURO, Contrato.minutos(5)),
    /** Prata (Silver/USD) na Deriv: tambem 5 minutos. */
    PRATA("frxXAGUSD", TipoFlecha.PRATA, Contrato.minutos(5)),
    /** Volatilidade 100 (1s): 5 ticks (~5 s). */
    V100("1HZ100V", TipoFlecha.V100, Contrato.ticks(5));

    /** Ouro antigo (PAXG na Binance): operacoes gravadas antes de o ouro ir para a Deriv. */
    private static final String OURO_ANTIGO = "PAXGUSDT";

    /**
     * Prazo do contrato Sobe/Desce na Deriv: {@code duracao} na {@code unidade} da API ("t" = ticks, "m" = minutos).
     */
    public record Contrato(int duracao, String unidade) {

        static Contrato ticks(int n) {
            return new Contrato(n, "t");
        }

        static Contrato minutos(int n) {
            return new Contrato(n, "m");
        }

        /** Prazo fixo em tempo (contratos em minutos); vazio nos de ticks, que terminam pelo tick de saida. */
        public Optional<Duration> prazoFixo() {
            return "m".equals(unidade) ? Optional.of(Duration.ofMinutes(duracao)) : Optional.empty();
        }
    }

    private final String simbolo;
    private final TipoFlecha flecha;
    private final Contrato contrato;

    AtivoEstudo(String simbolo, TipoFlecha flecha, Contrato contrato) {
        this.simbolo = simbolo;
        this.flecha = flecha;
        this.contrato = contrato;
    }

    public String simbolo() {
        return simbolo;
    }

    /** A flecha que, ao ser solta, abre uma operacao neste ativo. */
    public TipoFlecha flecha() {
        return flecha;
    }

    /** O contrato na Deriv; vazio nos ativos que nao sao da Deriv. */
    public Optional<Contrato> contratoDeriv() {
        return Optional.ofNullable(contrato);
    }

    public boolean naDeriv() {
        return contrato != null;
    }

    public static Optional<AtivoEstudo> porSimbolo(String simbolo) {
        if (simbolo == null) {
            return Optional.empty();
        }
        String procurado = simbolo.trim();
        if (procurado.equalsIgnoreCase(OURO_ANTIGO)) {
            return Optional.of(OURO);
        }
        return Arrays.stream(values()).filter(a -> a.simbolo.equalsIgnoreCase(procurado)).findFirst();
    }
}
