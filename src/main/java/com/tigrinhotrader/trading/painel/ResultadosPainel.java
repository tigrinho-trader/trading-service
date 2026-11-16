package com.tigrinhotrader.trading.painel;

import com.tigrinhotrader.trading.estudo.ModoEstudo;
import com.tigrinhotrader.trading.estudo.OperacaoEstudo;
import com.tigrinhotrader.trading.estudo.OperacaoEstudoRepository;
import com.tigrinhotrader.trading.estudo.StatusOperacao;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ganhos e perdas de cada perfil de jogo desde uma data: as flechas de mercado liquidadas (apostado, pago, acertos,
 * erros, empates e devolvidas), o que foi poupado, o resultado real na corretora e as taxas de devolucao das rodadas.
 */
@Service
public class ResultadosPainel {

    /** Numeros de um perfil (ou do total, com usuarioId nulo). {@code lucro} = pago - apostado - taxasDevolucao. */
    public record PorPerfil(String usuarioId, long operacoes, long acertos, long erros, long empates, long devolvidas,
                            BigDecimal apostado, BigDecimal pago, BigDecimal poupado, BigDecimal taxasDevolucao,
                            BigDecimal lucro, BigDecimal resultadoRealBrl, long sessoes) {
    }

    public record Resumo(Instant desde, List<PorPerfil> perfis, PorPerfil total) {
    }

    private final OperacaoEstudoRepository operacoes;
    private final SessaoFlechaRepository sessoes;

    public ResultadosPainel(OperacaoEstudoRepository operacoes, SessaoFlechaRepository sessoes) {
        this.operacoes = operacoes;
        this.sessoes = sessoes;
    }

    @Transactional(readOnly = true)
    public Resumo calcular(Instant desde) {
        Map<String, Soma> porPerfil = new TreeMap<>();
        for (OperacaoEstudo o : operacoes.findByCriadaEmGreaterThanEqual(desde)) {
            if (o.getApostaMoedas() == null || o.getMoedasPagas() == null) {
                continue; // antiga, sem aposta, ou ainda nao liquidada
            }
            porPerfil.computeIfAbsent(o.getUsuarioId(), Soma::new).somar(o);
        }
        for (SessaoFlecha s : sessoes.findByCriadaEmGreaterThanEqual(desde)) {
            porPerfil.computeIfAbsent(s.getUsuarioId(), Soma::new).somar(s);
        }
        Soma total = new Soma(null);
        porPerfil.values().forEach(total::juntar);
        List<PorPerfil> perfis = porPerfil.values().stream().map(Soma::numeros)
                .sorted(Comparator.comparing(PorPerfil::lucro).reversed()).toList();
        return new Resumo(desde, perfis, total.numeros());
    }

    private static final class Soma {
        final String usuarioId;
        long operacoes, acertos, erros, empates, devolvidas, sessoes;
        BigDecimal apostado = BigDecimal.ZERO, pago = BigDecimal.ZERO, poupado = BigDecimal.ZERO,
                taxasDevolucao = BigDecimal.ZERO, resultadoRealBrl = BigDecimal.ZERO;

        Soma(String usuarioId) {
            this.usuarioId = usuarioId;
        }

        void somar(OperacaoEstudo o) {
            operacoes++;
            if (o.getStatus() != StatusOperacao.FECHADA) {
                devolvidas++;
            } else {
                switch (o.sinalAposta()) {
                    case 1 -> acertos++;
                    case -1 -> erros++;
                    default -> empates++;
                }
            }
            apostado = apostado.add(o.getApostaMoedas());
            pago = pago.add(o.getMoedasPagas());
            if (o.getPoupancaMoedas() != null) {
                poupado = poupado.add(o.getPoupancaMoedas());
            }
            if (o.getModo() == ModoEstudo.REAL && o.getStatus() == StatusOperacao.FECHADA
                    && o.getResultadoLiquidoBrl() != null) {
                resultadoRealBrl = resultadoRealBrl.add(o.getResultadoLiquidoBrl());
            }
        }

        void somar(SessaoFlecha s) {
            sessoes++;
            if (s.getDevolucaoTaxa() != null) {
                taxasDevolucao = taxasDevolucao.add(s.getDevolucaoTaxa());
            }
        }

        void juntar(Soma s) {
            operacoes += s.operacoes;
            acertos += s.acertos;
            erros += s.erros;
            empates += s.empates;
            devolvidas += s.devolvidas;
            sessoes += s.sessoes;
            apostado = apostado.add(s.apostado);
            pago = pago.add(s.pago);
            poupado = poupado.add(s.poupado);
            taxasDevolucao = taxasDevolucao.add(s.taxasDevolucao);
            resultadoRealBrl = resultadoRealBrl.add(s.resultadoRealBrl);
        }

        PorPerfil numeros() {
            BigDecimal lucro = pago.subtract(apostado).subtract(taxasDevolucao).setScale(2, java.math.RoundingMode.HALF_UP);
            return new PorPerfil(usuarioId, operacoes, acertos, erros, empates, devolvidas, apostado, pago, poupado,
                    taxasDevolucao, lucro, resultadoRealBrl.setScale(2, java.math.RoundingMode.HALF_UP), sessoes);
        }
    }
}
