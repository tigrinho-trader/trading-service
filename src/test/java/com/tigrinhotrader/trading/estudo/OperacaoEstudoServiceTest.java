package com.tigrinhotrader.trading.estudo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.flecha.web.SessaoResponse;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.mensageria.SessaoFlechaEncerradaEvento;
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
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/** Partes do servico que a API nao alcanca facil: limite do dia, revisao periodica e liquidacao das apostas. */
class OperacaoEstudoServiceTest {

    private static final Instant AGORA = Instant.parse("2026-10-12T15:00:00Z");

    private final OperacaoEstudoRepository operacoes = mock(OperacaoEstudoRepository.class);
    private final SessaoFlechaRepository sessoes = mock(SessaoFlechaRepository.class);
    private final Corretora corretora = mock(Corretora.class);
    private final Agendador agendador = mock(Agendador.class);
    private final OrdemExecutadaPublisher publisher = mock(OrdemExecutadaPublisher.class);

    private OperacaoEstudoService servico(boolean revisao) {
        EstudoProperties p = new EstudoProperties(null, null, null, 0, null, null, null, 2, 0, null, null, revisao, null,
                null);
        // o gerenciador de transacao falso so deixa o TransactionTemplate rodar o codigo
        return new OperacaoEstudoService(operacoes, sessoes, corretora, agendador, p, new PrecoCache(),
                Clock.fixed(AGORA, ZoneOffset.UTC), publisher, mock(PlatformTransactionManager.class));
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

        OperacaoEstudo o = servico(false).registrar("u", UUID.randomUUID(), null, "ALTA", BigDecimal.ONE, null)
                .operacao();

        assertThat(o.getStatus()).isEqualTo(StatusOperacao.RECUSADA);
        assertThat(o.getMotivo()).isEqualTo("Limite de operacoes do dia atingido");
        assertThat(o.getTipo()).isEqualTo(TipoPrevisao.BINARIA);
        assertThat(o.getSimbolo()).isEqualTo("BTCUSDT");
        verifyNoInteractions(agendador);
    }

    @Test
    void soOAtivoConfiguradoUsaACorretoraConfigurada() {
        when(corretora.modo()).thenReturn(ModoEstudo.REAL);
        OperacaoEstudoService s = servico(false);

        assertThat(s.corretoraDe("BTCUSDT")).isSameAs(corretora);
        assertThat(s.corretoraDe("btcusdt")).isSameAs(corretora);
        Corretora eth = s.corretoraDe("ETHUSDT");
        assertThat(eth).isInstanceOf(CorretoraSimulada.class).isSameAs(s.corretoraDe("ETHUSDT"));
        assertThat(eth.modo()).isEqualTo(ModoEstudo.SIMULADO);
        assertThat(s.corretoraDe("PAXGUSDT")).isInstanceOf(CorretoraSimulada.class).isNotSameAs(eth);
    }

    @Test
    void operacaoAntigaSemSimboloEraDeBitcoin() {
        OperacaoEstudo antiga = OperacaoEstudo.nova("u", UUID.randomUUID(), null, Direcao.ALTA, null,
                ModoEstudo.SIMULADO, BigDecimal.TEN, null, null, AGORA);
        assertThat(antiga.getSimbolo()).isEqualTo("BTCUSDT");
        assertThat(antiga.getTipo()).isEqualTo(TipoPrevisao.PREVISAO);
    }

    @Test
    void revisaoDesligadaNaoMexeEmNada() {
        servico(false).revisarPendentes();
        verifyNoInteractions(operacoes);
    }

