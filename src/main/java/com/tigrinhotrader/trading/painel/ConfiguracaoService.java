package com.tigrinhotrader.trading.painel;

import com.tigrinhotrader.trading.estudo.EstudoProperties;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.servico.FlechaProperties;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regras do jogo configuradas pelo painel do super-admin, por cima das do ambiente: taxa de poupanca, taxa de
 * devolucao, entrada livre, niveis e valor minimo das flechas e travas de perda. Ficam no banco (uma linha) e em
 * memoria; mudar pelo painel vale na hora, sem reiniciar.
 */
@Service
public class ConfiguracaoService implements RegrasVigentes {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoService.class);
    static final BigDecimal TAXA_MAXIMA = new BigDecimal("0.5");
    static final int VALOR_MAXIMO = 100_000;

    /** O que o painel envia. Campo nulo volta ao valor do ambiente. */
    public record Alteracao(BigDecimal taxaPoupanca, BigDecimal taxaDevolucao, Boolean entradaLivre,
                            List<Integer> niveis, BigDecimal valorMinimo, Integer maxOperacoesDia,
                            Integer maxAbertas, BigDecimal perdaMaximaDiaBrl) {
    }

    private final ConfiguracaoJogoRepository repositorio;
    private final Configuracao ambiente;
    private final Clock relogio;
    private volatile Configuracao atual;

    public ConfiguracaoService(ConfiguracaoJogoRepository repositorio, FlechaProperties flecha, EstudoProperties estudo,
                               @Value("${flecha.entrada-livre:true}") boolean entradaLivre, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.ambiente = Configuracao.doAmbiente(flecha, estudo, entradaLivre);
        this.atual = ambiente;
    }

    @Override
    public Configuracao atual() {
        return atual;
    }

    public Configuracao doAmbiente() {
        return ambiente;
    }

    /** Na subida: aplica o que o painel ja tinha configurado. */
    @EventListener(ApplicationReadyEvent.class)
    public void carregar() {
        Configuracao c = recarregar();
        if (!c.equals(ambiente)) {
            log.info("Regras do painel em vigor: {}", c);
        }
    }

    public synchronized Configuracao recarregar() {
        aplicar(combinar(repositorio.findById(ConfiguracaoJogo.ID).orElse(null)));
        return atual;
    }

    @Transactional
    public synchronized Configuracao alterar(Alteracao a) {
        Configuracao nova = combinar(a);
        validar(nova);
        ConfiguracaoJogo c = repositorio.findById(ConfiguracaoJogo.ID).orElseGet(ConfiguracaoJogo::nova);
        c.taxaPoupanca = a.taxaPoupanca();
        c.taxaDevolucao = a.taxaDevolucao();
        c.entradaLivre = a.entradaLivre();
        List<Integer> niveis = a.niveis();
        c.nivel1 = niveis == null ? null : niveis.get(0);
        c.nivel2 = niveis == null ? null : niveis.get(1);
        c.nivel3 = niveis == null ? null : niveis.get(2);
        c.valorMinimo = a.valorMinimo();
        c.maxOperacoesDia = a.maxOperacoesDia();
        c.maxAbertas = a.maxAbertas();
        c.perdaMaximaDiaBrl = a.perdaMaximaDiaBrl();
        c.atualizadaEm = relogio.instant();
        repositorio.save(c);
        aplicar(nova);
        log.info("Painel: regras alteradas para {}", nova);
        return nova;
    }

    private void aplicar(Configuracao c) {
        atual = c;
        Campo.definirEntradaLivre(c.entradaLivre());
    }

    private Configuracao combinar(ConfiguracaoJogo c) {
        if (c == null) {
            return ambiente;
        }
        List<Integer> niveis = c.nivel1 == null || c.nivel2 == null || c.nivel3 == null ? null
                : List.of(c.nivel1, c.nivel2, c.nivel3);
        return combinar(new Alteracao(c.taxaPoupanca, c.taxaDevolucao, c.entradaLivre, niveis, c.valorMinimo,
                c.maxOperacoesDia, c.maxAbertas, c.perdaMaximaDiaBrl));
    }

    private Configuracao combinar(Alteracao a) {
        if (a.niveis() != null && a.niveis().size() != 3) {
            throw new RegraNegocioException("Informe os tres niveis das flechas (Nivel 1, 2 e 3)");
        }
        return new Configuracao(
                ou(a.taxaPoupanca(), ambiente.taxaPoupanca()),
                ou(a.taxaDevolucao(), ambiente.taxaDevolucao()),
                a.entradaLivre() == null ? ambiente.entradaLivre() : a.entradaLivre(),
                a.niveis() == null ? ambiente.niveis() : a.niveis(),
                ou(a.valorMinimo(), ambiente.valorMinimo()),
                a.maxOperacoesDia() == null ? ambiente.maxOperacoesDia() : a.maxOperacoesDia(),
                a.maxAbertas() == null ? ambiente.maxAbertas() : a.maxAbertas(),
                ou(a.perdaMaximaDiaBrl(), ambiente.perdaMaximaDiaBrl()));
    }

    private static BigDecimal ou(BigDecimal valor, BigDecimal padrao) {
        return valor == null ? padrao : valor;
    }

    static void validar(Configuracao c) {
        taxa("A taxa de poupanca", c.taxaPoupanca());
        taxa("A taxa de devolucao", c.taxaDevolucao());
        List<Integer> n = c.niveis();
        for (int i = 0; i < n.size(); i++) {
            if (n.get(i) == null || n.get(i) < 1 || n.get(i) > VALOR_MAXIMO) {
                throw new RegraNegocioException("Cada nivel de flecha vai de 1 a " + VALOR_MAXIMO + " moedas");
            }
            if (i > 0 && n.get(i) <= n.get(i - 1)) {
                throw new RegraNegocioException("Os niveis precisam crescer: Nivel 1 < Nivel 2 < Nivel 3");
            }
        }
        BigDecimal minimo = c.valorMinimo();
        if (minimo.compareTo(new BigDecimal("0.01")) < 0 || minimo.stripTrailingZeros().scale() > 2
                || minimo.compareTo(BigDecimal.valueOf(n.get(0))) > 0) {
            throw new RegraNegocioException("O valor minimo da flecha vai de 0,01 ate o Nivel 1, com ate 2 casas");
        }
        if (c.maxOperacoesDia() < 1 || c.maxOperacoesDia() > VALOR_MAXIMO) {
            throw new RegraNegocioException("Operacoes por dia: de 1 a " + VALOR_MAXIMO);
        }
        if (c.maxAbertas() < 1 || c.maxAbertas() > 100) {
            throw new RegraNegocioException("Operacoes abertas ao mesmo tempo: de 1 a 100");
        }
        if (c.perdaMaximaDiaBrl().signum() <= 0 || c.perdaMaximaDiaBrl().compareTo(BigDecimal.valueOf(1_000_000)) > 0) {
            throw new RegraNegocioException("A perda maxima por dia precisa ser maior que zero (ate R$ 1.000.000)");
        }
    }

    private static void taxa(String nome, BigDecimal valor) {
        if (valor.signum() < 0 || valor.compareTo(TAXA_MAXIMA) > 0) {
            throw new RegraNegocioException(nome + " vai de 0% a 50%");
        }
    }
}
