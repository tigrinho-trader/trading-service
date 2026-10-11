package com.tigrinhotrader.trading.flecha.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.dominio.OrdemRepository;
import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.dominio.StatusSessao;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.mensageria.SessaoFlechaEncerradaEvento;
import com.tigrinhotrader.trading.servico.ConflitoException;
import com.tigrinhotrader.trading.servico.RegraNegocioException;
import com.tigrinhotrader.trading.servico.SaldoDisponivel;
import com.tigrinhotrader.trading.servico.ServicoIndisponivelException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Regras do servico isoladas de banco, fila e HTTP (os fluxos completos estao no FlechaControllerTest). */
class SessaoFlechaServiceTest {

    private final Instant agora = Instant.parse("2026-10-01T12:00:00Z");

    private SessaoFlechaRepository repositorio;
    private WalletClient wallet;
    private OrdemExecutadaPublisher publisher;
    private SessaoFlechaService service;

    /** Gerenciador de transacao "de mentira" so pra o TransactionTemplate rodar sem banco. */
    static class TransacaoFalsa extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @BeforeEach
    void setUp() {
        repositorio = mock(SessaoFlechaRepository.class);
        wallet = mock(WalletClient.class);
        OrdemRepository ordens = mock(OrdemRepository.class);
        publisher = mock(OrdemExecutadaPublisher.class);
        when(ordens.somarValorEmAberto(any())).thenReturn(BigDecimal.ZERO);
        when(repositorio.somarCustoEmAberto(any())).thenReturn(BigDecimal.ZERO);
        when(repositorio.findFirstByUsuarioIdAndStatus(any(), any())).thenReturn(Optional.empty());
        when(repositorio.save(any(SessaoFlecha.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repositorio.saveAndFlush(any(SessaoFlecha.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wallet.saldo("u1")).thenReturn(new BigDecimal("1000"));
        service = new SessaoFlechaService(repositorio, new SaldoDisponivel(wallet, ordens, repositorio), publisher,
                new FlechaProperties(null, null), new TransacaoFalsa(), Clock.fixed(agora, ZoneOffset.UTC));
    }

    private SessaoFlecha aberta(Instant atividade) {
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO,
                Aljava.de(Map.of(TipoFlecha.WOOD, 1)), atividade);
        when(repositorio.findById(s.getId())).thenReturn(Optional.of(s));
        when(repositorio.findByIdAndUsuarioId(s.getId(), "u1")).thenReturn(Optional.of(s));
        return s;
    }

    @Test
    void propriedadesTemPadroes() {
        FlechaProperties padrao = new FlechaProperties(null, null);
        assertThat(padrao.expiraApos()).isEqualTo(Duration.ofMinutes(10));
        assertThat(padrao.intervaloMinimoTiro()).isEqualTo(Duration.ofMillis(150));
        assertThat(new FlechaProperties(Duration.ofMinutes(1), Duration.ZERO).expiraApos())
                .isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void criaCobrandoEntradaMaisFlechas() {
        SessaoFlecha s = service.criar("u1", "bosque", Map.of("wood", 10, "iron", 5));
        // 15 + 10 x 3 + 5 x 6
        assertThat(s.getCusto()).isEqualByComparingTo("75");
        assertThat(s.getRestantes().total()).isEqualTo(15);
    }

    @Test
    void pedidoSimultaneoQueBateNoUniqueViraConflito() {
        when(repositorio.saveAndFlush(any(SessaoFlecha.class)))
                .thenThrow(new DataIntegrityViolationException("usuario_aberta"));
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", Map.of("wood", 1)))
                .isInstanceOf(ConflitoException.class);
    }

    @Test
    void quantidadeNulaOuNegativaEInvalida() {
        Map<String, Integer> comNulo = new HashMap<>();
        comNulo.put("wood", null);
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", comNulo)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", Map.of("wood", -1)))
                .isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", Map.of("wood", 0)))
                .isInstanceOf(RegraNegocioException.class).hasMessageContaining("1 a 40");
        verify(wallet, never()).saldo(any());
    }

    @Test
    void tiroSemAcertosInformadosValeComoFlechaPerdida() {
        SessaoFlecha s = aberta(agora);
        SessaoFlechaService.ResultadoTiro r = service.disparar("u1", s.getId(), "wood", null);
        assertThat(r.ganhosTiro()).isZero();
        assertThat(r.sessao().getDisparadas().get(TipoFlecha.WOOD)).isEqualTo(1);
    }

    @Test
    void filaForaDoArNoEncerramentoViraIndisponivel() {
        SessaoFlecha s = aberta(agora);
        doThrow(new AmqpConnectException(new RuntimeException("sem broker"))).when(publisher)
                .publicarSessaoFlecha(any());
        assertThatThrownBy(() -> service.encerrar("u1", s.getId())).isInstanceOf(ServicoIndisponivelException.class);
    }

    @Test
    void encerraOciosasPublicandoOResultado() {
        SessaoFlecha ociosa = aberta(agora.minus(Duration.ofMinutes(11)));
        when(repositorio.findByStatusAndUltimaAtividadeEmBefore(StatusSessao.ABERTA, agora.minus(Duration.ofMinutes(10))))
                .thenReturn(List.of(ociosa));

        assertThat(service.encerrarOciosas()).isEqualTo(1);

        assertThat(ociosa.isAberta()).isFalse();
        ArgumentCaptor<SessaoFlechaEncerradaEvento> evento = ArgumentCaptor.forClass(SessaoFlechaEncerradaEvento.class);
        verify(publisher).publicarSessaoFlecha(evento.capture());
        assertThat(evento.getValue().ordemId()).isEqualTo(ociosa.getId());
        assertThat(evento.getValue().tipo()).isEqualTo("FLECHA_CERTEIRA");
        assertThat(evento.getValue().simbolo()).isEqualTo("vilarejo");
        assertThat(evento.getValue().resultado()).isEqualTo("PERDEU");
        assertThat(evento.getValue().valor()).isEqualByComparingTo("6");
        assertThat(evento.getValue().valorLiquido()).isEqualByComparingTo("-6");
        assertThat(evento.getValue().executadaEm()).isEqualTo(agora);
    }

    @Test
    void ociosaQueSumiuVoltouAJogarOuJaFechouNaoContaEFalhaNaoParaAsOutras() {
        Instant limite = agora.minus(Duration.ofMinutes(10));
        SessaoFlecha sumiu = aberta(agora.minus(Duration.ofHours(1)));
        when(repositorio.findById(sumiu.getId())).thenReturn(Optional.empty());
        SessaoFlecha voltou = aberta(agora.minus(Duration.ofHours(1)));
        voltou.comprar(TipoFlecha.WOOD, 1, agora); // atividade depois da consulta
        SessaoFlecha jaFechada = aberta(agora.minus(Duration.ofHours(1)));
        jaFechada.encerrar(agora.minus(Duration.ofMinutes(30)));
        SessaoFlecha comFalha = aberta(agora.minus(Duration.ofHours(1)));
        SessaoFlecha boa = aberta(agora.minus(Duration.ofHours(1)));
        when(repositorio.findByStatusAndUltimaAtividadeEmBefore(StatusSessao.ABERTA, limite))
                .thenReturn(List.of(sumiu, voltou, jaFechada, comFalha, boa));
        doThrow(new AmqpConnectException(new RuntimeException("sem broker")))
                .doNothing()
                .when(publisher).publicarSessaoFlecha(any());

        assertThat(service.encerrarOciosas()).isEqualTo(1);

        assertThat(voltou.isAberta()).isTrue();
        assertThat(boa.isAberta()).isFalse();
        verify(publisher, times(2)).publicarSessaoFlecha(any());
    }

    @Test
    void agendadorDelegaAoServico() {
        SessaoFlechaService mockService = mock(SessaoFlechaService.class);
        new EncerradorSessoesFlecha(mockService).executar();
        verify(mockService).encerrarOciosas();
    }
}
