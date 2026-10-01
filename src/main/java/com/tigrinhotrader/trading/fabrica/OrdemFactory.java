package com.tigrinhotrader.trading.fabrica;

import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import com.tigrinhotrader.trading.estrategia.CalculadoraBarreira;
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
        return criar(usuarioId, simbolo, tipo, ModoJogo.DIFICIL, valor, duracaoSegundos, precoEntrada, agora);
    }

    public Ordem criar(String usuarioId, String simbolo, TipoOrdem tipo, ModoJogo modo, BigDecimal valor,
                       int duracaoSegundos, BigDecimal precoEntrada, Instant agora) {
        if (tipo == TipoOrdem.BARREIRA) {
            throw new RegraNegocioException("BARREIRA precisa de alvo: use criarBarreira");
        }
        validar(simbolo, valor, duracaoSegundos, precoEntrada);
        return new Ordem(
                UUID.randomUUID(),
                usuarioId,
                simbolo.toUpperCase(Locale.ROOT),
                tipo,
                modo,
                valor.setScale(2, RoundingMode.HALF_UP),
                estrategias.para(tipo).multiplicador(modo),
                precoEntrada,
                duracaoSegundos,
                agora);
    }

    /**
     * Rodada "sem toque": o multiplicador sai da distancia entre o alvo e o preco, ajustada pela volatilidade
     * do ativo ({@link CalculadoraBarreira}).
     */
    public Ordem criarBarreira(String usuarioId, String simbolo, BigDecimal alvo, BigDecimal valor,
                               int duracaoSegundos, BigDecimal precoEntrada, double volatilidade, Instant agora) {
        validar(simbolo, valor, duracaoSegundos, precoEntrada);
        if (alvo == null || alvo.signum() <= 0) {
            throw new RegraNegocioException("BARREIRA precisa de um alvo positivo");
        }
        double distancia = CalculadoraBarreira.distancia(precoEntrada, alvo);
        if (distancia < CalculadoraBarreira.DISTANCIA_MINIMA) {
            throw new RegraNegocioException("Alvo colado no preco atual: escolha um ponto mais longe");
        }
        BigDecimal multiplicador = CalculadoraBarreira.multiplicador(distancia, volatilidade, duracaoSegundos);
        if (multiplicador.compareTo(CalculadoraBarreira.MULTIPLICADOR_MINIMO) < 0) {
            throw new RegraNegocioException("Alvo longe demais: pagaria so " + multiplicador + "x");
        }
        return Ordem.barreira(UUID.randomUUID(), usuarioId, simbolo.toUpperCase(Locale.ROOT), alvo,
                valor.setScale(2, RoundingMode.HALF_UP), multiplicador, precoEntrada, duracaoSegundos, agora);
    }

    private static void validar(String simbolo, BigDecimal valor, int duracaoSegundos, BigDecimal precoEntrada) {
        if (!DURACOES_PERMITIDAS.contains(duracaoSegundos)) {
            throw new RegraNegocioException("Duracao invalida: use uma de " + DURACOES_PERMITIDAS.stream().sorted().toList());
        }
        if (valor.compareTo(VALOR_MINIMO) < 0 || valor.compareTo(VALOR_MAXIMO) > 0) {
            throw new RegraNegocioException("Valor deve estar entre " + VALOR_MINIMO + " e " + VALOR_MAXIMO);
        }
        if (precoEntrada == null || precoEntrada.signum() <= 0) {
            throw new RegraNegocioException("Preco de entrada invalido para " + simbolo);
        }
    }
}
