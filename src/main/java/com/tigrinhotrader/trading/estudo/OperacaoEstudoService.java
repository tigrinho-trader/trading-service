package com.tigrinhotrader.trading.estudo;

import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.RegrasFlecha;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.mensageria.SessaoFlechaEncerradaEvento;
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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Estudo da sabedoria das massas: cada flecha de mercado solta (bitcoin, ethereum, ouro ou V100) vira uma operacao no
 * ativo dela, na direcao que o jogador previu, aberta na hora e fechada {@code fechaApos} depois (o tempo de voo da
 * flecha). No modo REAL o dinheiro da operacao e do dono do jogo.
 *
 * <p>Para o jogador cada flecha de mercado e uma aposta do valor que ele escolheu ao comprar a flecha (paga na
 * compra): registrar a operacao gasta uma flecha daquele valor da sessao aberta e abre uma operacao do mesmo tamanho
 * em reais ({@link RegrasFlecha#MOEDAS_POR_REAL} moeda por real). Quando a operacao termina o servidor liquida a
 * aposta com o resultado liquido dela (ver {@link OperacaoEstudo#liquidar()}). Com a sessao ainda aberta as moedas
 * entram nos ganhos dela; com a sessao ja encerrada vao direto para a carteira num ordem.executada proprio da
 * operacao. O placar de acertos (direcao) continua binario.
 *
 * <p>So o ativo configurado ({@code estudo.simbolo}, BTCUSDT) usa a corretora configurada, que pode ser REAL; os
 * outros ativos sao sempre simulados com o preco real.
 *
 * <p>Travas do modo REAL: operacoes por dia, operacoes abertas ao mesmo tempo e perda maxima do dia. Em qualquer
 * modo: uma flecha do ativo na aljava por operacao e um intervalo minimo entre operacoes do jogador.
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
    /** Conflitos de versao seguidos (o jogador mexendo na sessao) antes de deixar a liquidacao para a revisao. */
    static final int MAX_TENTATIVAS_LIQUIDAR = 3;
    private static final List<StatusOperacao> TERMINADAS = List.of(StatusOperacao.FECHADA, StatusOperacao.RECUSADA,
            StatusOperacao.FALHOU);

    /**
     * Placar das apostas fechadas: acertos, erros, empates (preco parado) e a soma do resultado liquido das
     * operacoes, em reais.
     */
    public record Placar(long operacoes, long acertos, long erros, long empates, BigDecimal resultadoLiquidoBrl) {

        static final Placar ZERO = new Placar(0, 0, 0, 0, BigDecimal.ZERO);

        /** Mais uma operacao com o sinal da aposta (+1 acerto, -1 erro, 0 empate) e o resultado liquido dela. */
        Placar com(int sinal, BigDecimal liquidoBrl) {
            return new Placar(operacoes + 1, acertos + (sinal > 0 ? 1 : 0), erros + (sinal < 0 ? 1 : 0),
                    empates + (sinal == 0 ? 1 : 0),
                    liquidoBrl == null ? resultadoLiquidoBrl : resultadoLiquidoBrl.add(liquidoBrl));
        }

        /** acertos x 100 / (acertos + erros), com uma casa; empates ficam de fora. Nulo sem acertos nem erros. */
        public BigDecimal taxaAcertoPercentual() {
            long decididas = acertos + erros;
            if (decididas == 0) {
                return null;
            }
            return BigDecimal.valueOf(acertos * 100).divide(BigDecimal.valueOf(decididas), 1, RoundingMode.HALF_UP);
        }
    }

    /** O placar do jogador em cada ativo do estudo (todos, mesmo zerados) e no total. */
    public record MeusAcertos(Map<AtivoEstudo, Placar> porAtivo, Placar total) {
    }

    /** A operacao e a sessao dela (nula se a sessao nao existe mais), como o jogador ve. */
    public record Registro(OperacaoEstudo operacao, SessaoFlecha sessao) {
    }

    private final OperacaoEstudoRepository operacoes;
    private final SessaoFlechaRepository sessoes;
    /** Corretora configurada (pode ser REAL): so para o ativo de {@code estudo.simbolo}. */
    private final Corretora corretora;
    /** Corretora de opções da Deriv; só é usada pelo ativo V100 quando DERIV_TRADING_MODE=REAL. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CorretoraDerivOptions corretoraDeriv;
    /** Simulacoes dos outros ativos, criadas no primeiro uso. */
    private final Map<String, Corretora> simuladas = new ConcurrentHashMap<>();
    private final Agendador agendador;
    private final EstudoProperties propriedades;
    private final PrecoCache precos;
    private final Clock relogio;
    private final OrdemExecutadaPublisher publisher;
    private final TransactionTemplate transacao;

    // corretoraEstudo: com duas Corretora no contexto (a padrao e a da Deriv), o Spring escolhe pelo nome do bean
    public OperacaoEstudoService(OperacaoEstudoRepository operacoes, SessaoFlechaRepository sessoes,
                                 Corretora corretoraEstudo, Agendador agendador, EstudoProperties propriedades,
                                 PrecoCache precos, Clock relogio, OrdemExecutadaPublisher publisher,
                                 PlatformTransactionManager transactionManager) {
        this.operacoes = operacoes;
        this.sessoes = sessoes;
        this.corretora = corretoraEstudo;
        this.agendador = agendador;
        this.propriedades = propriedades;
        this.precos = precos;
        this.relogio = relogio;
        this.publisher = publisher;
        this.transacao = new TransactionTemplate(transactionManager);
    }

    /**
     * Registra a previsao (sobe ou desce) de uma flecha de mercado que acabou de sair do arco e dispara a abertura.
     * Na mesma transacao gasta a flecha do ativo da sessao aberta do jogador. Devolve a operacao ja salva (ABRINDO,
     * ou RECUSADA e liquidada se uma trava do modo REAL impediu) e a sessao atualizada.
     *
     * @param simboloTexto par do ativo (BTCUSDT, ETHUSDT, PAXGUSDT, 1HZ100V); sem ele, BTCUSDT (clientes antigos)
     * @param valor valor da flecha gasta (o lote da aljava); sem ele, a de menor valor do ativo (clientes antigos)
     */
    public Registro registrar(String usuarioId, UUID sessaoId, String simboloTexto, String direcaoTexto,
                              BigDecimal precoAgora, BigDecimal precoPrevisto, BigDecimal valor) {
        Direcao direcao = Direcao.porNome(direcaoTexto)
                .orElseThrow(() -> new RegraNegocioException("Direcao deve ser ALTA ou QUEDA"));
        AtivoEstudo ativo = simboloTexto == null ? AtivoEstudo.BTC : AtivoEstudo.porSimbolo(simboloTexto)
                .orElseThrow(() -> new RegraNegocioException("Ativo nao suportado: " + simboloTexto));
        OperacaoEstudo salva = transacao.execute(status -> registrarNaSessao(usuarioId, sessaoId, ativo, direcao,
                precoAgora, precoPrevisto, valor));
        UUID id = salva.getId();
        if (salva.aguardaLiquidacao()) {
            liquidar(id);
            salva = operacoes.findById(id).orElse(salva);
        }
        SessaoFlecha sessao = sessoes.findById(sessaoId).orElse(null);
        if (salva.getStatus() == StatusOperacao.ABRINDO) {
            agendador.executar(() -> abrir(id));
        }
        return new Registro(salva, sessao);
    }

    private OperacaoEstudo registrarNaSessao(String usuarioId, UUID sessaoId, AtivoEstudo ativo, Direcao direcao,
                                             BigDecimal precoAgora, BigDecimal precoPrevisto, BigDecimal valor) {
        SessaoFlecha sessao = sessoes.findByIdAndUsuarioId(sessaoId, usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Sessao nao encontrada"));
        if (!sessao.isAberta()) {
            throw new RegraNegocioException("Sessao ja encerrada");
        }
        TipoFlecha flecha = ativo.flecha();
        Campo campo = sessao.getCampo();
        if (!campo.temFlecha(flecha)) {
            // flecha guardada de um ativo que este campo nao tem: fica na aljava e volta para a guarda ao encerrar
            throw new RegraNegocioException("O passaro " + flecha.nome() + " nao aparece no " + campo.nome());
        }
        if (sessao.getRestantes().get(flecha) < 1) {
            throw new RegraNegocioException("Sem flechas " + flecha.nome() + " na aljava");
        }
        if (valor != null && !sessao.temFlechaDeMercado(flecha, valor)) {
            throw new RegraNegocioException("Sem flechas " + flecha.nome() + " de " + valor.toPlainString()
                    + " moedas na aljava");
        }
        Instant agora = relogio.instant();
        Instant limite = agora.minus(propriedades.intervaloPorJogador());
        Optional<OperacaoEstudo> ultima = operacoes.findFirstByUsuarioIdOrderByCriadaEmDesc(usuarioId);
        if (ultima.isPresent() && ultima.get().getCriadaEm().isAfter(limite)) {
            throw new RitmoExcedidoException("Uma operacao por vez: espere "
                    + propriedades.intervaloPorJogador().toMillis() + " ms entre flechas de mercado");
        }
        BigDecimal valorFlecha = sessao.usarFlechaDeMercado(flecha, valor, agora);
        sessoes.save(sessao);
        ModoEstudo modo = corretoraDe(ativo.simbolo()).modo();
        // a operacao tem o tamanho da flecha: valor em moedas / moedas por real
        BigDecimal nocional = valorFlecha.divide(BigDecimal.valueOf(RegrasFlecha.MOEDAS_POR_REAL), 2,
                RoundingMode.HALF_UP);
        OperacaoEstudo operacao = OperacaoEstudo.nova(usuarioId, sessaoId, ativo.simbolo(), direcao,
                TipoPrevisao.BINARIA, modo, nocional, precoAgora, precoPrevisto, agora);
        Optional<String> trava = modo == ModoEstudo.REAL ? travaDoModoReal(agora) : Optional.empty();
        trava.ifPresent(motivo -> operacao.recusar(motivo, agora));
        return operacoes.save(operacao);
    }

    /** A operacao do jogador e a sessao dela (com os ganhos que a aposta ja somou). */
    public Registro buscar(String usuarioId, UUID id) {
        OperacaoEstudo operacao = operacoes.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Operacao nao encontrada"));
        return new Registro(operacao, sessoes.findByIdAndUsuarioId(operacao.getSessaoId(), usuarioId).orElse(null));
    }

    /**
     * Placar das operacoes FECHADAS do proprio jogador por ativo (operacoes antigas sem simbolo contam como
     * BTCUSDT), de todas as sessoes ou so de {@code sessaoId}. Sessao de outro jogador da tudo zerado.
     */
    public MeusAcertos meusAcertos(String usuarioId, UUID sessaoId) {
        List<OperacaoEstudo> fechadas = sessaoId == null
                ? operacoes.findByUsuarioIdAndStatus(usuarioId, StatusOperacao.FECHADA)
                : operacoes.findByUsuarioIdAndSessaoIdAndStatus(usuarioId, sessaoId, StatusOperacao.FECHADA);
        Map<AtivoEstudo, Placar> porAtivo = new EnumMap<>(AtivoEstudo.class);
        for (AtivoEstudo ativo : AtivoEstudo.values()) {
            porAtivo.put(ativo, Placar.ZERO);
        }
        Placar total = Placar.ZERO;
        for (OperacaoEstudo o : fechadas) {
            int sinal = o.sinalAposta();
            BigDecimal liquido = o.getResultadoLiquidoBrl();
            AtivoEstudo.porSimbolo(o.getSimbolo())
                    .ifPresent(ativo -> porAtivo.put(ativo, porAtivo.get(ativo).com(sinal, liquido)));
            total = total.com(sinal, liquido);
        }
        return new MeusAcertos(porAtivo, total);
    }

    public ResumoEstudo resumo() {
        return new ResumoEstudo(corretora.modo(), propriedades.nocionalBrl(), propriedades.apostaBinariaBrl(),
                operacoes.resumir(ModoEstudo.SIMULADO), operacoes.resumir(ModoEstudo.REAL),
                operacoes.resumirPorAtivo());
    }

    /**
     * Onde operar o ativo: a corretora configurada (talvez REAL) so para {@code estudo.simbolo}; qualquer outro
     * ativo e sempre simulado, para dinheiro real nunca sair por um ativo que o dono nao ligou.
     */
    Corretora corretoraDe(String simbolo) {
        if (simbolo.equalsIgnoreCase(AtivoEstudo.V100.simbolo()) && corretoraDeriv != null
                && corretoraDeriv.modo() != ModoEstudo.SIMULADO) {
            return corretoraDeriv;
        }
        if (simbolo.equalsIgnoreCase(propriedades.simbolo())) {
            return corretora;
        }
        return simuladas.computeIfAbsent(simbolo,
                s -> new CorretoraSimulada(s, propriedades.taxaSimulada(), precos));
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
            Execucao entrada = corretoraDe(operacao.getSimbolo()).abrir(operacao.getDirecao(), nocionalUsdt,
                    idCliente(operacao, "a"));
            Instant aberta = relogio.instant();
            operacao.registrarAbertura(entrada, cambio, aberta);
            operacoes.save(operacao);
            agendador.agendar(() -> fechar(id), aberta.plus(propriedades.fechaApos()));
        } catch (RegraNegocioException e) {
            operacao.recusar(e.getMessage(), relogio.instant());
            operacoes.save(operacao);
            liquidar(id);
        } catch (RuntimeException e) {
            LOG.warn("Operacao {} nao abriu: {}", id, e.getMessage());
            operacao.falhar("Nao abriu: " + e.getMessage(), relogio.instant());
            operacoes.save(operacao);
            liquidar(id);
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
            Execucao saida = corretoraDe(operacao.getSimbolo()).fechar(operacao.getDirecao(), entrada,
                    idCliente(operacao, "f" + operacao.getTentativasFechar()));
            ResultadoOperacao resultado = ResultadoOperacao.calcular(operacao.getDirecao(), entrada, saida,
                    operacao.getCambio(), propriedades.apostaBinariaBrl());
            operacao.fechar(saida, resultado, relogio.instant());
            operacoes.save(operacao);
            liquidar(id);
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
                liquidar(id);
            }
        }
    }

    /**
     * Fecha operacoes esquecidas (servico reiniciado com posicao aberta), encerra as que nunca abriram e liquida as
     * apostas que ficaram sem liquidar (fila fora do ar, conflitos seguidos).
     */
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
            liquidar(o.getId());
        }
        for (OperacaoEstudo o : operacoes.findByApostaMoedasIsNotNullAndMoedasPagasIsNullAndStatusIn(TERMINADAS)) {
            liquidar(o.getId());
        }
    }

    /**
     * Liquida a aposta da flecha de mercado de uma operacao terminada, uma vez so (a operacao guarda as moedas
     * pagas, com @Version). Sessao aberta: as moedas entram nos ganhos dela. Sessao encerrada: vao para a carteira
     * num ordem.executada com a referencia da operacao (nada e publicado se nao paga nada). Conflito de versao (o
     * jogador atirando na mesma sessao) tenta de novo; qualquer outra falha fica para a revisao.
     */
    void liquidar(UUID id) {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_LIQUIDAR; tentativa++) {
            try {
                transacao.executeWithoutResult(status -> liquidarNaTransacao(id));
                return;
            } catch (OptimisticLockingFailureException e) {
                LOG.info("Liquidacao da operacao {} em conflito (tentativa {})", id, tentativa);
            } catch (RuntimeException e) {
                LOG.warn("Operacao {} nao liquidou, a revisao tenta de novo: {}", id, e.getMessage());
                return;
            }
        }
    }

    private void liquidarNaTransacao(UUID id) {
        OperacaoEstudo operacao = operacoes.findById(id).orElse(null);
        if (operacao == null || !operacao.aguardaLiquidacao()) {
            return;
        }
        BigDecimal moedas = operacao.liquidar();
        // flush antes de pagar: se outra liquidacao chegou primeiro, o conflito de versao aparece aqui
        operacoes.saveAndFlush(operacao);
        Optional<SessaoFlecha> aberta = sessoes.findById(operacao.getSessaoId()).filter(SessaoFlecha::isAberta);
        if (aberta.isPresent()) {
            // acerto na sessao = acertou a direcao, mesmo que as taxas tenham comido o lucro
            aberta.get().receberApostaDeMercado(moedas, operacao.acertouAposta());
            sessoes.saveAndFlush(aberta.get());
        } else if (moedas.signum() > 0) {
            publisher.publicarSessaoFlecha(pagamento(operacao, relogio.instant()));
        }
    }

    /**
     * Pagamento de uma aposta liquidada depois de a sessao encerrar, no formato que a carteira consome: valorLiquido
     * = moedas pagas (com centavos); resultado compara o pagamento com a aposta (GANHOU/PERDEU/EMPATOU); acertos = 1
     * se acertou a direcao.
     */
    static SessaoFlechaEncerradaEvento pagamento(OperacaoEstudo o, Instant agora) {
        BigDecimal pagas = o.getMoedasPagas();
        String resultado = switch (Integer.signum(o.compararPagamento())) {
            case 1 -> "GANHOU";
            case -1 -> "PERDEU";
            default -> "EMPATOU";
        };
        return new SessaoFlechaEncerradaEvento(o.referenciaPagamento(), o.getUsuarioId(), o.getSimbolo(),
                SessaoFlechaEncerradaEvento.TIPO_FLECHA_MERCADO, o.getApostaMoedas(),
                resultado, pagas, agora, pagas, o.acertouAposta() ? 1 : 0);
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
