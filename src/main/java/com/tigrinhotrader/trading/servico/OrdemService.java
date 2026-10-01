package com.tigrinhotrader.trading.servico;

import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.dominio.ModoJogo;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.OrdemRepository;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import com.tigrinhotrader.trading.estrategia.CalculadoraBarreira;
import com.tigrinhotrader.trading.estrategia.EstrategiaBarreira;
import com.tigrinhotrader.trading.estrategia.RegistroEstrategias;
import com.tigrinhotrader.trading.fabrica.OrdemFactory;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaEvento;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.preco.PrecoCache;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrdemService {

    private static final Logger log = LoggerFactory.getLogger(OrdemService.class);

    private final OrdemRepository repositorio;
    private final OrdemFactory fabrica;
    private final RegistroEstrategias estrategias;
    private final PrecoCache precoCache;
    private final MarketDataClient marketDataClient;
    private final WalletClient walletClient;
    private final OrdemExecutadaPublisher publisher;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public OrdemService(OrdemRepository repositorio, OrdemFactory fabrica, RegistroEstrategias estrategias,
                        PrecoCache precoCache, MarketDataClient marketDataClient, WalletClient walletClient,
                        OrdemExecutadaPublisher publisher, PlatformTransactionManager transactionManager,
                        Clock relogio) {
        this.repositorio = repositorio;
        this.fabrica = fabrica;
        this.estrategias = estrategias;
        this.precoCache = precoCache;
        this.marketDataClient = marketDataClient;
        this.walletClient = walletClient;
        this.publisher = publisher;
        this.transacao = new TransactionTemplate(transactionManager);
        this.relogio = relogio;
    }

    /** Abre uma rodada nova no modo classico ({@link ModoJogo#DIFICIL}). */
    @Transactional
    public Ordem criar(String usuarioId, String simbolo, TipoOrdem tipo, BigDecimal valor, int duracaoSegundos) {
        return criar(usuarioId, simbolo, tipo, ModoJogo.DIFICIL, valor, duracaoSegundos);
    }

    /** Abre uma rodada nova com o preco atual como preco de entrada. */
    @Transactional
    public Ordem criar(String usuarioId, String simbolo, TipoOrdem tipo, ModoJogo modo, BigDecimal valor,
                       int duracaoSegundos) {
        String ativo = simbolo.toUpperCase(Locale.ROOT);
        BigDecimal precoEntrada = precoAtual(ativo);
        exigirSaldo(usuarioId, valor);
        Ordem ordem = fabrica.criar(usuarioId, ativo, tipo, modo, valor, duracaoSegundos, precoEntrada, relogio.instant());
        return repositorio.save(ordem);
    }

    /** Abre uma rodada "sem toque": ganha se o preco nao encostar no alvo ate o fim. */
    @Transactional
    public Ordem criarBarreira(String usuarioId, String simbolo, BigDecimal alvo, BigDecimal valor,
                               int duracaoSegundos) {
        String ativo = simbolo.toUpperCase(Locale.ROOT);
        BigDecimal precoEntrada = precoAtual(ativo);
        exigirSaldo(usuarioId, valor);
        Ordem ordem = fabrica.criarBarreira(usuarioId, ativo, alvo, valor, duracaoSegundos, precoEntrada,
                volatilidade(ativo), relogio.instant());
        return repositorio.save(ordem);
    }

    /** Volatilidade medida do ativo, ou o chute padrao enquanto o historico enche. */
    public double volatilidade(String simbolo) {
        return precoCache.volatilidadePorSegundo(simbolo).orElse(CalculadoraBarreira.VOLATILIDADE_PADRAO);
    }

    /**
     * Chamado a cada preco novo: BARREIRA aberta cujo alvo foi tocado perde na hora.
     *
     * @return quantas rodadas perderam com este preco
     */
    public int verificarBarreiras(String simbolo, BigDecimal preco) {
        Instant agora = relogio.instant();
        int tocadas = 0;
        for (Ordem aberta : repositorio.findBySimboloAndTipoAndStatus(simbolo.toUpperCase(Locale.ROOT),
                TipoOrdem.BARREIRA, StatusOrdem.ABERTA)) {
            if (!EstrategiaBarreira.tocou(aberta.getPrecoEntrada(), aberta.getAlvo(), preco)) {
                continue;
            }
            try {
                if (Boolean.TRUE.equals(transacao.execute(status -> fecharTocada(aberta.getId(), preco, agora)))) {
                    tocadas++;
                }
            } catch (RuntimeException e) {
                log.warn("Falha ao fechar barreira {}, tentando no proximo preco: {}", aberta.getId(), e.getMessage());
            }
        }
        return tocadas;
    }

    private boolean fecharTocada(UUID id, BigDecimal preco, Instant agora) {
        Ordem ordem = repositorio.findById(id).orElse(null);
        if (ordem == null || !ordem.isAberta()) {
            return false;
        }
        ordem.resolver(StatusOrdem.PERDEU, preco, agora);
        repositorio.save(ordem);
        publisher.publicar(OrdemExecutadaEvento.de(ordem));
        log.info("Barreira {} tocada em {} ({}): PERDEU", id, preco, ordem.getSimbolo());
        return true;
    }

    private BigDecimal precoAtual(String ativo) {
        return precoCache.preco(ativo)
                .or(() -> marketDataClient.precoAtual(ativo))
                .orElseThrow(() -> new ServicoIndisponivelException("Sem cotacao disponivel para " + ativo));
    }

    /** Saldo da carteira menos o que ja esta apostado em rodadas abertas. */
    private void exigirSaldo(String usuarioId, BigDecimal valor) {
        BigDecimal disponivel = walletClient.saldo(usuarioId).subtract(repositorio.somarValorEmAberto(usuarioId));
        if (disponivel.compareTo(valor) < 0) {
            throw new RegraNegocioException("Saldo insuficiente: disponivel " + disponivel.max(BigDecimal.ZERO));
        }
    }

    @Transactional(readOnly = true)
    public List<Ordem> listar(String usuarioId) {
        return repositorio.findByUsuarioIdOrderByCriadaEmDesc(usuarioId);
    }

    @Transactional(readOnly = true)
    public Ordem buscar(String usuarioId, UUID id) {
        return repositorio.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Ordem " + id + " nao encontrada"));
    }

    /**
     * Fecha todas as rodadas vencidas usando o preco mais recente do ativo.
     * Cada ordem tem sua propria transacao: uma falha nao trava as outras.
     *
     * @return quantas ordens foram resolvidas
     */
    public int resolverVencidas() {
        Instant agora = relogio.instant();
        List<Ordem> vencidas = repositorio.findByStatusAndExpiraEmLessThanEqual(StatusOrdem.ABERTA, agora);
        int resolvidas = 0;
        for (Ordem vencida : vencidas) {
            try {
                if (Boolean.TRUE.equals(transacao.execute(status -> resolver(vencida.getId(), agora)))) {
                    resolvidas++;
                }
            } catch (RuntimeException e) {
                log.warn("Falha ao resolver ordem {}, tentando no proximo ciclo: {}", vencida.getId(), e.getMessage());
            }
        }
        return resolvidas;
    }

    private boolean resolver(UUID id, Instant agora) {
        Ordem ordem = repositorio.findById(id).orElse(null);
        if (ordem == null || !ordem.isAberta()) {
            return false;
        }
        BigDecimal precoSaida = precoCache.preco(ordem.getSimbolo()).orElse(null);
        if (precoSaida == null) {
            log.debug("Sem preco para {} ainda, ordem {} aguarda", ordem.getSimbolo(), id);
            return false;
        }
        StatusOrdem resultado = estrategias.para(ordem.getTipo()).resolver(ordem, precoSaida);
        ordem.resolver(resultado, precoSaida, agora);
        repositorio.save(ordem);
        publisher.publicar(OrdemExecutadaEvento.de(ordem));
        log.info("Ordem {} ({} {}) resolvida: {} liquido {}", id, ordem.getTipo(), ordem.getSimbolo(),
                resultado, ordem.valorLiquido());
        return true;
    }
}
