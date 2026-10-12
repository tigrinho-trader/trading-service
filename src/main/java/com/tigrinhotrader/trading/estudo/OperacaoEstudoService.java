package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.preco.PrecoCache;
import com.tigrinhotrader.trading.servico.RecursoNaoEncontradoException;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import com.tigrinhotrader.trading.servico.RitmoExcedidoException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Estudo da sabedoria das massas: cada flecha bitcoin solta vira uma operacao na direcao que o jogador previu,
 * aberta na hora e fechada {@code fechaApos} depois (o tempo de voo da flecha). O jogador nao paga nada: no modo
 * REAL o dinheiro e do dono do jogo.
 *
 * <p>Travas do modo REAL: operacoes por dia, operacoes abertas ao mesmo tempo e perda maxima do dia. Em qualquer
 * modo: uma operacao por flecha bitcoin comprada na sessao e um intervalo minimo entre operacoes do jogador.
 */
@Service
public class OperacaoEstudoService {

    private static final Logger LOG = LoggerFactory.getLogger(OperacaoEstudoService.class);

    static final int MAX_TENTATIVAS_FECHAR = 3;
    static final Duration ESPERA_NOVA_TENTATIVA = Duration.ofSeconds(2);
    /** Operacao ABERTA alem do horario de fechar mais esta folga: a revisao fecha. */
    static final Duration FOLGA_REVISAO = Duration.ofSeconds(20);
    /** Operacao que nao abriu nesse tempo (servico reiniciado no meio): a revisao marca como falha. */
    static final Duration LIMITE_ABRINDO = Duration.ofSeconds(60);

    private final OperacaoEstudoRepository operacoes;
    private final SessaoFlechaRepository sessoes;
    private final Corretora corretora;
    private final Agendador agendador;
    private final EstudoProperties propriedades;
    private final PrecoCache precos;
    private final Clock relogio;

    public OperacaoEstudoService(OperacaoEstudoRepository operacoes, SessaoFlechaRepository sessoes,
                                 Corretora corretora, Agendador agendador, EstudoProperties propriedades,
                                 PrecoCache precos, Clock relogio) {
        this.operacoes = operacoes;
        this.sessoes = sessoes;
        this.corretora = corretora;
        this.agendador = agendador;
        this.propriedades = propriedades;
        this.precos = precos;
        this.relogio = relogio;
    }

