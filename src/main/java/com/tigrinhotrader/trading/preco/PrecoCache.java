package com.tigrinhotrader.trading.preco;

import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Ultimo preco recebido via preco.atualizado, por ativo, e um historico curto pra medir a volatilidade. */
@Component
public class PrecoCache {

    /** ~5 minutos de miniTicker (1 atualizacao por segundo). */
    static final int AMOSTRAS = 300;
    /** Abaixo disso a estimativa de volatilidade e ruido demais. */
    static final int AMOSTRAS_MINIMAS = 20;

    private final Map<String, PrecoAtualizadoEvento> precos = new ConcurrentHashMap<>();
    private final Map<String, Deque<PrecoAtualizadoEvento>> historico = new ConcurrentHashMap<>();

    public void atualizar(PrecoAtualizadoEvento evento) {
        if (evento == null || evento.simbolo() == null || evento.preco() == null) {
            return;
        }
        String simbolo = evento.simbolo().toUpperCase(Locale.ROOT);
        precos.merge(simbolo, evento, (atual, novo) ->
                atual.atualizadoEm() != null && novo.atualizadoEm() != null
                        && novo.atualizadoEm().isBefore(atual.atualizadoEm()) ? atual : novo);
        guardarAmostra(simbolo, evento);
    }

    public Optional<BigDecimal> preco(String simbolo) {
        return Optional.ofNullable(precos.get(simbolo.toUpperCase(Locale.ROOT))).map(PrecoAtualizadoEvento::preco);
    }

    /**
     * Volatilidade realizada do ativo, como desvio padrao do retorno por raiz de segundo
     * (0.0001 = o preco costuma andar 0,01% em 1s). Vazio enquanto nao ha amostras suficientes.
     */
    public Optional<Double> volatilidadePorSegundo(String simbolo) {
        Deque<PrecoAtualizadoEvento> amostras = historico.get(simbolo.toUpperCase(Locale.ROOT));
        if (amostras == null) {
            return Optional.empty();
        }
        List<PrecoAtualizadoEvento> copia;
        synchronized (amostras) {
            copia = List.copyOf(amostras);
        }
        if (copia.size() < AMOSTRAS_MINIMAS) {
            return Optional.empty();
        }
        double somaQuadrados = 0;
        double segundos = 0;
        for (int i = 1; i < copia.size(); i++) {
            double anterior = copia.get(i - 1).preco().doubleValue();
            double atual = copia.get(i).preco().doubleValue();
            double retorno = Math.log(atual / anterior);
            somaQuadrados += retorno * retorno;
            segundos += Math.max(0.001,
                    Duration.between(copia.get(i - 1).atualizadoEm(), copia.get(i).atualizadoEm()).toMillis() / 1000.0);
        }
        return Optional.of(Math.sqrt(somaQuadrados / segundos));
    }

    /** So entram no historico eventos com horario, em ordem cronologica. */
    private void guardarAmostra(String simbolo, PrecoAtualizadoEvento evento) {
        Instant quando = evento.atualizadoEm();
        if (quando == null || evento.preco().signum() <= 0) {
            return;
        }
        Deque<PrecoAtualizadoEvento> amostras = historico.computeIfAbsent(simbolo, s -> new ArrayDeque<>());
        synchronized (amostras) {
            PrecoAtualizadoEvento ultimo = amostras.peekLast();
            if (ultimo != null && !quando.isAfter(ultimo.atualizadoEm())) {
                return;
            }
            amostras.addLast(evento);
            if (amostras.size() > AMOSTRAS) {
                amostras.removeFirst();
            }
        }
    }
}