    @Test
    void revisaoFechaAsEsquecidasEEncerraAsQueNuncaAbriram() {
        OperacaoEstudo esquecida = OperacaoEstudo.nova("u", UUID.randomUUID(), "BTCUSDT", Direcao.ALTA, TipoPrevisao.PREVISAO, ModoEstudo.REAL,
                BigDecimal.TEN, null, null, AGORA.minusSeconds(120));
        esquecida.registrarAbertura(new Execucao("a", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN.negate(),
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO), BigDecimal.ONE, AGORA.minusSeconds(119));
        OperacaoEstudo travada = OperacaoEstudo.nova("u", UUID.randomUUID(), "BTCUSDT", Direcao.QUEDA, TipoPrevisao.BINARIA, ModoEstudo.REAL,
                BigDecimal.TEN, null, null, AGORA.minusSeconds(120));
        when(operacoes.findByStatusAndAbertaEmBefore(eq(StatusOperacao.ABERTA), any())).thenReturn(List.of(esquecida));
        when(operacoes.findByStatusAndCriadaEmBefore(eq(StatusOperacao.ABRINDO), any())).thenReturn(List.of(travada));
        when(operacoes.findById(esquecida.getId())).thenReturn(Optional.of(esquecida));
        when(corretora.fechar(eq(Direcao.ALTA), any(), anyString())).thenReturn(new Execucao("f", BigDecimal.ONE,
                new BigDecimal("11"), new BigDecimal("11"), BigDecimal.ONE.negate(), BigDecimal.ZERO, BigDecimal.ZERO));

        servico(true).revisarPendentes();

        assertThat(esquecida.getStatus()).isEqualTo(StatusOperacao.FECHADA);
        assertThat(esquecida.getResultadoLiquidoBrl()).isEqualByComparingTo("1");
        // a sessao nao existe mais (mock vazio): o pagamento (100 + 10 x R$ 1) vai direto para a carteira
        assertThat(esquecida.getMoedasPagas()).isEqualByComparingTo("110.00");
        verify(publisher).publicarSessaoFlecha(any());
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

        OperacaoEstudo terminada = OperacaoEstudo.nova("u", UUID.randomUUID(), "BTCUSDT", Direcao.ALTA, TipoPrevisao.BINARIA, ModoEstudo.SIMULADO,
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
        OperacaoEstudo o = OperacaoEstudo.nova("u", UUID.randomUUID(), "BTCUSDT", Direcao.ALTA, TipoPrevisao.PREVISAO, ModoEstudo.REAL, BigDecimal.TEN,
                null, null, AGORA);
        assertThat(OperacaoEstudoService.idCliente(o, "f2")).hasSize(29).matches("[a-z0-9]+");
    }

    /** Operacao terminada do jeito pedido e a sessao (aberta ou nao) dela, nos mocks. */
    private OperacaoEstudo terminada(StatusOperacao status, String precoSaida, SessaoFlecha sessao) {
        OperacaoEstudo o = criarTerminada(status, precoSaida, sessao.getId());
        when(operacoes.findById(o.getId())).thenReturn(Optional.of(o));
        when(sessoes.findById(sessao.getId())).thenReturn(Optional.of(sessao));
        return o;
    }

    /**
     * Operacao terminada; na FECHADA compra 1 unidade a 100 e vende a {@code precoSaida}, com cambio 1: o resultado
     * liquido em reais e {@code precoSaida - 100}.
     */
    private static OperacaoEstudo criarTerminada(StatusOperacao status, String precoSaida, UUID sessaoId) {
        return criarTerminada(status, precoSaida, sessaoId, "-100");
    }

    /** Igual, com o fluxo da entrada escolhido (ex.: -100.05 = taxa de 0,05 na compra). */
    private static OperacaoEstudo criarTerminada(StatusOperacao status, String precoSaida, UUID sessaoId,
                                                 String fluxoEntrada) {
        OperacaoEstudo o = OperacaoEstudo.nova("u", sessaoId, "ETHUSDT", Direcao.ALTA, TipoPrevisao.BINARIA,
                ModoEstudo.SIMULADO, BigDecimal.TEN, null, null, AGORA);
        switch (status) {
            case FECHADA -> {
                Execucao entrada = new Execucao("a", BigDecimal.ONE, new BigDecimal("100"),
                        new BigDecimal(fluxoEntrada), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
                o.registrarAbertura(entrada, BigDecimal.ONE, AGORA);
                Execucao saida = new Execucao("f", BigDecimal.ONE, new BigDecimal(precoSaida),
                        new BigDecimal(precoSaida), BigDecimal.ONE.negate(), BigDecimal.ZERO, BigDecimal.ZERO);
                o.fechar(saida, ResultadoOperacao.calcular(Direcao.ALTA, entrada, saida, BigDecimal.ONE,
                        BigDecimal.TEN), AGORA);
            }
            case RECUSADA -> o.recusar("limite", AGORA);
            case FALHOU -> o.falhar("erro", AGORA);
            default -> { }
        }
        return o;
    }

    private SessaoFlecha sessaoAberta() {
        return new SessaoFlecha(UUID.randomUUID(), "u", Campo.BOSQUE, Aljava.de(Map.of(TipoFlecha.ETH, 1)), AGORA);
    }

    @Test
    void liquidacaoPagaAApostaMaisDezVezesOResultadoLiquido() {
        // {status, preco de saida (resultado liquido = preco - 100), moedas pagas, ganhou a aposta, acertou a direcao}
        Object[][] casos = {
                {StatusOperacao.FECHADA, "99.94", "99.40", false, false},     // -R$ 0,06
                {StatusOperacao.FECHADA, "100.031", "100.31", true, true},    // +R$ 0,031
                {StatusOperacao.FECHADA, "100.0005", "100.01", true, true},   // 100,005: HALF_UP
                {StatusOperacao.FECHADA, "99.9995", "100.00", false, false},  // 99,995: HALF_UP, devolve a aposta
                {StatusOperacao.FECHADA, "100", "100.00", false, false},      // preco parado, sem taxa
                {StatusOperacao.FECHADA, "101", "110.00", true, true},
                {StatusOperacao.FECHADA, "90", "0.00", false, false},         // -R$ 10: perde a aposta toda
                {StatusOperacao.FECHADA, "80", "0.00", false, false},         // -R$ 20: nunca menos que 0
                {StatusOperacao.FALHOU, null, "100.00", false, false},
                {StatusOperacao.RECUSADA, null, "100.00", false, false}};
        for (Object[] caso : casos) {
            OperacaoEstudo o = criarTerminada((StatusOperacao) caso[0], (String) caso[1], UUID.randomUUID());

            BigDecimal pagas = o.liquidar();

            assertThat(pagas).as("pagamento %s %s", caso[0], caso[1]).isEqualByComparingTo((String) caso[2])
                    .hasScaleOf(2).isSameAs(o.getMoedasPagas());
            assertThat(o.ganhouAposta()).as("ganhou %s %s", caso[0], caso[1]).isEqualTo(caso[3]);
            assertThat(o.acertouAposta()).as("acertou %s %s", caso[0], caso[1]).isEqualTo(caso[4]);
            assertThat(o.aguardaLiquidacao()).isFalse();
        }
    }

    @Test
    void acertarADirecaoComAsTaxasComendoOLucroPagaMenosQueAApostaMasContaComoAcerto() {
        OperacaoEstudoService s = servico(false);
        SessaoFlecha sessao = sessaoAberta();
        // subiu 0,03 mas a compra custou 0,05 de taxa: liquido -R$ 0,02
        OperacaoEstudo o = criarTerminada(StatusOperacao.FECHADA, "100.03", sessao.getId(), "-100.05");
        when(operacoes.findById(o.getId())).thenReturn(Optional.of(o));
        when(sessoes.findById(sessao.getId())).thenReturn(Optional.of(sessao));

        s.liquidar(o.getId());

        assertThat(o.getResultadoLiquidoBrl()).isEqualByComparingTo("-0.02");
        assertThat(o.getMoedasPagas()).isEqualByComparingTo("99.80");
        assertThat(o.ganhouAposta()).isFalse();
        assertThat(o.compararPagamento()).isNegative();
        // o placar de acertos continua sendo a direcao
        assertThat(o.sinalAposta()).isEqualTo(1);
        assertThat(sessao.getAcertos()).isEqualTo(1);
        assertThat(sessao.getGanhos()).isEqualByComparingTo("99.80").hasScaleOf(2);
        // e a carteira, com a sessao ja encerrada: paga 99,80, PERDEU a aposta mas acertou a direcao
        SessaoFlechaEncerradaEvento pagamento = OperacaoEstudoService.pagamento(o, AGORA);
        assertThat(pagamento.resultado()).isEqualTo("PERDEU");
        assertThat(pagamento.valorLiquido()).isEqualByComparingTo("99.80");
        assertThat(pagamento.acertos()).isEqualTo(1);
    }

    @Test
    void comASessaoAbertaAApostaEntraNosGanhos() {
        OperacaoEstudoService s = servico(false);
        Object[][] casos = {
                {StatusOperacao.FECHADA, "101", "110.00", 1},
                {StatusOperacao.FECHADA, "100", "100.00", 0},
                {StatusOperacao.FECHADA, "99.94", "99.40", 0},
                {StatusOperacao.FECHADA, "100.031", "100.31", 1},
                {StatusOperacao.FECHADA, "80", "0.00", 0},
                {StatusOperacao.RECUSADA, null, "100.00", 0},
                {StatusOperacao.FALHOU, null, "100.00", 0}};
        for (Object[] caso : casos) {
            SessaoFlecha sessao = sessaoAberta();
            OperacaoEstudo o = terminada((StatusOperacao) caso[0], (String) caso[1], sessao);
            assertThat(o.aguardaLiquidacao()).isTrue();

            s.liquidar(o.getId());

            assertThat(o.getMoedasPagas()).as("pagamento %s %s", caso[0], caso[1])
                    .isEqualByComparingTo((String) caso[2]);
            assertThat(sessao.getGanhos()).isEqualByComparingTo(String.valueOf(caso[2]));
            assertThat(sessao.getAcertos()).isEqualTo(caso[3]);
            assertThat(o.aguardaLiquidacao()).isFalse();
            verify(sessoes).saveAndFlush(sessao);

            // de novo: nao paga outra vez
            s.liquidar(o.getId());
            assertThat(sessao.getGanhos()).isEqualByComparingTo(String.valueOf(caso[2]));
        }
        verify(publisher, never()).publicarSessaoFlecha(any());
    }

    @Test
    void comASessaoEncerradaOPagamentoVaiParaACarteiraEZeroNaoPublica() {
        OperacaoEstudoService s = servico(false);
        SessaoFlecha encerrada = sessaoAberta();
        encerrada.encerrar(AGORA);
        OperacaoEstudo ganhou = terminada(StatusOperacao.FECHADA, "100.031", encerrada);
        OperacaoEstudo perdeu = terminada(StatusOperacao.FECHADA, "99.94", encerrada);
        OperacaoEstudo zerou = terminada(StatusOperacao.FECHADA, "80", encerrada);
        OperacaoEstudo recusada = terminada(StatusOperacao.RECUSADA, null, encerrada);

        s.liquidar(ganhou.getId());
        s.liquidar(perdeu.getId());
        s.liquidar(zerou.getId());
        s.liquidar(recusada.getId());
        s.liquidar(ganhou.getId());

        ArgumentCaptor<SessaoFlechaEncerradaEvento> eventos = ArgumentCaptor.forClass(SessaoFlechaEncerradaEvento.class);
        // a que nao pagou nada nao publica
        verify(publisher, times(3)).publicarSessaoFlecha(eventos.capture());
        SessaoFlechaEncerradaEvento pago = eventos.getAllValues().get(0);
        assertThat(pago.ordemId()).isEqualTo(ganhou.referenciaPagamento()).isNotEqualTo(ganhou.getId());
        assertThat(pago.tipo()).isEqualTo("FLECHA_MERCADO");
        assertThat(pago.simbolo()).isEqualTo("ETHUSDT");
        assertThat(pago.valor()).isEqualByComparingTo("100");
        assertThat(pago.resultado()).isEqualTo("GANHOU");
        assertThat(pago.valorLiquido()).isEqualByComparingTo("100.31").hasScaleOf(2);
        assertThat(pago.ganhos()).isEqualByComparingTo("100.31");
        assertThat(pago.acertos()).isEqualTo(1);
        assertThat(pago.executadaEm()).isEqualTo(AGORA);
        SessaoFlechaEncerradaEvento perdido = eventos.getAllValues().get(1);
        assertThat(perdido.ordemId()).isEqualTo(perdeu.referenciaPagamento());
        assertThat(perdido.resultado()).isEqualTo("PERDEU");
        assertThat(perdido.valorLiquido()).isEqualByComparingTo("99.40");
        assertThat(perdido.acertos()).isZero();
        SessaoFlechaEncerradaEvento devolvido = eventos.getAllValues().get(2);
        assertThat(devolvido.ordemId()).isEqualTo(recusada.referenciaPagamento());
        assertThat(devolvido.resultado()).isEqualTo("EMPATOU");
        assertThat(devolvido.valorLiquido()).isEqualByComparingTo("100");
        assertThat(devolvido.acertos()).isZero();
        assertThat(zerou.getMoedasPagas()).isZero();
        // a referencia e estavel: a carteira reconhece o mesmo pagamento repetido
        assertThat(ganhou.referenciaPagamento()).isEqualTo(ganhou.referenciaPagamento());
        assertThat(encerrada.getGanhos()).isEqualByComparingTo("0");
    }

    @Test
    void conflitoDeVersaoTentaDeNovoEFilaForaDoArFicaParaARevisao() {
        OperacaoEstudoService s = servico(false);
        SessaoFlecha sessao = sessaoAberta();
        OperacaoEstudo o = terminada(StatusOperacao.FALHOU, null, sessao);
        // o jogador atirou na mesma sessao: a 1a tentativa perde a corrida
        when(sessoes.saveAndFlush(sessao))
                .thenThrow(new ObjectOptimisticLockingFailureException(SessaoFlecha.class, sessao.getId()))
                .thenReturn(sessao);
        // o mock nao desfaz a 1a tentativa como o banco faria: volta a operacao a pendente na mao
        OperacaoEstudo pendente = terminada(StatusOperacao.FALHOU, null, sessao);
        when(operacoes.findById(o.getId())).thenReturn(Optional.of(o), Optional.of(pendente));

        s.liquidar(o.getId());

        verify(sessoes, times(2)).saveAndFlush(sessao);
        assertThat(pendente.getMoedasPagas()).isEqualByComparingTo("100");

        // fila fora do ar com a sessao encerrada: nao propaga, a revisao tenta depois
        SessaoFlecha encerrada = sessaoAberta();
        encerrada.encerrar(AGORA);
        OperacaoEstudo semFila = terminada(StatusOperacao.RECUSADA, null, encerrada);
        doThrow(new AmqpConnectException(new RuntimeException("sem broker"))).when(publisher)
                .publicarSessaoFlecha(any());
        s.liquidar(semFila.getId());
        verify(publisher).publicarSessaoFlecha(any());

        // operacao que sumiu ou ainda nao terminou: nada
        s.liquidar(UUID.randomUUID());
        OperacaoEstudo emAndamento = terminada(StatusOperacao.ABRINDO, null, sessao);
        s.liquidar(emAndamento.getId());
        assertThat(emAndamento.getMoedasPagas()).isNull();
        assertThatThrownBy(emAndamento::liquidar).isInstanceOf(IllegalStateException.class);

        // conflito toda vez (cada tentativa le a operacao pendente de novo, como o banco depois do rollback):
        // desiste depois de algumas tentativas
        clearInvocations(operacoes);
        UUID teimosa = UUID.randomUUID();
        when(operacoes.findById(teimosa))
                .thenAnswer(i -> Optional.of(criarTerminada(StatusOperacao.FALHOU, null, sessao.getId())));
        when(operacoes.saveAndFlush(any(OperacaoEstudo.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(OperacaoEstudo.class, teimosa));
        s.liquidar(teimosa);
        verify(operacoes, times(OperacaoEstudoService.MAX_TENTATIVAS_LIQUIDAR)).saveAndFlush(any(OperacaoEstudo.class));
    }

    @Test
    void revisaoLiquidaAsApostasPendentes() {
        SessaoFlecha sessao = sessaoAberta();
        OperacaoEstudo pendente = terminada(StatusOperacao.RECUSADA, null, sessao);
        when(operacoes.findByApostaMoedasIsNotNullAndMoedasPagasIsNullAndStatusIn(
                List.of(StatusOperacao.FECHADA, StatusOperacao.RECUSADA, StatusOperacao.FALHOU)))
                .thenReturn(List.of(pendente));

        servico(true).revisarPendentes();

        assertThat(pendente.getMoedasPagas()).isEqualByComparingTo("100");
        assertThat(sessao.getGanhos()).isEqualByComparingTo("100");
    }

    @Test
    void apostaNovaValeCemEOperacaoSemSessaoNaRespostaNaoQuebra() {
        OperacaoEstudo o = OperacaoEstudo.nova("u", UUID.randomUUID(), "BTCUSDT", Direcao.ALTA, TipoPrevisao.BINARIA,
                ModoEstudo.SIMULADO, BigDecimal.TEN, null, null, AGORA);
        assertThat(o.getApostaMoedas()).isEqualTo(100);
        assertThat(o.getMoedasPagas()).isNull();
        assertThat(o.ganhouAposta()).isFalse();
        assertThat(o.compararPagamento()).isZero();
        assertThat(o.aguardaLiquidacao()).isFalse();

        EstudoController.OperacaoResponse semSessao = EstudoController.OperacaoResponse.de(
                new OperacaoEstudoService.Registro(o, null));
        assertThat(semSessao.sessao()).isNull();
        assertThat(semSessao.moedasPagas()).isNull();
        SessaoFlecha sessao = sessaoAberta();
        SessaoResponse comSessao = EstudoController.OperacaoResponse.de(
                new OperacaoEstudoService.Registro(o, sessao)).sessao();
        assertThat(comSessao.id()).isEqualTo(sessao.getId());
    }

    @Test
    void meusAcertosUsaAcertouDirecaoNasLinhasAntigasEContaSemSimboloComoBitcoin() {
        UUID sessaoId = UUID.randomUUID();
        Execucao entrada = new Execucao("a", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.TEN.negate(), BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO);
        // linhas de antes do placar binario e do resultado: sem simbolo, sem binario, sem liquido
        OperacaoEstudo antigaAcertou = OperacaoEstudo.nova("u", sessaoId, null, Direcao.ALTA, null,
                ModoEstudo.SIMULADO, BigDecimal.TEN, null, null, AGORA);
        antigaAcertou.fechar(entrada, new ResultadoOperacao(null, null, null, null, null, null, true, null), AGORA);
        OperacaoEstudo antigaErrou = OperacaoEstudo.nova("u", sessaoId, null, Direcao.ALTA, null,
                ModoEstudo.SIMULADO, BigDecimal.TEN, null, null, AGORA);
        antigaErrou.fechar(entrada, new ResultadoOperacao(null, null, null, null, null, null, false, null), AGORA);
        OperacaoEstudo nova = OperacaoEstudo.nova("u", sessaoId, "PAXGUSDT", Direcao.ALTA, TipoPrevisao.BINARIA,
                ModoEstudo.SIMULADO, BigDecimal.TEN, null, null, AGORA);
        nova.fechar(entrada, new ResultadoOperacao(null, null, null, null, null, new BigDecimal("1.25"), true,
                new BigDecimal("10")), AGORA);
        when(operacoes.findByUsuarioIdAndStatus("u", StatusOperacao.FECHADA))
                .thenReturn(List.of(antigaAcertou, antigaErrou, nova, antigaAcertou));
        when(operacoes.findByUsuarioIdAndSessaoIdAndStatus("u", sessaoId, StatusOperacao.FECHADA))
                .thenReturn(List.of(nova));
        OperacaoEstudoService s = servico(false);

        OperacaoEstudoService.MeusAcertos todos = s.meusAcertos("u", null);

        OperacaoEstudoService.Placar btc = todos.porAtivo().get(AtivoEstudo.BTC);
        assertThat(btc.operacoes()).isEqualTo(3);
        assertThat(btc.acertos()).isEqualTo(2);
        assertThat(btc.erros()).isEqualTo(1);
        assertThat(btc.taxaAcertoPercentual()).isEqualByComparingTo("66.7");
        assertThat(btc.resultadoLiquidoBrl()).isEqualByComparingTo("0");
        assertThat(todos.porAtivo().get(AtivoEstudo.ETH).taxaAcertoPercentual()).isNull();
        assertThat(todos.total().operacoes()).isEqualTo(4);
        assertThat(todos.total().resultadoLiquidoBrl()).isEqualByComparingTo("1.25");
        assertThat(antigaErrou.sinalAposta()).isEqualTo(-1);

        OperacaoEstudoService.MeusAcertos daSessao = s.meusAcertos("u", sessaoId);
        assertThat(daSessao.total().operacoes()).isEqualTo(1);
        assertThat(daSessao.porAtivo().get(AtivoEstudo.OURO).taxaAcertoPercentual()).isEqualByComparingTo("100.0");
        assertThat(daSessao.porAtivo().get(AtivoEstudo.BTC).operacoes()).isZero();
    }
}
