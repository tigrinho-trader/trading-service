package com.tigrinhotrader.trading.estudo;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Estudo da "sabedoria das massas" ({@code estudo.*}): cada flecha de mercado (bitcoin, ethereum, ouro) vira uma
 * operacao na direcao prevista pelo jogador, fechada {@code fechaApos} depois. Em {@link ModoEstudo#SIMULADO}
 * (padrao) nada e enviado a corretora; {@link ModoEstudo#REAL} usa dinheiro de verdade, so vale para o
 * {@code simbolo} configurado e so e ligado pelo dono da conta.
 *
 * @param simbolo unico ativo que pode operar no modo REAL; os outros ativos sao sempre simulados
 * @param margemBrl margem de referencia, em reais (so para o resumo; cada operacao tem o valor da flecha em reais)
 * @param alavancagem nocional de referencia = margem x alavancagem (so para o resumo)
 * @param taxaSimulada taxa por ponta usada na simulacao (0.001 = 0,1%)
 * @param cambioReserva reais por USDT quando nao ha cotacao de USDTBRL
 * @param maxOperacoesDia operacoes reais por dia (UTC), somando todos os jogadores
 * @param maxAbertas operacoes reais abertas ao mesmo tempo
 * @param perdaMaximaDiaBrl para de operar no dia quando o resultado liquido chega a menos isso
 * @param intervaloPorJogador intervalo minimo entre duas operacoes do mesmo jogador
 * @param revisaoHabilitada liga a revisao periodica de operacoes que ficaram pelo caminho
 * @param apostaBinariaBrl valor da aposta binaria contada no placar (+ no acerto, - no erro); nao e dinheiro real
 */
@ConfigurationProperties(prefix = "estudo")
public record EstudoProperties(ModoEstudo modo, String simbolo, BigDecimal margemBrl, int alavancagem,
                               Duration fechaApos, BigDecimal taxaSimulada, BigDecimal cambioReserva,
                               int maxOperacoesDia, int maxAbertas, BigDecimal perdaMaximaDiaBrl,
                               Duration intervaloPorJogador, boolean revisaoHabilitada, Binance binance,
                               BigDecimal apostaBinariaBrl) {

    /**
     * Acesso a Binance (so no modo REAL). A chave vem SO do ambiente (BINANCE_API_KEY/BINANCE_API_SECRET) e nunca
     * aparece em log nem em resposta.
     */
    public record Binance(String url, String apiKey, String apiSecret, long recvWindow, Duration timeout) {

        public Binance {
            url = url == null || url.isBlank() ? "https://api.binance.com" : url;
            apiKey = apiKey == null ? "" : apiKey.trim();
            apiSecret = apiSecret == null ? "" : apiSecret.trim();
            recvWindow = recvWindow <= 0 ? 5000 : recvWindow;
            timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        }

        public boolean temCredenciais() {
            return !apiKey.isEmpty() && !apiSecret.isEmpty();
        }

        @Override
        public String toString() {
            return "Binance[url=" + url + ", credenciais=" + (temCredenciais() ? "configuradas" : "ausentes") + "]";
        }
    }

    public EstudoProperties {
        modo = modo == null ? ModoEstudo.SIMULADO : modo;
        simbolo = simbolo == null || simbolo.isBlank() ? "BTCUSDT" : simbolo;
        margemBrl = margemBrl == null ? new BigDecimal("10") : margemBrl;
        alavancagem = alavancagem <= 0 ? 3 : alavancagem;
        fechaApos = fechaApos == null ? Duration.ofSeconds(5) : fechaApos;
        taxaSimulada = taxaSimulada == null ? new BigDecimal("0.001") : taxaSimulada;
        cambioReserva = cambioReserva == null ? new BigDecimal("5.50") : cambioReserva;
        maxOperacoesDia = maxOperacoesDia <= 0 ? 300 : maxOperacoesDia;
        maxAbertas = maxAbertas <= 0 ? 5 : maxAbertas;
        perdaMaximaDiaBrl = perdaMaximaDiaBrl == null ? new BigDecimal("50") : perdaMaximaDiaBrl;
        intervaloPorJogador = intervaloPorJogador == null ? Duration.ofSeconds(1) : intervaloPorJogador;
        binance = binance == null ? new Binance(null, null, null, 0, null) : binance;
        apostaBinariaBrl = apostaBinariaBrl == null ? new BigDecimal("10") : apostaBinariaBrl;
    }

    /** Nocional de referencia em reais (margem x alavancagem); as operacoes usam o valor da flecha. */
    public BigDecimal nocionalBrl() {
        return margemBrl.multiply(BigDecimal.valueOf(alavancagem));
    }
}
