package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.preco.PrecoCache;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Partes do servico que a API nao alcanca facil: limite do dia e a revisao periodica. */
class OperacaoEstudoServiceTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");

    private final OperacaoEstudoRepository operacoes = mock(OperacaoEstudoRepository.class);
    private final SessaoFlechaRepository sessoes = mock(SessaoFlechaRepository.class);
    private final Corretora corretora = mock(Corretora.class);
    private final Agendador agendador = mock(Agendador.class);

    private OperacaoEstudoService servico(boolean revisao) {
        EstudoProperties p = new EstudoProperties(null, null, null, 0, null, null, null, 2, 0, null, null, revisao, null,
                null);
        return new OperacaoEstudoService(operacoes, sessoes, corretora, agendador, p, new PrecoCache(),
                Clock.fixed(AGORA, ZoneOffset.UTC));
    }

    @Test
    void modoRealParaNoLimiteDeOperacoesDoDia() {
        when(corretora.modo()).thenReturn(ModoEstudo.REAL);
        SessaoFlecha sessao = new SessaoFlecha(UUID.randomUUID(), "u", Campo.VILAREJO,
                Aljava.de(Map.of(TipoFlecha.BTC, 5)), AGORA);
        when(sessoes.findByIdAndUsuarioId(any(), eq("u"))).thenReturn(Optional.of(sessao));
        when(operacoes.findFirstByUsuarioIdOrderByCriadaEmDesc("u")).thenReturn(Optional.empty());
        when(operacoes.countByModoAndStatusNotAndCriadaEmGreaterThanEqual(ModoEstudo.REAL, StatusOperacao.RECUSADA,
                Instant.parse("2026-10-12T00:00:00Z"))).thenReturn(2L);
        when(operacoes.save(any())).thenAnswer(i -> i.getArgument(0));

        OperacaoEstudo o = servico(false).registrar("u", UUID.randomUUID(), "ALTA", "binaria", BigDecimal.ONE, null);

        assertThat(o.getStatus()).isEqualTo(StatusOperacao.RECUSADA);
        assertThat(o.getMotivo()).isEqualTo("Limite de operacoes do dia atingido");
        assertThat(o.getTipo()).isEqualTo(TipoPrevisao.BINARIA);
        verifyNoInteractions(agendador);
    }

    @Test
    void revisaoDesligadaNaoMexeEmNada() {
        servico(false).revisarPendentes();
        verifyNoInteractions(operacoes);
    }

    @Test
    void revisaoFechaAsEsquecidasEEncerraAsQueNuncaAbriram() {
        OperacaoEstudo esquecida = OperacaoEstudo.nova("u", UUID.randomUUID(), Direcao.ALTA, TipoPrevisao.PREVISAO, ModoEstudo.REAL,
                BigDecimal.TEN, null, null, AGORA.minusSeconds(120));
        esquecida.registrarAbertura(new Execucao("a", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN.negate(),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO), BigDecimal.ONE, AGORA.minusSeconds(119));
        OperacaoEstudo travada = OperacaoEstudo.nova("u", UUID.randomUUID(), Direcao.QUEDA, TipoPrevisao.BINARIA, ModoEstudo.REAL,
                BigDecimal.TEN, null, null, AGORA.minusSeconds(120));
        when(operacoes.findByStatusAndAbertaEmBefore(eq(StatusOperacao.ABERTA), any())).thenReturn(List.of(esquecida));
        when(operacoes.findByStatusAndCriadaEmBefore(eq(StatusOperacao.ABRINDO), any())).thenReturn(List.of(travada));
        when(operacoes.findById(esquecida.getId())).thenReturn(Optional.of(esquecida));
        when(corretora.fechar(eq(Direcao.ALTA), any(), anyString())).thenReturn(new Execucao("f", BigDecimal.ONE,
                new BigDecimal("11"), new BigDecimal("11"), BigDecimal.ONE.negate(), BigDecimal.ZERO, BigDecimal.ZERO));

        servico(true).revisarPendentes();

        assertThat(esquecida.getStatus()).isEqualTo(StatusOperacao.FECHADA);
        assertThat(esquecida.getResultadoLiquidoBrl()).isEqualByComparingTo("1");
        assertThat(travada.getStatus()).isEqualTo(StatusOperacao.FALHOU);
        assertThat(travada.getMotivo()).contains("Nao abriu a tempo");
        verify(operacoes).save(esquecida);
        verify(operacoes).save(travada);
    }

    @Test
    void tarefasAtrasadasDeOperacaoQueSumiuOuJaTerminouNaoFazemNada() {
        UUID id = UUID.randomUUID();
        when(operacoes.findById(id)).thenReturn(Optional.empty());
        OperacaoEstudoService s = servico(false);
        s.abrir(id);
        s.fechar(id);

        OperacaoEstudo terminada = OperacaoEstudo.nova("u", UUID.randomUUID(), Direcao.ALTA, TipoPrevisao.BINARIA, ModoEstudo.SIMULADO,
                BigDecimal.TEN, null, null, AGORA);
        terminada.recusar("x".repeat(400), AGORA);
        assertThat(terminada.getMotivo()).hasSize(300);
        when(operacoes.findById(terminada.getId())).thenReturn(Optional.of(terminada));
        s.abrir(terminada.getId());
        s.fechar(terminada.getId());

        verify(corretora, never()).abrir(any(), any(), anyString());
        verify(corretora, never()).fechar(any(), any(), anyString());
        verify(operacoes, never()).save(any());
    }

    @Test
    void idDaOrdemCabeNoLimiteDaBinance() {
        OperacaoEstudo o = OperacaoEstudo.nova("u", UUID.randomUUID(), Direcao.ALTA, TipoPrevisao.PREVISAO, ModoEstudo.REAL, BigDecimal.TEN,
                null, null, AGORA);
        assertThat(OperacaoEstudoService.idCliente(o, "f2")).hasSize(29).matches("[a-z0-9]+");
    }
}
