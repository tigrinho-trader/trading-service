package com.tigrinhotrader.trading.preco;

import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Ultimo preco recebido via preco.atualizado, por ativo. */
@Component
public class PrecoCache {

    private final Map<String, PrecoAtualizadoEvento> precos = new ConcurrentHashMap<>();

    public void atualizar(PrecoAtualizadoEvento evento) {
        if (evento == null || evento.simbolo() == null || evento.preco() == null) {
            return;
        }
        precos.merge(evento.simbolo().toUpperCase(Locale.ROOT), evento, (atual, novo) ->
                atual.atualizadoEm() != null && novo.atualizadoEm() != null
                        && novo.atualizadoEm().isBefore(atual.atualizadoEm()) ? atual : novo);
    }

    public Optional<BigDecimal> preco(String simbolo) {
        return Optional.ofNullable(precos.get(simbolo.toUpperCase(Locale.ROOT))).map(PrecoAtualizadoEvento::preco);
    }
}
