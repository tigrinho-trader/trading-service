package com.tigrinhotrader.trading.fabrica;

import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import com.tigrinhotrader.trading.estrategia.RegistroEstrategias;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Factory: monta uma rodada nova a partir do tipo escolhido, ja com o multiplicador
 * da estrategia correspondente e as regras de duracao/valor do jogo aplicadas.
 */
@Component
public class OrdemFactory {

    public static final Set<Integer> DURACOES_PERMITIDAS = Set.of(15, 30, 60, 300);
    public static final BigDecimal VALOR_MINIMO = new BigDecimal("1.00");
    public static final BigDecimal VALOR_MAXIMO = new BigDecimal("10000.00");

    private final RegistroEstrategias estrategias;

    public OrdemFactory(RegistroEstrategias estrategias) {
        this.estrategias = estrategias;
    }

    public Ordem criar(String usuarioId, String simbolo, TipoOrdem tipo, BigDecimal valor,
                       int duracaoSegundos, BigDecimal precoEntrada, Instant agora) {
        if (!DURACOES_PERMITIDAS.contains(duracaoSegundos)) {
            throw new RegraNegocioException("Duracao invalida: use uma de " + DURACOES_PERMITIDAS.stream().sorted().toList());
        }
        if (valor.compareTo(VALOR_MINIMO) < 0 || valor.compareTo(VALOR_MAXIMO) > 0) {
            throw new RegraNegocioException("Valor deve estar entre " + VALOR_MINIMO + " e " + VALOR_MAXIMO);
        }
        if (precoEntrada == null || precoEntrada.signum() <= 0) {
            throw new RegraNegocioException("Preco de entrada invalido para " + simbolo);
        }
        return new Ordem(
                UUID.randomUUID(),
                usuarioId,
                simbolo.toUpperCase(Locale.ROOT),
                tipo,
                valor.setScale(2, RoundingMode.HALF_UP),
                estrategias.para(tipo).multiplicador(),
                precoEntrada,
                duracaoSegundos,
                agora);
    }
}
