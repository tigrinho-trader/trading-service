package com.tigrinhotrader.trading.flecha.servico;

import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.AljavaGuardada;
import com.tigrinhotrader.trading.flecha.dominio.AljavaGuardadaRepository;
import com.tigrinhotrader.trading.flecha.dominio.LoteFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.dominio.StatusSessao;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.RegrasFlecha;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.mensageria.SessaoFlechaEncerradaEvento;
import com.tigrinhotrader.trading.servico.ConflitoException;
import com.tigrinhotrader.trading.servico.PedidoInvalidoException;
import com.tigrinhotrader.trading.servico.RecursoNaoEncontradoException;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import com.tigrinhotrader.trading.servico.RitmoExcedidoException;
import com.tigrinhotrader.trading.servico.SaldoDisponivel;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sessoes do Flecha Certeira: o servidor cobra entrada + flechas, confere cada tiro (plausibilidade) e manda o
 * resultado para a carteira ao encerrar. Passaros nao pagam moedas: so as apostas das flechas de mercado rendem.
 * As flechas de mercado tem o valor que o jogador escolhe (niveis de {@link RegrasFlecha#NIVEIS_FLECHA} ou qualquer
 * valor a partir de 1 moeda, com ate 2 casas) e ficam em lotes por tipo e valor. As comuns que sobram ficam
 * guardadas no campo onde foram compradas; as de mercado que sobram voltam em moedas ao encerrar, menos a taxa de
 * devolucao ({@code flecha.taxa-devolucao}).
 */
@Service
public class SessaoFlechaService {

    private static final Logger log = LoggerFactory.getLogger(SessaoFlechaService.class);

    /** Ids das flechas que sairam do jogo (as guardadas sao reembolsadas por {@link #reembolsarFlechasAntigas()}). */
    static final Set<String> FLECHAS_REMOVIDAS = Set.of("iron", "gold", "diamond");
    static final String FLECHA_REMOVIDA = "Flechas de ferro, ouro e diamante foram removidas do jogo: use madeira "
            + "(gratis) ou as flechas de mercado";

    /** Resultado de um tiro: moedas desta flecha e a sessao atualizada. */
    public record ResultadoTiro(int ganhosTiro, SessaoFlecha sessao) {
    }

    /** Flechas guardadas do jogador: as comuns de cada campo e as de mercado, que valem em todos. */
    public record Guardadas(Map<Campo, Aljava> porCampo, Aljava mercado) {
    }

    /** Lote de flechas de mercado pedido: {@code {"flecha":"btc","valor":10,"quantidade":3}}. */
    public record PedidoLote(String flecha, BigDecimal valor, Integer quantidade) {
    }

    private final SessaoFlechaRepository repositorio;
    private final AljavaGuardadaRepository aljavas;
    private final SaldoDisponivel saldoDisponivel;
    private final OrdemExecutadaPublisher publisher;
    private final FlechaProperties propriedades;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public SessaoFlechaService(SessaoFlechaRepository repositorio, AljavaGuardadaRepository aljavas,
                               SaldoDisponivel saldoDisponivel, OrdemExecutadaPublisher publisher,
                               FlechaProperties propriedades, PlatformTransactionManager transactionManager,
                               Clock relogio) {
        this.repositorio = repositorio;
        this.aljavas = aljavas;
        this.saldoDisponivel = saldoDisponivel;
        this.publisher = publisher;
        this.propriedades = propriedades;
        this.transacao = new TransactionTemplate(transactionManager);
        this.relogio = relogio;
    }

    /** Abre uma sessao so com o mapa de flechas (sem lotes de mercado com valor). */
    @Transactional
    public SessaoFlecha criar(String usuarioId, String slugCampo, Map<String, Integer> flechas) {
        return criar(usuarioId, slugCampo, flechas, List.of());
    }

    /**
     * Abre uma sessao cobrando entrada + flechas novas, se o saldo disponivel cobrir: a madeira de {@code flechas} e
     * gratis e cada lote de {@code mercado} custa valor x quantidade (so dos ativos do campo; ferro, ouro e diamante
     * sairam do jogo). Flechas de mercado mandadas em {@code flechas} (clientes antigos) viram lotes do valor padrao
     * ({@link RegrasFlecha#VALOR_FLECHA_PADRAO}). As flechas guardadas do campo e as de mercado guardadas (do preco
     * fixo antigo, que entram como lotes de {@link RegrasFlecha#VALOR_FLECHA_ANTIGA}) entram na aljava sem custo; da
     * para abrir sem comprar nada se houver ao menos uma guardada. A aljava comporta {@link RegrasFlecha#MAX_FLECHAS}:
     * com mais guardadas que isso a sessao abre, mas sem comprar.
     */
    @Transactional
    public SessaoFlecha criar(String usuarioId, String slugCampo, Map<String, Integer> flechas,
                              List<PedidoLote> mercado) {
        Campo campo = Campo.porSlug(slugCampo)
                .orElseThrow(() -> new RegraNegocioException("Campo desconhecido: " + slugCampo));
        Aljava pedido = pedido(campo, flechas);
        Aljava comuns = pedido.comuns();
        List<LoteFlecha> lotes = new ArrayList<>(SessaoFlecha.lotesPadrao(pedido.deMercado()));
        lotes.addAll(lotesPedidos(campo, mercado == null ? List.of() : mercado));
        long novas = comuns.total() + lotes.stream().mapToLong(LoteFlecha::quantidade).sum();
        if (novas > RegrasFlecha.MAX_FLECHAS) {
            throw escolhaDeFlechas();
        }
        if (repositorio.findFirstByUsuarioIdAndStatus(usuarioId, StatusSessao.ABERTA).isPresent()) {
            throw jaTemAberta();
        }
        Optional<AljavaGuardada> doCampo = aljavas.findByUsuarioIdAndEscopo(usuarioId, campo.slug());
        Optional<AljavaGuardada> deMercado = aljavas.findByUsuarioIdAndEscopo(usuarioId, AljavaGuardada.MERCADO);
        int quantasGuardadas = doCampo.map(g -> g.getFlechas().comuns().total()).orElse(0)
                + deMercado.map(g -> g.getFlechas().deMercado().total()).orElse(0);
        if (novas + quantasGuardadas < 1) {
            throw escolhaDeFlechas();
        }
        if (novas > 0 && novas + quantasGuardadas > RegrasFlecha.MAX_FLECHAS) {
            throw new RegraNegocioException("A aljava comporta no maximo " + RegrasFlecha.MAX_FLECHAS
                    + " flechas e voce ja tem " + quantasGuardadas + " guardadas");
        }
        exigirSaldo(usuarioId, SessaoFlecha.custoAbertura(campo, comuns, lotes));
        Instant agora = relogio.instant();
        Aljava trazidas = Aljava.soma(
                doCampo.map(g -> retirar(g, agora).comuns()).orElseGet(Aljava::vazia),
                deMercado.map(g -> retirar(g, agora).deMercado()).orElseGet(Aljava::vazia));
        SessaoFlecha sessao = new SessaoFlecha(UUID.randomUUID(), usuarioId, campo, comuns, lotes, trazidas, agora);
        try {
            return repositorio.saveAndFlush(sessao);
        } catch (DataIntegrityViolationException e) {
            // outro pedido simultaneo abriu a sessao primeiro (UNIQUE em usuarioAberta)
            throw jaTemAberta();
        }
    }

    /**
     * Lotes de mercado pedidos ao abrir: so flechas de mercado de ativos do campo, quantidade 0 ou mais (0 e
     * ignorado) e valor valido ({@link #valorFlecha}).
     */
    private static List<LoteFlecha> lotesPedidos(Campo campo, List<PedidoLote> mercado) {
        List<LoteFlecha> lotes = new ArrayList<>();
        for (PedidoLote lote : mercado) {
            TipoFlecha tipo = tipoFlecha(lote.flecha());
            if (!tipo.deMercado()) {
                throw new RegraNegocioException("Flechas " + tipo.nome() + " nao tem valor: peca-as em flechas");
            }
            if (lote.quantidade() == null || lote.quantidade() < 0) {
                throw new RegraNegocioException("Quantidade invalida de flechas " + lote.flecha());
            }
            if (lote.quantidade() > 0) {
                exigirNoCampo(campo, tipo);
                lotes.add(new LoteFlecha(tipo, valorFlecha(tipo, lote.valor()), lote.quantidade()));
            }
        }
        return lotes;
    }

    /**
     * Valor de uma flecha de mercado escolhido pelo jogador: obrigatorio, pelo menos
     * {@link RegrasFlecha#VALOR_MINIMO_FLECHA} moeda e com ate {@link RegrasFlecha#CASAS_VALOR_FLECHA} casas, sem
     * teto (so o saldo limita). Devolve com 2 casas; invalido e 422.
     */
    static BigDecimal valorFlecha(TipoFlecha tipo, BigDecimal valor) {
        if (valor == null) {
            throw new RegraNegocioException("Informe o valor das flechas " + tipo.nome());
        }
        if (valor.compareTo(RegrasFlecha.VALOR_MINIMO_FLECHA) < 0
                || valor.stripTrailingZeros().scale() > RegrasFlecha.CASAS_VALOR_FLECHA) {
            throw new RegraNegocioException("O valor de uma flecha " + tipo.nome() + " e de pelo menos "
                    + RegrasFlecha.VALOR_MINIMO_FLECHA + " moeda, com ate " + RegrasFlecha.CASAS_VALOR_FLECHA
                    + " casas: " + valor.toPlainString());
        }
        return valor.setScale(RegrasFlecha.CASAS_VALOR_FLECHA);
    }

    private Aljava retirar(AljavaGuardada guardada, Instant agora) {
        Aljava retiradas = guardada.retirar(agora);
        aljavas.save(guardada);
        return retiradas;
    }

    private static RegraNegocioException escolhaDeFlechas() {
        return new RegraNegocioException("Escolha de 1 a " + RegrasFlecha.MAX_FLECHAS + " flechas");
    }

    private static ConflitoException jaTemAberta() {
        return new ConflitoException("Ja existe uma sessao aberta do Flecha Certeira: encerre antes de abrir outra");
    }

    /**
     * Flechas novas pedidas ao abrir: madeira e, as de mercado, so as dos ativos do campo. Clientes antigos que
     * mandam ferro, ouro ou diamante com 0 passam; com mais que 0 o pedido e invalido (400).
     */
    private static Aljava pedido(Campo campo, Map<String, Integer> flechas) {
        Map<TipoFlecha, Integer> quantidades = new EnumMap<>(TipoFlecha.class);
        flechas.forEach((id, quantidade) -> {
            if (FLECHAS_REMOVIDAS.contains(id) && (quantidade == null || quantidade <= 0)) {
                return;
            }
            TipoFlecha tipo = tipoFlecha(id);
            if (quantidade == null || quantidade < 0) {
                throw new RegraNegocioException("Quantidade invalida de flechas " + id);
            }
            if (quantidade > 0) {
                exigirNoCampo(campo, tipo);
            }
            quantidades.put(tipo, quantidade);
        });
        return Aljava.de(quantidades);
    }

    /** Recusa (422) flecha de mercado de um ativo que o campo nao tem. */
    private static void exigirNoCampo(Campo campo, TipoFlecha tipo) {
        if (!campo.temFlecha(tipo)) {
            throw new RegraNegocioException("Flechas " + tipo.nome() + " nao sao vendidas no " + campo.nome());
        }
    }

    /** So consulta a carteira se ha algo a pagar: flecha de madeira e gratis. */
    private void exigirSaldo(String usuarioId, BigDecimal valor) {
        if (valor.signum() > 0) {
            saldoDisponivel.exigir(usuarioId, valor);
        }
    }

    /** Tipo de flecha pelo id: ferro, ouro e diamante sairam do jogo (400); id desconhecido (ou nulo) e 422. */
    private static TipoFlecha tipoFlecha(String id) {
        if (id != null && FLECHAS_REMOVIDAS.contains(id)) {
            throw new PedidoInvalidoException(FLECHA_REMOVIDA);
        }
        return TipoFlecha.porId(id).orElseThrow(() -> new RegraNegocioException("Flecha desconhecida: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<SessaoFlecha> atual(String usuarioId) {
        return repositorio.findFirstByUsuarioIdAndStatus(usuarioId, StatusSessao.ABERTA);
    }

    /** Flechas guardadas do jogador (fora da sessao aberta), com todos os campos. */
    @Transactional(readOnly = true)
    public Guardadas guardadas(String usuarioId) {
        Map<Campo, Aljava> porCampo = new EnumMap<>(Campo.class);
        for (Campo campo : Campo.values()) {
            porCampo.put(campo, Aljava.vazia());
        }
        Aljava mercado = Aljava.vazia();
        for (AljavaGuardada guardada : aljavas.findByUsuarioId(usuarioId)) {
            if (AljavaGuardada.MERCADO.equals(guardada.getEscopo())) {
                mercado = Aljava.soma(mercado, guardada.getFlechas().deMercado());
            } else {
                Campo.porSlug(guardada.getEscopo())
                        .ifPresent(campo -> porCampo.merge(campo, guardada.getFlechas().comuns(), Aljava::soma));
            }
        }
        return new Guardadas(porCampo, mercado);
    }

    /**
     * Registra um tiro: o cliente diz quais passaros a flecha atravessou (na ordem). O servidor so confere a
     * plausibilidade: tem a flecha, combo ate 5, passaros que existem no campo (os de mercado so nos campos com o
     * ativo) e ritmo minimo entre tiros. Passaros nao pagam moedas: o tiro so conta acertos e rende 0.
     */
    @Transactional
    public ResultadoTiro disparar(String usuarioId, UUID id, String flecha, List<String> acertos) {
        SessaoFlecha sessao = buscarAberta(usuarioId, id);
        TipoFlecha tipo = tipoFlecha(flecha);
        if (tipo.deMercado()) {
            throw new RegraNegocioException("Flechas de mercado sao registradas pelo estudo");
        }
        if (sessao.getRestantes().get(tipo) <= 0) {
            throw new RegraNegocioException("Sem flechas de " + tipo.nome() + " na aljava");
        }
        List<String> ids = acertos == null ? List.of() : acertos;
        if (ids.size() > RegrasFlecha.MAX_COMBO) {
            throw new RegraNegocioException("Uma flecha acerta no maximo " + RegrasFlecha.MAX_COMBO + " passaros");
        }
        Campo campo = sessao.getCampo();
        List<Passaro> atingidos = new ArrayList<>();
        for (String idPassaro : ids) {
            Passaro passaro = Passaro.porId(idPassaro)
                    .orElseThrow(() -> new RegraNegocioException("Passaro desconhecido: " + idPassaro));
            if (!campo.temNoPool(passaro)) {
                throw new RegraNegocioException("Passaro " + idPassaro + " nao aparece no " + campo.nome());
            }
            atingidos.add(passaro);
        }
        Instant agora = relogio.instant();
        Instant ultimo = sessao.getUltimoTiroEm();
        if (ultimo != null && Duration.between(ultimo, agora).compareTo(propriedades.intervaloMinimoTiro()) < 0) {
            throw new RitmoExcedidoException("Tiros rapidos demais: espere "
                    + propriedades.intervaloMinimoTiro().toMillis() + " ms entre flechas");
        }
        int ganhos = sessao.registrarTiro(tipo, atingidos, agora);
        return new ResultadoTiro(ganhos, repositorio.save(sessao));
    }

    /**
     * Compra mais flechas no meio da sessao (1..20 por vez, aljava ate 40), se o saldo cobrir: madeira (gratis) ou
     * as de mercado dos ativos do campo, que pedem o {@code valor} de cada flecha (custam valor x quantidade e entram
     * no lote desse valor). Na madeira o valor e ignorado.
     */
    @Transactional
    public SessaoFlecha comprar(String usuarioId, UUID id, String flecha, int quantidade, BigDecimal valor) {
        SessaoFlecha sessao = buscarAberta(usuarioId, id);
        TipoFlecha tipo = tipoFlecha(flecha);
        exigirNoCampo(sessao.getCampo(), tipo);
        if (quantidade < 1 || quantidade > RegrasFlecha.MAX_COMPRA) {
            throw new RegraNegocioException("Compre de 1 a " + RegrasFlecha.MAX_COMPRA + " flechas por vez");
        }
        if (sessao.getRestantes().total() + quantidade > RegrasFlecha.MAX_FLECHAS) {
            throw new RegraNegocioException("A aljava comporta no maximo " + RegrasFlecha.MAX_FLECHAS + " flechas");
        }
        BigDecimal valorFlecha = tipo.deMercado() ? valorFlecha(tipo, valor) : null;
        BigDecimal extra = (valorFlecha == null ? sessao.getCampo().custoFlecha(tipo) : valorFlecha)
                .multiply(BigDecimal.valueOf(quantidade));
        exigirSaldo(usuarioId, extra);
        sessao.comprar(tipo, quantidade, valorFlecha, relogio.instant());
        return repositorio.save(sessao);
    }

    /** Encerra e manda o resultado para a carteira. Idempotente: sessao ja encerrada so e devolvida. */
    @Transactional
    public SessaoFlecha encerrar(String usuarioId, UUID id) {
        SessaoFlecha sessao = buscar(usuarioId, id);
        if (!sessao.isAberta()) {
            return sessao;
        }
        return fechar(sessao, relogio.instant());
    }

    /**
     * Encerra as sessoes abertas sem atividade ha mais de {@code flecha.expira-apos}. Cada sessao tem sua propria
     * transacao: uma falha (fila fora do ar, conflito) nao trava as outras e e tentada no proximo ciclo.
     *
     * @return quantas sessoes foram encerradas
     */
    public int encerrarOciosas() {
        Instant agora = relogio.instant();
        Instant limite = agora.minus(propriedades.expiraApos());
        int encerradas = 0;
        for (SessaoFlecha ociosa : repositorio.findByStatusAndUltimaAtividadeEmBefore(StatusSessao.ABERTA, limite)) {
            try {
                if (Boolean.TRUE.equals(transacao.execute(status -> encerrarSeOciosa(ociosa.getId(), agora, limite)))) {
                    encerradas++;
                }
            } catch (RuntimeException e) {
                log.warn("Falha ao encerrar sessao {}, tentando no proximo ciclo: {}", ociosa.getId(), e.getMessage());
            }
        }
        return encerradas;
    }

    /**
     * Devolve pela carteira o que o jogador pagou pelas flechas antigas (ferro, ouro, diamante) que ainda tinha
     * guardadas quando elas sairam do jogo: preco base do campo x 2, 4 ou 8 por flecha. Cada aljava tem sua propria
     * transacao: zera as antigas, grava (com flush) e publica um ordem.executada FLECHA_REEMBOLSO; se a fila falhar,
     * a transacao volta e o proximo ciclo tenta de novo com a mesma referencia (a carteira e idempotente por ordemId).
     *
     * @return quantas aljavas foram reembolsadas
     */
    public int reembolsarFlechasAntigas() {
        int reembolsadas = 0;
        for (AljavaGuardada guardada : aljavas.findComFlechasAntigas()) {
            try {
                if (Boolean.TRUE.equals(transacao.execute(status -> reembolsar(guardada.getId())))) {
                    reembolsadas++;
                }
            } catch (RuntimeException e) {
                log.warn("Falha ao reembolsar as flechas antigas da aljava {}, tentando no proximo ciclo: {}",
                        guardada.getId(), e.getMessage());
            }
        }
        return reembolsadas;
    }

    private boolean reembolsar(UUID id) {
        AljavaGuardada guardada = aljavas.findById(id).orElse(null);
        if (guardada == null || guardada.getFlechas().antigas() == 0) {
            return false;
        }
        Instant agora = relogio.instant();
        UUID referencia = guardada.referenciaReembolso();
        // escopo sem campo conhecido (nao deveria ter antigas): so tira, sem valor para devolver
        BigDecimal valor = Campo.porSlug(guardada.getEscopo()).map(c -> guardada.getFlechas().valorAntigas(c))
                .orElse(BigDecimal.ZERO);
        guardada.zerarAntigas(agora);
        aljavas.saveAndFlush(guardada);
        if (valor.signum() > 0) {
            publisher.publicarSessaoFlecha(new SessaoFlechaEncerradaEvento(referencia, guardada.getUsuarioId(),
                    guardada.getEscopo(), SessaoFlechaEncerradaEvento.TIPO_REEMBOLSO, valor, "GANHOU", valor, agora,
                    valor, 0));
        }
        log.info("Flechas antigas da aljava {} reembolsadas: {} moedas", id, valor);
        return true;
    }

    private boolean encerrarSeOciosa(UUID id, Instant agora, Instant limite) {
        SessaoFlecha sessao = repositorio.findById(id).orElse(null);
        if (sessao == null || !sessao.isAberta() || !sessao.getUltimaAtividadeEm().isBefore(limite)) {
            return false;
        }
        fechar(sessao, agora);
        log.info("Sessao {} do Flecha Certeira encerrada por inatividade: {} liquido {}", id,
                sessao.resultadoFinal(), sessao.resultado());
        return true;
    }

    /**
     * Guarda as flechas comuns que sobraram, devolve as de mercado (valor x quantidade menos a taxa de devolucao,
     * somado ao resultado), grava (com flush, para um conflito de versao aparecer antes de avisar a carteira) e
     * publica o resultado. Se a fila falhar, a transacao volta e a sessao continua ABERTA, com as flechas.
     */
    private SessaoFlecha fechar(SessaoFlecha sessao, Instant agora) {
        Aljava sobra = sessao.encerrar(agora, propriedades.taxaDevolucao());
        guardar(sessao.getUsuarioId(), sessao.getCampo().slug(), sobra, agora);
        SessaoFlecha salva = repositorio.saveAndFlush(sessao);
        try {
            publisher.publicarSessaoFlecha(evento(salva));
        } catch (AmqpException e) {
            throw new ServicoIndisponivelException("Fila indisponivel, tente encerrar de novo: " + e.getMessage());
        }
        return salva;
    }

    private void guardar(String usuarioId, String escopo, Aljava flechas, Instant agora) {
        if (flechas.total() == 0 && flechas.antigas() == 0) {
            return;
        }
        AljavaGuardada guardada = aljavas.findByUsuarioIdAndEscopo(usuarioId, escopo)
                .orElseGet(() -> new AljavaGuardada(usuarioId, escopo, agora));
        guardada.guardar(flechas, agora);
        aljavas.save(guardada);
    }

    static SessaoFlechaEncerradaEvento evento(SessaoFlecha s) {
        return new SessaoFlechaEncerradaEvento(s.getId(), s.getUsuarioId(), s.getCampo().slug(),
                SessaoFlechaEncerradaEvento.TIPO, s.getCusto(), s.resultadoFinal(), s.resultado(), s.getEncerradaEm(),
                s.getGanhos(), s.getAcertos());
    }

    private SessaoFlecha buscar(String usuarioId, UUID id) {
        return repositorio.findByIdAndUsuarioId(id, usuarioId)
                .orElseThrow(() -> new RecursoNaoEncontradoException("Sessao " + id + " nao encontrada"));
    }

    private SessaoFlecha buscarAberta(String usuarioId, UUID id) {
        SessaoFlecha sessao = buscar(usuarioId, id);
        if (!sessao.isAberta()) {
            throw new ConflitoException("Sessao " + id + " ja foi encerrada");
        }
        return sessao;
    }
}
