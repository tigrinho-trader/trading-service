package com.tigrinhotrader.trading.flecha.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.dominio.OrdemRepository;
import com.tigrinhotrader.trading.flecha.dominio.Aljava;
import com.tigrinhotrader.trading.flecha.dominio.AljavaGuardada;
import com.tigrinhotrader.trading.flecha.dominio.AljavaGuardadaRepository;
import com.tigrinhotrader.trading.flecha.dominio.LoteFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlecha;
import com.tigrinhotrader.trading.flecha.dominio.SessaoFlechaRepository;
import com.tigrinhotrader.trading.flecha.dominio.StatusSessao;
import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import com.tigrinhotrader.trading.flecha.servico.SessaoFlechaService.PedidoLote;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.mensageria.SessaoFlechaEncerradaEvento;
import com.tigrinhotrader.trading.servico.ConflitoException;
import com.tigrinhotrader.trading.servico.PedidoInvalidoException;
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
    private AljavaGuardadaRepository aljavas;
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
        aljavas = mock(AljavaGuardadaRepository.class);
        wallet = mock(WalletClient.class);
        OrdemRepository ordens = mock(OrdemRepository.class);
        publisher = mock(OrdemExecutadaPublisher.class);
        when(ordens.somarValorEmAberto(any())).thenReturn(BigDecimal.ZERO);
        when(repositorio.somarCustoEmAberto(any())).thenReturn(BigDecimal.ZERO);
        when(repositorio.findFirstByUsuarioIdAndStatus(any(), any())).thenReturn(Optional.empty());
        when(repositorio.save(any(SessaoFlecha.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repositorio.saveAndFlush(any(SessaoFlecha.class))).thenAnswer(inv -> inv.getArgument(0));
        when(wallet.saldo("u1")).thenReturn(new BigDecimal("1000"));
        service = new SessaoFlechaService(repositorio, aljavas, new SaldoDisponivel(wallet, ordens, repositorio),
                publisher, new FlechaProperties(null, null, null), new TransacaoFalsa(),
                Clock.fixed(agora, ZoneOffset.UTC));
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
        FlechaProperties padrao = new FlechaProperties(null, null, null);
        assertThat(padrao.expiraApos()).isEqualTo(Duration.ofMinutes(10));
        assertThat(padrao.intervaloMinimoTiro()).isEqualTo(Duration.ofMillis(150));
        // 0,1% em cada ponta da conversao real -> dolar -> real
        assertThat(padrao.taxaDevolucao()).isEqualByComparingTo("0.002");
        FlechaProperties outra = new FlechaProperties(Duration.ofMinutes(1), Duration.ZERO, new BigDecimal("0.01"));
        assertThat(outra.expiraApos()).isEqualTo(Duration.ofMinutes(1));
        assertThat(outra.taxaDevolucao()).isEqualByComparingTo("0.01");
    }

    @Test
    void criaCobrandoEntradaMaisFlechas() {
        // 1,5 + 10 madeira (gratis) + 1 ethereum sem valor (cliente antigo: 10); ferro com 0 no pedido nao atrapalha
        SessaoFlecha s = service.criar("u1", "bosque", Map.of("wood", 10, "eth", 1, "iron", 0));
        assertThat(s.getCusto()).isEqualByComparingTo("11.5");
        assertThat(s.getLotesMercado()).containsExactly(new LoteFlecha(TipoFlecha.ETH, BigDecimal.TEN, 1));
        assertThat(s.getRestantes().total()).isEqualTo(11);
        verify(wallet).saldo("u1");
    }

    @Test
    void ferroOuroEDiamanteForamRemovidos() {
        for (String flecha : List.of("iron", "gold", "diamond")) {
            assertThatThrownBy(() -> service.criar("u1", "reserva-real", Map.of("wood", 1, flecha, 1)))
                    .isInstanceOf(PedidoInvalidoException.class)
                    .hasMessage("Flechas de ferro, ouro e diamante foram removidas do jogo: use madeira (gratis) ou "
                            + "as flechas de mercado");
        }
        SessaoFlecha s = aberta(agora);
        assertThatThrownBy(() -> service.comprar("u1", s.getId(), "diamond", 1, null))
                .isInstanceOf(PedidoInvalidoException.class);
        assertThatThrownBy(() -> service.disparar("u1", s.getId(), "iron", List.of()))
                .isInstanceOf(PedidoInvalidoException.class);
        assertThat(s.getCusto()).isEqualByComparingTo("0.5");
        verify(wallet, never()).saldo(any());

        // cliente antigo mandando os tipos removidos com 0 (ou nulo) abre normalmente
        Map<String, Integer> antigo = new HashMap<>(Map.of("wood", 1, "iron", 0, "gold", 0));
        antigo.put("diamond", null);
        assertThat(service.criar("u1", "vilarejo", antigo).getRestantes().total()).isEqualTo(1);
    }

    @Test
    void flechasAntigasGuardadasNaoEntramNaSessao() {
        AljavaGuardada doBosque = guardada("bosque", Map.of(TipoFlecha.WOOD, 1));
        doBosque.guardar(Aljava.antigas(2, 0, 0), agora);

        SessaoFlecha s = service.criar("u1", "bosque", Map.of());

        assertThat(s.getRestantes().total()).isEqualTo(1);
        assertThat(s.getRestantes().antigas()).isZero();
        assertThat(doBosque.getFlechas().antigas()).isEqualTo(2);
    }

    @Test
    void reembolsaAsFlechasAntigasGuardadasUmaVezSo() {
        // bosque: 1 ferro (2 x 3) + 1 diamante (8 x 3) = 30
        AljavaGuardada doBosque = new AljavaGuardada("u1", "bosque", agora);
        doBosque.guardar(Aljava.antigas(1, 0, 1), agora);
        doBosque.guardar(Aljava.de(Map.of(TipoFlecha.WOOD, 3)), agora);
        // escopo sem campo conhecido: so zera, sem valor
        AljavaGuardada estranha = new AljavaGuardada("u1", "lua", agora);
        estranha.guardar(Aljava.antigas(5, 0, 0), agora);
        AljavaGuardada sumiu = new AljavaGuardada("u1", "pico", agora);
        when(aljavas.findComFlechasAntigas()).thenReturn(List.of(doBosque, estranha, sumiu));
        when(aljavas.findById(doBosque.getId())).thenReturn(Optional.of(doBosque));
        when(aljavas.findById(estranha.getId())).thenReturn(Optional.of(estranha));
        when(aljavas.findById(sumiu.getId())).thenReturn(Optional.empty());
        UUID referencia = doBosque.referenciaReembolso();

        assertThat(service.reembolsarFlechasAntigas()).isEqualTo(2);

        ArgumentCaptor<SessaoFlechaEncerradaEvento> evento = ArgumentCaptor.forClass(SessaoFlechaEncerradaEvento.class);
        verify(publisher).publicarSessaoFlecha(evento.capture());
        assertThat(evento.getValue().ordemId()).isEqualTo(referencia);
        assertThat(evento.getValue().usuarioId()).isEqualTo("u1");
        assertThat(evento.getValue().simbolo()).isEqualTo("bosque");
        assertThat(evento.getValue().tipo()).isEqualTo("FLECHA_REEMBOLSO");
        assertThat(evento.getValue().resultado()).isEqualTo("GANHOU");
        assertThat(evento.getValue().valor()).isEqualByComparingTo("30");
        assertThat(evento.getValue().valorLiquido()).isEqualByComparingTo("30");
        assertThat(evento.getValue().executadaEm()).isEqualTo(agora);
        assertThat(doBosque.getFlechas().antigas()).isZero();
        assertThat(doBosque.getFlechas().get(TipoFlecha.WOOD)).isEqualTo(3);
        assertThat(estranha.getFlechas().antigas()).isZero();
        verify(aljavas).saveAndFlush(doBosque);

        // de novo (aljava ja zerada): nada a reembolsar
        assertThat(service.reembolsarFlechasAntigas()).isZero();
        verify(publisher, times(1)).publicarSessaoFlecha(any());
    }

    @Test
    void reembolsoComAFilaForaDoArFicaParaOProximoCiclo() {
        AljavaGuardada doVilarejo = new AljavaGuardada("u1", "vilarejo", agora);
        doVilarejo.guardar(Aljava.antigas(0, 1, 0), agora);
        when(aljavas.findComFlechasAntigas()).thenReturn(List.of(doVilarejo));
        when(aljavas.findById(doVilarejo.getId())).thenReturn(Optional.of(doVilarejo));
        doThrow(new AmqpConnectException(new RuntimeException("sem broker"))).when(publisher)
                .publicarSessaoFlecha(any());

        assertThat(service.reembolsarFlechasAntigas()).isZero();
    }

    @Test
    void madeiraNoMeioDaSessaoEGratisESemConsultarACarteira() {
        SessaoFlecha s = aberta(agora);
        when(wallet.saldo("u1")).thenThrow(new ServicoIndisponivelException("wallet fora"));

        SessaoFlecha depois = service.comprar("u1", s.getId(), "wood", 20, new BigDecimal("50"));

        assertThat(depois.getCusto()).isEqualByComparingTo("0.5");
        assertThat(depois.getRestantes().get(TipoFlecha.WOOD)).isEqualTo(21);
        verify(wallet, never()).saldo(any());
    }

    @Test
    void flechaDeMercadoSoNosCamposComOAtivo() {
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", Map.of("eth", 1)))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessage("Flechas Ethereum nao sao vendidas no Campo do Vilarejo");
        assertThatThrownBy(() -> service.criar("u1", "bosque", Map.of("wood", 1, "ouro", 1)))
                .hasMessage("Flechas Ouro PAXG nao sao vendidas no Bosque dos Arqueiros");
        SessaoFlecha s = aberta(agora);
        assertThatThrownBy(() -> service.comprar("u1", s.getId(), "ouro", 1, BigDecimal.TEN))
                .hasMessage("Flechas Ouro PAXG nao sao vendidas no Campo do Vilarejo");
        verify(wallet, never()).saldo(any());

        // a bitcoin vale em todo campo; no pico tem os quatro ativos
        assertThat(service.comprar("u1", s.getId(), "btc", 1, BigDecimal.TEN).getCusto()).isEqualByComparingTo("10.5");
        SessaoFlecha pico = service.criar("u1", "pico", Map.of("btc", 1, "eth", 1, "ouro", 1, "v100", 1));
        assertThat(pico.getCusto()).isEqualByComparingTo("44");
    }

    @Test
    void passaroDeMercadoSoApareceNosCamposComOAtivo() {
        SessaoFlecha s = aberta(agora);
        s.comprar(TipoFlecha.WOOD, 1, agora);
        assertThatThrownBy(() -> service.disparar("u1", s.getId(), "wood", List.of("btc", "eth")))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessage("Passaro eth nao aparece no Campo do Vilarejo");
        assertThatThrownBy(() -> service.disparar("u1", s.getId(), "wood", List.of("ouro")))
                .hasMessage("Passaro ouro nao aparece no Campo do Vilarejo");

        SessaoFlechaService.ResultadoTiro r = service.disparar("u1", s.getId(), "wood", List.of("btc", "red"));
        assertThat(r.ganhosTiro()).isZero();
        assertThat(r.sessao().getAcertos()).isEqualTo(2);
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

    private AljavaGuardada guardada(String escopo, Map<TipoFlecha, Integer> flechas) {
        AljavaGuardada g = new AljavaGuardada("u1", escopo, agora);
        g.guardar(Aljava.de(flechas), agora);
        when(aljavas.findByUsuarioIdAndEscopo("u1", escopo)).thenReturn(Optional.of(g));
        return g;
    }

    @Test
    void abreComAsGuardadasDoCampoEAsDeMercadoSemCobrarPorElas() {
        AljavaGuardada doBosque = guardada("bosque", Map.of(TipoFlecha.WOOD, 4));
        AljavaGuardada deMercado = guardada(AljavaGuardada.MERCADO, Map.of(TipoFlecha.BTC, 2));

        SessaoFlecha s = service.criar("u1", "bosque", Map.of("wood", 1));

        // so a entrada 1,5: a madeira e gratis e as guardadas ja foram pagas
        assertThat(s.getCusto()).isEqualByComparingTo("1.5");
        assertThat(s.getRestantes().get(TipoFlecha.WOOD)).isEqualTo(5);
        assertThat(s.getRestantes().get(TipoFlecha.BTC)).isEqualTo(2);
        assertThat(s.getCompradas().total()).isEqualTo(1);
        assertThat(doBosque.getFlechas().total()).isZero();
        assertThat(deMercado.getFlechas().total()).isZero();
        verify(aljavas).save(doBosque);
        verify(aljavas).save(deMercado);
    }

    @Test
    void comMaisGuardadasQueOLimiteAbreSemComprar() {
        guardada("pico", Map.of(TipoFlecha.WOOD, 30));
        guardada(AljavaGuardada.MERCADO, Map.of(TipoFlecha.ETH, 11));

        assertThatThrownBy(() -> service.criar("u1", "pico", Map.of("wood", 1)))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessage("A aljava comporta no maximo 40 flechas e voce ja tem 41 guardadas");
        assertThatThrownBy(() -> service.criar("u1", "pico", Map.of("wood", 41)))
                .hasMessage("Escolha de 1 a 40 flechas");

        SessaoFlecha s = service.criar("u1", "pico", Map.of("wood", 0));
        assertThat(s.getRestantes().total()).isEqualTo(41);
        assertThat(s.getCusto()).isEqualByComparingTo("4");
    }

    @Test
    void encerrarGuardaAsComunsNoCampoEDevolveAsDeMercadoComATaxa() {
        // sessao aberta antes de o ferro sair do jogo: o ferro que sobrou vai para a guarda, para o reembolso
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.PICO,
                Aljava.de(Map.of(TipoFlecha.WOOD, 2, TipoFlecha.OURO, 1)), Aljava.antigas(1, 0, 0), agora);
        when(repositorio.findByIdAndUsuarioId(s.getId(), "u1")).thenReturn(Optional.of(s));
        AljavaGuardada doPico = guardada("pico", Map.of(TipoFlecha.WOOD, 1));

        service.encerrar("u1", s.getId());

        assertThat(doPico.getFlechas().get(TipoFlecha.WOOD)).isEqualTo(3);
        assertThat(doPico.getFlechas().antigas()).isEqualTo(1);
        // as de mercado nao vao mais para a guarda
        ArgumentCaptor<AljavaGuardada> salvas = ArgumentCaptor.forClass(AljavaGuardada.class);
        verify(aljavas, times(1)).save(salvas.capture());
        assertThat(salvas.getAllValues()).extracting(AljavaGuardada::getEscopo).containsExactly("pico");
        assertThat(s.getRestantes().total()).isZero();
        // a flecha de ouro de 10 volta com a taxa padrao (0,2%): 9,98, somada ao resultado
        assertThat(s.getDevolucaoQuantidade()).isEqualTo(1);
        assertThat(s.getDevolucaoValor()).isEqualByComparingTo("9.98");
        ArgumentCaptor<SessaoFlechaEncerradaEvento> evento = ArgumentCaptor.forClass(SessaoFlechaEncerradaEvento.class);
        verify(publisher).publicarSessaoFlecha(evento.capture());
        // custo 4 + 10; resultado = 0 + 9,98 - 14
        assertThat(evento.getValue().valor()).isEqualByComparingTo("14");
        assertThat(evento.getValue().valorLiquido()).isEqualByComparingTo("-4.02");
        assertThat(evento.getValue().resultado()).isEqualTo("PERDEU");
    }

    @Test
    void guardadasTemTodoCampoEIgnoraEscopoDesconhecido() {
        AljavaGuardada doVilarejo = new AljavaGuardada("u1", "vilarejo", agora);
        doVilarejo.guardar(Aljava.de(Map.of(TipoFlecha.WOOD, 5)), agora);
        AljavaGuardada deMercado = new AljavaGuardada("u1", AljavaGuardada.MERCADO, agora);
        deMercado.guardar(Aljava.de(Map.of(TipoFlecha.ETH, 2)), agora);
        AljavaGuardada estranha = new AljavaGuardada("u1", "lua", agora);
        estranha.guardar(Aljava.de(Map.of(TipoFlecha.WOOD, 9)), agora);
        when(aljavas.findByUsuarioId("u1")).thenReturn(List.of(doVilarejo, deMercado, estranha));

        SessaoFlechaService.Guardadas g = service.guardadas("u1");

        assertThat(g.porCampo()).hasSize(4);
        assertThat(g.porCampo().get(Campo.VILAREJO).get(TipoFlecha.WOOD)).isEqualTo(5);
        assertThat(g.porCampo().get(Campo.RESERVA_REAL).total()).isZero();
        assertThat(g.mercado().get(TipoFlecha.ETH)).isEqualTo(2);
    }

    @Test
    void flechaDeMercadoNaoPassaPelosTiros() {
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.VILAREJO,
                Aljava.de(Map.of(TipoFlecha.BTC, 1)), agora);
        when(repositorio.findByIdAndUsuarioId(s.getId(), "u1")).thenReturn(Optional.of(s));
        assertThatThrownBy(() -> service.disparar("u1", s.getId(), "btc", List.of("btc")))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessage("Flechas de mercado sao registradas pelo estudo");
        assertThat(s.getRestantes().get(TipoFlecha.BTC)).isEqualTo(1);
    }

    @Test
    void tiroSemAcertosInformadosValeComoFlechaPerdida() {
        SessaoFlecha s = aberta(agora);
        SessaoFlechaService.ResultadoTiro r = service.disparar("u1", s.getId(), "wood", null);
        assertThat(r.ganhosTiro()).isZero();
        assertThat(r.sessao().getDisparadas().get(TipoFlecha.WOOD)).isEqualTo(1);
    }

    @Test
    void resultadoZeroTambemVaiParaACarteiraComoEmpate() {
        // reserva real: 10 + 2 bitcoin de 10 (20); as apostas pagaram 20,60 e 9,40
        SessaoFlecha s = new SessaoFlecha(UUID.randomUUID(), "u1", Campo.RESERVA_REAL,
                Aljava.de(Map.of(TipoFlecha.BTC, 2)), agora);
        when(repositorio.findByIdAndUsuarioId(s.getId(), "u1")).thenReturn(Optional.of(s));
        s.usarFlechaDeMercado(TipoFlecha.BTC, agora);
        s.usarFlechaDeMercado(TipoFlecha.BTC, agora);
        s.receberApostaDeMercado(new BigDecimal("20.60"), true);
        s.receberApostaDeMercado(new BigDecimal("9.40"), false);

        SessaoFlecha encerrada = service.encerrar("u1", s.getId());

        assertThat(encerrada.isAberta()).isFalse();
        ArgumentCaptor<SessaoFlechaEncerradaEvento> evento = ArgumentCaptor.forClass(SessaoFlechaEncerradaEvento.class);
        verify(publisher).publicarSessaoFlecha(evento.capture());
        // a carteira aplica 0 e registra empate (EMPATE_RODADA), sem mexer no saldo
        assertThat(evento.getValue().resultado()).isEqualTo("EMPATOU");
        assertThat(evento.getValue().valorLiquido()).isEqualByComparingTo("0");
        assertThat(evento.getValue().valor()).isEqualByComparingTo("30");
        assertThat(evento.getValue().ganhos()).isEqualByComparingTo("30");
        assertThat(evento.getValue().acertos()).isEqualTo(1);
        verify(aljavas, never()).save(any());
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
        // so a entrada: a madeira e gratis
        assertThat(evento.getValue().valor()).isEqualByComparingTo("0.5");
        assertThat(evento.getValue().valorLiquido()).isEqualByComparingTo("-0.5");
        assertThat(evento.getValue().executadaEm()).isEqualTo(agora);
        // a madeira que sobrou fica guardada no vilarejo
        ArgumentCaptor<AljavaGuardada> guardada = ArgumentCaptor.forClass(AljavaGuardada.class);
        verify(aljavas).save(guardada.capture());
        assertThat(guardada.getValue().getEscopo()).isEqualTo("vilarejo");
        assertThat(guardada.getValue().getFlechas().get(TipoFlecha.WOOD)).isEqualTo(1);
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
    void abreComLotesDeMercadoDeValorEscolhido() {
        // pico: 4 + 3 bitcoin de 10 + 1 ethereum de 25,50 + 2 V100 de 30 = 119,50; madeira gratis
        SessaoFlecha s = service.criar("u1", "pico", Map.of("wood", 2, "btc", 1), List.of(
                new PedidoLote("btc", new BigDecimal("10"), 2),
                new PedidoLote("eth", new BigDecimal("25.5"), 1),
                new PedidoLote("v100", new BigDecimal("30.00"), 2),
                new PedidoLote("ouro", new BigDecimal("20"), 0)));

        assertThat(s.getCusto()).isEqualByComparingTo("119.50");
        // a bitcoin de cliente antigo (em flechas) entra no lote de 10 junto com as do mercado
        assertThat(s.getLotesMercado()).containsExactly(
                new LoteFlecha(TipoFlecha.BTC, BigDecimal.TEN, 3),
                new LoteFlecha(TipoFlecha.ETH, new BigDecimal("25.50"), 1),
                new LoteFlecha(TipoFlecha.V100, new BigDecimal("30"), 2));
        assertThat(s.getRestantes().total()).isEqualTo(8);
        assertThat(s.getCompradas().get(TipoFlecha.OURO)).isZero();
        verify(wallet).saldo("u1");

        // sem teto: um valor grande so esbarra no saldo (1000 < 0,5 + 999,99)
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", Map.of(),
                List.of(new PedidoLote("btc", new BigDecimal("999.99"), 1))))
                .isInstanceOf(RegraNegocioException.class).hasMessageStartingWith("Saldo insuficiente");
        when(wallet.saldo("u3")).thenReturn(new BigDecimal("1000000"));
        assertThat(service.criar("u3", "vilarejo", Map.of(), List.of(new PedidoLote("btc", new BigDecimal("12345.67"), 2)))
                .getCusto()).isEqualByComparingTo("24691.84");
    }

    @Test
    void valorDaFlechaDeMercadoTemMinimoECasas() {
        Map<PedidoLote, String> invalidos = new java.util.LinkedHashMap<>();
        invalidos.put(new PedidoLote("btc", null, 1), "Informe o valor das flechas Bitcoin");
        invalidos.put(new PedidoLote("btc", new BigDecimal("0.99"), 1),
                "O valor de uma flecha Bitcoin e de pelo menos 1 moeda, com ate 2 casas: 0.99");
        invalidos.put(new PedidoLote("btc", new BigDecimal("10.005"), 1),
                "O valor de uma flecha Bitcoin e de pelo menos 1 moeda, com ate 2 casas: 10.005");
        invalidos.put(new PedidoLote("btc", BigDecimal.TEN, null), "Quantidade invalida de flechas btc");
        invalidos.put(new PedidoLote("btc", BigDecimal.TEN, -1), "Quantidade invalida de flechas btc");
        invalidos.put(new PedidoLote("wood", BigDecimal.TEN, 1), "Flechas Madeira nao tem valor: peca-as em flechas");
        invalidos.put(new PedidoLote("laser", BigDecimal.TEN, 1), "Flecha desconhecida: laser");
        invalidos.put(new PedidoLote(null, BigDecimal.TEN, 1), "Flecha desconhecida: null");
        invalidos.put(new PedidoLote("eth", BigDecimal.TEN, 1), "Flechas Ethereum nao sao vendidas no Campo do Vilarejo");
        invalidos.put(new PedidoLote("btc", BigDecimal.TEN, 41), "Escolha de 1 a 40 flechas");
        invalidos.forEach((lote, mensagem) -> assertThatThrownBy(() -> service.criar("u1", "vilarejo",
                Map.of("wood", 1), List.of(lote))).as("lote %s", lote)
                .isInstanceOf(RegraNegocioException.class).hasMessage(mensagem));
        // madeira + lotes passando de 40 tambem
        assertThatThrownBy(() -> service.criar("u1", "vilarejo", Map.of("wood", 30),
                List.of(new PedidoLote("btc", BigDecimal.ONE, 11)))).hasMessage("Escolha de 1 a 40 flechas");
        verify(wallet, never()).saldo(any());

        // 1 moeda e 2 casas valem; zeros a direita nao contam como casas
        SessaoFlecha s = service.criar("u1", "vilarejo", Map.of(), List.of(
                new PedidoLote("btc", new BigDecimal("1"), 1), new PedidoLote("btc", new BigDecimal("1.230"), 1)));
        assertThat(s.getCusto()).isEqualByComparingTo("2.73");
        assertThat(s.getLotesMercado()).extracting(LoteFlecha::valor)
                .containsExactly(new BigDecimal("1.00"), new BigDecimal("1.23"));
    }

    @Test
    void compraDeMercadoNoMeioDaSessaoPedeOValor() {
        SessaoFlecha s = aberta(agora);
        assertThatThrownBy(() -> service.comprar("u1", s.getId(), "btc", 2, null))
                .isInstanceOf(RegraNegocioException.class).hasMessage("Informe o valor das flechas Bitcoin");
        assertThatThrownBy(() -> service.comprar("u1", s.getId(), "btc", 2, new BigDecimal("0.5")))
                .isInstanceOf(RegraNegocioException.class);
        verify(wallet, never()).saldo(any());

        SessaoFlecha depois = service.comprar("u1", s.getId(), "btc", 2, new BigDecimal("20"));

        // 0,5 + 2 x 20
        assertThat(depois.getCusto()).isEqualByComparingTo("40.5");
        assertThat(depois.getLotesMercado()).containsExactly(new LoteFlecha(TipoFlecha.BTC, new BigDecimal("20"), 2));
        verify(wallet).saldo("u1");
    }

    @Test
    void flechasDeMercadoGuardadasDoPrecoAntigoEntramComoLotesDeCemESaoDevolvidas() {
        AljavaGuardada deMercado = guardada(AljavaGuardada.MERCADO, Map.of(TipoFlecha.BTC, 2));

        SessaoFlecha s = service.criar("u1", "vilarejo", Map.of(), List.of(new PedidoLote("btc", BigDecimal.TEN, 1)));

        assertThat(s.getCusto()).isEqualByComparingTo("10.5");
        assertThat(s.getLotesMercado()).containsExactly(
                new LoteFlecha(TipoFlecha.BTC, BigDecimal.TEN, 1),
                new LoteFlecha(TipoFlecha.BTC, new BigDecimal("100"), 2));
        assertThat(deMercado.getFlechas().total()).isZero();
        when(repositorio.findByIdAndUsuarioId(s.getId(), "u1")).thenReturn(Optional.of(s));
        clearInvocations(aljavas);

        service.encerrar("u1", s.getId());

        // 10 + 2 x 100 = 210, menos 0,2% = 209,58; nada volta para a guarda de mercado
        assertThat(s.getDevolucaoBruto()).isEqualByComparingTo("210");
        assertThat(s.getDevolucaoTaxa()).isEqualByComparingTo("0.42");
        assertThat(s.getDevolucaoValor()).isEqualByComparingTo("209.58");
        verify(aljavas, never()).save(any());
        ArgumentCaptor<SessaoFlechaEncerradaEvento> evento = ArgumentCaptor.forClass(SessaoFlechaEncerradaEvento.class);
        verify(publisher).publicarSessaoFlecha(evento.capture());
        assertThat(evento.getValue().valorLiquido()).isEqualByComparingTo("199.08");
        assertThat(evento.getValue().resultado()).isEqualTo("GANHOU");
    }

    @Test
    void agendadorDelegaAoServico() {
        SessaoFlechaService mockService = mock(SessaoFlechaService.class);
        new EncerradorSessoesFlecha(mockService).executar();
        verify(mockService).encerrarOciosas();
        verify(mockService).reembolsarFlechasAntigas();
    }
}
