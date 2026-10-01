package com.tigrinhotrader.trading.servico;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tigrinhotrader.trading.cliente.MarketDataClient;
import com.tigrinhotrader.trading.cliente.WalletClient;
import com.tigrinhotrader.trading.dominio.Ordem;
import com.tigrinhotrader.trading.dominio.OrdemRepository;
import com.tigrinhotrader.trading.dominio.StatusOrdem;
import com.tigrinhotrader.trading.dominio.TipoOrdem;
import com.tigrinhotrader.trading.estrategia.EstrategiaAlta;
import com.tigrinhotrader.trading.estrategia.EstrategiaBaixa;
import com.tigrinhotrader.trading.estrategia.EstrategiaBarreira;
import com.tigrinhotrader.trading.estrategia.CalculadoraBarreira;
import com.tigrinhotrader.trading.estrategia.EstrategiaLateral;
import com.tigrinhotrader.trading.estrategia.RegistroEstrategias;
import com.tigrinhotrader.trading.fabrica.OrdemFactory;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaEvento;
import com.tigrinhotrader.trading.mensageria.OrdemExecutadaPublisher;
import com.tigrinhotrader.trading.mensageria.PrecoAtualizadoEvento;
import com.tigrinhotrader.trading.preco.PrecoCache;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Testa as regras do servico isoladas de banco, fila e HTTP. */
class OrdemServiceTest {

    private final Instant agora = Instant.parse("2026-09-30T12:00:00Z");
    private final RegistroEstrategias estrategias =
            new RegistroEstrategias(List.of(new EstrategiaAlta(), new EstrategiaBaixa(), new EstrategiaLateral(),
                    new EstrategiaBarreira()));

    private OrdemRepository repositorio;
    private PrecoCache precoCache;
    private MarketDataClient marketData;
    private WalletClient wallet;
    private OrdemExecutadaPublisher publisher;
    private OrdemService service;

