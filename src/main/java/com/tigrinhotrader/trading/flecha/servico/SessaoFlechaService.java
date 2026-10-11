package com.tigrinhotrader.trading.flecha.servico;

import com.tigrinhotrader.trading.flecha.dominio.Aljava;
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
 * resultado para a carteira ao encerrar.
 */
@Service
public class SessaoFlechaService {

    private static final Logger log = LoggerFactory.getLogger(SessaoFlechaService.class);

    /** Resultado de um tiro: moedas desta flecha e a sessao atualizada. */
    public record ResultadoTiro(int ganhosTiro, SessaoFlecha sessao) {
    }

    private final SessaoFlechaRepository repositorio;
    private final SaldoDisponivel saldoDisponivel;
    private final OrdemExecutadaPublisher publisher;
    private final FlechaProperties propriedades;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public SessaoFlechaService(SessaoFlechaRepository repositorio, SaldoDisponivel saldoDisponivel,
                               OrdemExecutadaPublisher publisher, FlechaProperties propriedades,
                               PlatformTransactionManager transactionManager, Clock relogio) {
        this.repositorio = repositorio;
        this.saldoDisponivel = saldoDisponivel;
        this.publisher = publisher;
        this.propriedades = propriedades;
        this.transacao = new TransactionTemplate(transactionManager);
        this.relogio = relogio;
    }

    /** Abre uma sessao cobrando entrada + flechas, se o saldo disponivel cobrir. */
    @Transactional
    public SessaoFlecha criar(String usuarioId, String slugCampo, Map<String, Integer> flechas) {
        Campo campo = Campo.porSlug(slugCampo)
                .orElseThrow(() -> new RegraNegocioException("Campo desconhecido: " + slugCampo));
        Aljava pedido = pedido(flechas);
        if (repositorio.findFirstByUsuarioIdAndStatus(usuarioId, StatusSessao.ABERTA).isPresent()) {
            throw jaTemAberta();
        }
        saldoDisponivel.exigir(usuarioId, SessaoFlecha.custoAbertura(campo, pedido));
        SessaoFlecha sessao = new SessaoFlecha(UUID.randomUUID(), usuarioId, campo, pedido, relogio.instant());
        try {
            return repositorio.saveAndFlush(sessao);
        } catch (DataIntegrityViolationException e) {
            // outro pedido simultaneo abriu a sessao primeiro (UNIQUE em usuarioAberta)
            throw jaTemAberta();
        }
    }

    private static ConflitoException jaTemAberta() {
        return new ConflitoException("Ja existe uma sessao aberta do Flecha Certeira: encerre antes de abrir outra");
    }

    private static Aljava pedido(Map<String, Integer> flechas) {
        Map<TipoFlecha, Integer> quantidades = new EnumMap<>(TipoFlecha.class);
        flechas.forEach((id, quantidade) -> {
            TipoFlecha tipo = tipoFlecha(id);
            if (quantidade == null || quantidade < 0) {
                throw new RegraNegocioException("Quantidade invalida de flechas " + id);
            }
            quantidades.put(tipo, quantidade);
        });
        Aljava pedido = Aljava.de(quantidades);
        if (pedido.total() < 1 || pedido.total() > RegrasFlecha.MAX_FLECHAS) {
            throw new RegraNegocioException("Escolha de 1 a " + RegrasFlecha.MAX_FLECHAS + " flechas");
        }
        return pedido;
    }

    private static TipoFlecha tipoFlecha(String id) {
        return TipoFlecha.porId(id).orElseThrow(() -> new RegraNegocioException("Flecha desconhecida: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<SessaoFlecha> atual(String usuarioId) {
        return repositorio.findFirstByUsuarioIdAndStatus(usuarioId, StatusSessao.ABERTA);
    }

    /**
     * Registra um tiro: o cliente diz quais passaros a flecha atravessou (na ordem). O servidor so confere a
     * plausibilidade: tem a flecha, combo ate 5, passaros que existem no campo e ritmo minimo entre tiros.
     */
    @Transactional
    public ResultadoTiro disparar(String usuarioId, UUID id, String flecha, List<String> acertos) {
        SessaoFlecha sessao = buscarAberta(usuarioId, id);
        TipoFlecha tipo = tipoFlecha(flecha);
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

    /** Compra mais flechas no meio da sessao (1..20 por vez, aljava ate 40), se o saldo cobrir. */
    @Transactional
    public SessaoFlecha comprar(String usuarioId, UUID id, String flecha, int quantidade) {
        SessaoFlecha sessao = buscarAberta(usuarioId, id);
        TipoFlecha tipo = tipoFlecha(flecha);
        if (quantidade < 1 || quantidade > RegrasFlecha.MAX_COMPRA) {
            throw new RegraNegocioException("Compre de 1 a " + RegrasFlecha.MAX_COMPRA + " flechas por vez");
        }
        if (sessao.getRestantes().total() + quantidade > RegrasFlecha.MAX_FLECHAS) {
            throw new RegraNegocioException("A aljava comporta no maximo " + RegrasFlecha.MAX_FLECHAS + " flechas");
        }
        BigDecimal extra = sessao.getCampo().custoFlecha(tipo).multiply(BigDecimal.valueOf(quantidade));
        saldoDisponivel.exigir(usuarioId, extra);
        sessao.comprar(tipo, quantidade, relogio.instant());
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
     * Grava (com flush, para um conflito de versao aparecer antes de avisar a carteira) e publica o resultado.
     * Se a fila falhar, a transacao volta e a sessao continua ABERTA.
     */
    private SessaoFlecha fechar(SessaoFlecha sessao, Instant agora) {
        sessao.encerrar(agora);
        SessaoFlecha salva = repositorio.saveAndFlush(sessao);
        try {
            publisher.publicarSessaoFlecha(evento(salva));
        } catch (AmqpException e) {
            throw new ServicoIndisponivelException("Fila indisponivel, tente encerrar de novo: " + e.getMessage());
        }
        return salva;
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