    /**
     * Registra a previsao de uma flecha bitcoin que acabou de sair do arco e dispara a abertura. Devolve a operacao
     * ja salva (ABRINDO, ou RECUSADA se uma trava do modo REAL impediu).
     */
    public OperacaoEstudo registrar(String usuarioId, UUID sessaoId, String direcaoTexto, BigDecimal precoAgora,
                                    BigDecimal precoPrevisto) {
        Direcao direcao = Direcao.porNome(direcaoTexto)
                .orElseThrow(() -> new RegraNegocioException("Direcao deve ser ALTA ou QUEDA"));
        SessaoFlecha sessao = sessoes.findByIdAndUsuarioId(sessaoId, usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Sessao nao encontrada"));
        if (!sessao.isAberta()) {
            throw new RegraNegocioException("Sessao ja encerrada");
        }
        if (operacoes.countBySessaoId(sessaoId) >= sessao.getCompradas().get(TipoFlecha.BTC)) {
            throw new RegraNegocioException("Cada flecha bitcoin comprada vale uma operacao");
        }
        Instant agora = relogio.instant();
        Instant limite = agora.minus(propriedades.intervaloPorJogador());
        Optional<OperacaoEstudo> ultima = operacoes.findFirstByUsuarioIdOrderByCriadaEmDesc(usuarioId);
        if (ultima.isPresent() && ultima.get().getCriadaEm().isAfter(limite)) {
            throw new RitmoExcedidoException("Uma operacao por vez: espere "
                    + propriedades.intervaloPorJogador().toMillis() + " ms entre flechas bitcoin");
        }
        OperacaoEstudo operacao = OperacaoEstudo.nova(usuarioId, sessaoId, direcao, corretora.modo(),
                propriedades.nocionalBrl(), precoAgora, precoPrevisto, agora);
        Optional<String> trava = corretora.modo() == ModoEstudo.REAL ? travaDoModoReal(agora) : Optional.empty();
        if (trava.isPresent()) {
            operacao.recusar(trava.get(), agora);
            return operacoes.save(operacao);
        }
        OperacaoEstudo salva = operacoes.save(operacao);
        UUID id = salva.getId();
        agendador.executar(() -> abrir(id));
        return salva;
    }

    public OperacaoEstudo buscar(String usuarioId, UUID id) {
        return operacoes.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Operacao nao encontrada"));
    }

    public ResumoEstudo resumo() {
        return new ResumoEstudo(corretora.modo(), propriedades.nocionalBrl(),
                operacoes.resumir(ModoEstudo.SIMULADO), operacoes.resumir(ModoEstudo.REAL));
    }

    private Optional<String> travaDoModoReal(Instant agora) {
        Instant inicioDoDia = agora.truncatedTo(ChronoUnit.DAYS);
        if (operacoes.countByModoAndStatusNotAndCriadaEmGreaterThanEqual(ModoEstudo.REAL, StatusOperacao.RECUSADA,
                inicioDoDia) >= propriedades.maxOperacoesDia()) {
            return Optional.of("Limite de operacoes do dia atingido");
        }
        if (operacoes.countByModoAndStatusIn(ModoEstudo.REAL, List.of(StatusOperacao.ABRINDO, StatusOperacao.ABERTA))
                >= propriedades.maxAbertas()) {
            return Optional.of("Muitas operacoes abertas ao mesmo tempo");
        }
        BigDecimal resultadoDoDia = operacoes.somarResultadoDesde(ModoEstudo.REAL, inicioDoDia);
        if (resultadoDoDia.compareTo(propriedades.perdaMaximaDiaBrl().negate()) <= 0) {
            return Optional.of("Perda maxima do dia atingida");
        }
        return Optional.empty();
    }

    /** Abre a posicao e agenda o fechamento. Chamado fora da requisicao. */
    void abrir(UUID id) {
        OperacaoEstudo operacao = operacoes.findById(id).orElse(null);
        if (operacao == null || operacao.getStatus() != StatusOperacao.ABRINDO) {
            return;
        }
        try {
            BigDecimal cambio = cambio();
            BigDecimal nocionalUsdt = operacao.getNocionalBrl().divide(cambio, 8, RoundingMode.HALF_UP);
            Execucao entrada = corretora.abrir(operacao.getDirecao(), nocionalUsdt, idCliente(operacao, "a"));
            Instant aberta = relogio.instant();
            operacao.registrarAbertura(entrada, cambio, aberta);
            operacoes.save(operacao);
            agendador.agendar(() -> fechar(id), aberta.plus(propriedades.fechaApos()));
        } catch (RegraNegocioException e) {
            operacao.recusar(e.getMessage(), relogio.instant());
            operacoes.save(operacao);
        } catch (RuntimeException e) {
            LOG.warn("Operacao {} nao abriu: {}", id, e.getMessage());
            operacao.falhar("Nao abriu: " + e.getMessage(), relogio.instant());
            operacoes.save(operacao);
        }
    }

    /** Fecha a posicao e calcula o resultado; tenta de novo algumas vezes antes de desistir. */
    void fechar(UUID id) {
        OperacaoEstudo operacao = operacoes.findById(id).orElse(null);
        if (operacao == null || operacao.getStatus() != StatusOperacao.ABERTA) {
            return;
        }
        Execucao entrada = operacao.abertura();
        try {
            Execucao saida = corretora.fechar(operacao.getDirecao(), entrada,
                    idCliente(operacao, "f" + operacao.getTentativasFechar()));
            ResultadoOperacao resultado = ResultadoOperacao.calcular(operacao.getDirecao(), entrada, saida,
                    operacao.getCambio());
            operacao.fechar(saida, resultado, relogio.instant());
            operacoes.save(operacao);
        } catch (RuntimeException e) {
            operacao.contarTentativaFechar();
            if (operacao.getTentativasFechar() < MAX_TENTATIVAS_FECHAR) {
                LOG.warn("Operacao {} nao fechou (tentativa {}): {}", id, operacao.getTentativasFechar(), e.getMessage());
                operacoes.save(operacao);
                agendador.agendar(() -> fechar(id), relogio.instant().plus(ESPERA_NOVA_TENTATIVA));
            } else {
                LOG.error("Operacao {} NAO FECHOU depois de {} tentativas: confira a posicao na corretora. {}",
                        id, MAX_TENTATIVAS_FECHAR, e.getMessage());
                operacao.falhar("Nao fechou: " + e.getMessage(), relogio.instant());
                operacoes.save(operacao);
            }
        }
    }

    /** Fecha operacoes esquecidas (servico reiniciado com posicao aberta) e encerra as que nunca abriram. */
    @Scheduled(fixedDelayString = "${estudo.revisao-ms:30000}", initialDelayString = "${estudo.revisao-ms:30000}")
    public void revisarPendentes() {
        if (!propriedades.revisaoHabilitada()) {
            return;
        }
        Instant agora = relogio.instant();
        for (OperacaoEstudo o : operacoes.findByStatusAndAbertaEmBefore(StatusOperacao.ABERTA,
                agora.minus(propriedades.fechaApos()).minus(FOLGA_REVISAO))) {
            fechar(o.getId());
        }
        for (OperacaoEstudo o : operacoes.findByStatusAndCriadaEmBefore(StatusOperacao.ABRINDO,
                agora.minus(LIMITE_ABRINDO))) {
            o.falhar("Nao abriu a tempo (servico reiniciado?)", agora);
            operacoes.save(o);
        }
    }

    /** Reais por USDT: cotacao ao vivo de USDTBRL se houver, senao o valor de reserva configurado. */
    BigDecimal cambio() {
        return precos.preco("USDTBRL").filter(p -> p.signum() > 0).orElse(propriedades.cambioReserva());
    }

    /** Id da ordem na corretora (ate 36 caracteres): idempotente por operacao e por ponta. */
    static String idCliente(OperacaoEstudo operacao, String ponta) {
        return "est" + operacao.getId().toString().replace("-", "").substring(0, 24) + ponta;
    }
}