    /** Gerenciador de transacao "de mentira" so pra o TransactionTemplate rodar sem banco. */
    static class TransacaoFalsa extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
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
        repositorio = mock(OrdemRepository.class);
        precoCache = new PrecoCache();
        marketData = mock(MarketDataClient.class);
        wallet = mock(WalletClient.class);
        publisher = mock(OrdemExecutadaPublisher.class);
        service = new OrdemService(repositorio, new OrdemFactory(estrategias), estrategias, precoCache, marketData,
                wallet, publisher, new TransacaoFalsa(), Clock.fixed(agora, ZoneOffset.UTC));
        when(repositorio.save(any(Ordem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repositorio.somarValorEmAberto("u1")).thenReturn(BigDecimal.ZERO);
    }

    private void preco(String simbolo, String valor) {
        precoCache.atualizar(new PrecoAtualizadoEvento(simbolo, new BigDecimal(valor), BigDecimal.ZERO, null));
    }

    @Test
    void criaOrdemComPrecoDaFila() {
        preco("BTCUSDT", "100");
        when(wallet.saldo("u1")).thenReturn(new BigDecimal("1000"));

        Ordem ordem = service.criar("u1", "btcusdt", TipoOrdem.ALTA, new BigDecimal("50"), 30);

        assertThat(ordem.getPrecoEntrada()).isEqualByComparingTo("100");
        assertThat(ordem.getExpiraEm()).isEqualTo(agora.plusSeconds(30));
        verify(marketData, never()).precoAtual(any());
    }

    @Test
    void usaMarketDataQuandoFilaAindaNaoTemPreco() {
        when(marketData.precoAtual("ETHUSDT")).thenReturn(Optional.of(new BigDecimal("3000")));
        when(wallet.saldo("u1")).thenReturn(new BigDecimal("1000"));

        assertThat(service.criar("u1", "ETHUSDT", TipoOrdem.BAIXA, BigDecimal.TEN, 15).getPrecoEntrada())
                .isEqualByComparingTo("3000");
    }

    @Test
    void semCotacaoNenhumaFicaIndisponivel() {
        when(marketData.precoAtual("ETHUSDT")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.criar("u1", "ETHUSDT", TipoOrdem.ALTA, BigDecimal.TEN, 15))
                .isInstanceOf(ServicoIndisponivelException.class);
    }

    @Test
    void recusaApostaMaiorQueSaldoMenosRodadasAbertas() {
        preco("BTCUSDT", "100");
        when(wallet.saldo("u1")).thenReturn(new BigDecimal("100"));
        when(repositorio.somarValorEmAberto("u1")).thenReturn(new BigDecimal("60"));

        assertThatThrownBy(() -> service.criar("u1", "BTCUSDT", TipoOrdem.ALTA, new BigDecimal("41"), 30))
                .isInstanceOf(RegraNegocioException.class)
                .hasMessageContaining("40");
        verify(repositorio, never()).save(any());
    }

    @Test
    void buscarOrdemDeOutroUsuarioDa404() {
        UUID id = UUID.randomUUID();
        when(repositorio.findByIdAndUsuarioId(id, "u2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.buscar("u2", id)).isInstanceOf(RecursoNaoEncontradoException.class);
    }

    private Ordem vencida(TipoOrdem tipo, String simbolo) {
        Ordem o = new Ordem(UUID.randomUUID(), "u1", simbolo, tipo, new BigDecimal("100.00"),
                estrategias.para(tipo).multiplicador(), new BigDecimal("100"), 30, agora.minusSeconds(31));
        when(repositorio.findById(o.getId())).thenReturn(Optional.of(o));
        return o;
    }

    private Ordem barreira(String alvo) {
        return Ordem.barreira(UUID.randomUUID(), "u1", "BTCUSDT", new BigDecimal(alvo), new BigDecimal("10.00"),
                new BigDecimal("2.00"), new BigDecimal("100"), 30, agora.minusSeconds(31));
    }

    @Test
    void criaBarreiraComVolatilidadePadraoEnquantoNaoHaHistorico() {
        preco("BTCUSDT", "100");
        when(wallet.saldo("u1")).thenReturn(new BigDecimal("1000"));

        Ordem ordem = service.criarBarreira("u1", "btcusdt", new BigDecimal("100.05"), BigDecimal.TEN, 30);

        assertThat(ordem.getTipo()).isEqualTo(TipoOrdem.BARREIRA);
        assertThat(ordem.getAlvo()).isEqualByComparingTo("100.05");
        assertThat(ordem.getMultiplicador()).isGreaterThan(BigDecimal.ONE);
        assertThat(service.volatilidade("BTCUSDT")).isEqualTo(CalculadoraBarreira.VOLATILIDADE_PADRAO);
    }

    @Test
    void barreiraTocadaPerdeNaHoraEAsOutrasSeguem() {
        Ordem acima = barreira("101");
        Ordem abaixo = barreira("99");
        when(repositorio.findBySimboloAndTipoAndStatus("BTCUSDT", TipoOrdem.BARREIRA, StatusOrdem.ABERTA))
                .thenReturn(List.of(acima, abaixo));
        when(repositorio.findById(acima.getId())).thenReturn(Optional.of(acima));

        assertThat(service.verificarBarreiras("btcusdt", new BigDecimal("101.2"))).isEqualTo(1);

        assertThat(acima.getStatus()).isEqualTo(StatusOrdem.PERDEU);
        assertThat(acima.getPrecoSaida()).isEqualByComparingTo("101.2");
        assertThat(acima.valorLiquido()).isEqualByComparingTo("-10.00");
        assertThat(abaixo.isAberta()).isTrue();
        verify(publisher).publicar(any(OrdemExecutadaEvento.class));
    }

    @Test
    void barreiraJaFechadaOuComFalhaNaoContaComoTocada() {
        Ordem jaFechada = barreira("101");
        Ordem comFalha = barreira("101");
        when(repositorio.findBySimboloAndTipoAndStatus("BTCUSDT", TipoOrdem.BARREIRA, StatusOrdem.ABERTA))
                .thenReturn(List.of(jaFechada, comFalha));
        when(repositorio.findById(jaFechada.getId())).thenReturn(Optional.empty());
        when(repositorio.findById(comFalha.getId())).thenReturn(Optional.of(comFalha));
        doThrow(new AmqpConnectException(new RuntimeException("sem broker"))).when(publisher).publicar(any());

        assertThat(service.verificarBarreiras("BTCUSDT", new BigDecimal("102"))).isZero();
    }

    @Test
    void barreiraQueSobreviveAteOFimGanha() {
        Ordem sobreviveu = barreira("101");
        when(repositorio.findByStatusAndExpiraEmLessThanEqual(StatusOrdem.ABERTA, agora)).thenReturn(List.of(sobreviveu));
        when(repositorio.findById(sobreviveu.getId())).thenReturn(Optional.of(sobreviveu));
        preco("BTCUSDT", "100.7");

        assertThat(service.resolverVencidas()).isEqualTo(1);

        assertThat(sobreviveu.getStatus()).isEqualTo(StatusOrdem.GANHOU);
        assertThat(sobreviveu.valorLiquido()).isEqualByComparingTo("10.00");
    }

    @Test
    void resolveVencidasEPublicaResultado() {
        Ordem ganha = vencida(TipoOrdem.ALTA, "BTCUSDT");
        Ordem perde = vencida(TipoOrdem.BAIXA, "BTCUSDT");
        when(repositorio.findByStatusAndExpiraEmLessThanEqual(StatusOrdem.ABERTA, agora)).thenReturn(List.of(ganha, perde));
        preco("BTCUSDT", "110");

        assertThat(service.resolverVencidas()).isEqualTo(2);

        ArgumentCaptor<OrdemExecutadaEvento> eventos = ArgumentCaptor.forClass(OrdemExecutadaEvento.class);
        verify(publisher, org.mockito.Mockito.times(2)).publicar(eventos.capture());
        assertThat(eventos.getAllValues()).extracting(OrdemExecutadaEvento::resultado)
                .containsExactly(StatusOrdem.GANHOU, StatusOrdem.PERDEU);
        assertThat(eventos.getAllValues()).extracting(OrdemExecutadaEvento::valorLiquido)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("90.00"), new BigDecimal("-100.00"));
        assertThat(eventos.getValue().executadaEm()).isEqualTo(agora);
    }

    @Test
    void ordemSemPrecoOuJaResolvidaFicaParaDepois() {
        Ordem semPreco = vencida(TipoOrdem.ALTA, "SOLUSDT");
        Ordem jaFechada = vencida(TipoOrdem.ALTA, "BTCUSDT");
        jaFechada.resolver(StatusOrdem.PERDEU, BigDecimal.ONE, agora);
        UUID sumiu = UUID.randomUUID();
        Ordem fantasma = new Ordem(sumiu, "u1", "BTCUSDT", TipoOrdem.ALTA, BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, 15, agora.minusSeconds(60));
        when(repositorio.findById(sumiu)).thenReturn(Optional.empty());
        when(repositorio.findByStatusAndExpiraEmLessThanEqual(StatusOrdem.ABERTA, agora))
                .thenReturn(List.of(semPreco, jaFechada, fantasma));
        preco("BTCUSDT", "100");

        assertThat(service.resolverVencidas()).isZero();
        assertThat(semPreco.isAberta()).isTrue();
        verify(publisher, never()).publicar(any());
    }

    @Test
    void falhaNaFilaNaoInterrompeAsOutrasOrdens() {
        Ordem primeira = vencida(TipoOrdem.ALTA, "BTCUSDT");
        Ordem segunda = vencida(TipoOrdem.ALTA, "BTCUSDT");
        when(repositorio.findByStatusAndExpiraEmLessThanEqual(StatusOrdem.ABERTA, agora))
                .thenReturn(List.of(primeira, segunda));
        preco("BTCUSDT", "100");
        doThrow(new AmqpConnectException(new RuntimeException("sem broker")))
                .doNothing()
                .when(publisher).publicar(any());

        assertThat(service.resolverVencidas()).isEqualTo(1);
    }

    @Test
    void resolvedorAgendadoDelegaAoServico() {
        OrdemService mockService = mock(OrdemService.class);
        new ResolvedorRodadas(mockService).executar();
        verify(mockService).resolverVencidas();
    }
}
