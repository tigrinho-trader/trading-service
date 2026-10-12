package com.tigrinhotrader.trading.estudo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Uma previsao de um jogador (uma flecha de mercado: bitcoin, ethereum ou ouro) e a operacao feita com ela. */
@Entity
@Table(name = "operacoes_estudo", indexes = {
        @Index(name = "idx_operacoes_estudo_usuario", columnList = "usuarioId, criadaEm"),
        @Index(name = "idx_operacoes_estudo_sessao", columnList = "sessaoId"),
        @Index(name = "idx_operacoes_estudo_status", columnList = "status, criadaEm")})
public class OperacaoEstudo {

    private static final int MOTIVO_MAXIMO = 300;

    @Id
    private UUID id;

    /** Wrapper: nulo enquanto nova (o Spring Data usa isso para persistir em vez de mesclar). */
    @Version
    private Long versao;

    @Column(nullable = false)
    private String usuarioId;

    @Column(nullable = false)
    private UUID sessaoId;

    /** Par negociado (BTCUSDT, ETHUSDT, PAXGUSDT). Nulo em operacoes de antes dos outros ativos: eram todas BTC. */
    @Column(length = 12)
    private String simbolo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direcao direcao;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ModoEstudo modo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatusOperacao status;

    @Column(length = MOTIVO_MAXIMO)
    private String motivo;

    /** Nula em operacoes de antes da preferencia: eram todas de previsao de preco. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private TipoPrevisao tipo;

    /** O que o jogador viu ao mirar: preco de agora e o preco que ele previu para daqui a 5 s. */
    @Column(precision = 18, scale = 8)
    private BigDecimal precoAgoraJogo;
    @Column(precision = 18, scale = 8)
    private BigDecimal precoPrevistoJogo;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal nocionalBrl;
    @Column(precision = 12, scale = 4)
    private BigDecimal cambio;

    // abertura (guardada inteira para fechar mesmo depois de o servico reiniciar)
    @Column(length = 64)
    private String ordemEntrada;
    @Column(precision = 18, scale = 8)
    private BigDecimal quantidadeBtc;
    @Column(precision = 18, scale = 8)
    private BigDecimal precoEntrada;
    @Column(precision = 18, scale = 8)
    private BigDecimal fluxoUsdtEntrada;
    @Column(precision = 18, scale = 8)
    private BigDecimal fluxoBtcEntrada;
    @Column(precision = 18, scale = 8)
    private BigDecimal taxaUsdtEntrada;
    @Column(precision = 18, scale = 8)
    private BigDecimal taxaOutraEntrada;

    // fechamento
    @Column(length = 64)
    private String ordemSaida;
    @Column(precision = 18, scale = 8)
    private BigDecimal precoSaida;
    @Column(precision = 14, scale = 4)
    private BigDecimal resultadoBrutoBrl;
    @Column(precision = 14, scale = 4)
    private BigDecimal taxasBrl;
    @Column(precision = 14, scale = 4)
    private BigDecimal resultadoLiquidoBrl;
    private Boolean acertouDirecao;
    /** Placar da aposta binaria (+aposta no acerto, -aposta no erro): metrica do estudo, nao dinheiro real. */
    @Column(precision = 14, scale = 4)
    private BigDecimal resultadoBinarioBrl;

    @Column(nullable = false)
    private int tentativasFechar;

    @Column(nullable = false)
    private Instant criadaEm;
    private Instant abertaEm;
    private Instant fechadaEm;

    protected OperacaoEstudo() {
        // JPA
    }

    public static OperacaoEstudo nova(String usuarioId, UUID sessaoId, String simbolo, Direcao direcao,
                                      TipoPrevisao tipo, ModoEstudo modo, BigDecimal nocionalBrl,
                                      BigDecimal precoAgora, BigDecimal precoPrevisto, Instant agora) {
        OperacaoEstudo o = new OperacaoEstudo();
        o.id = UUID.randomUUID();
        o.usuarioId = usuarioId;
        o.sessaoId = sessaoId;
        o.simbolo = simbolo;
        o.direcao = direcao;
        o.tipo = tipo;
        o.modo = modo;
        o.status = StatusOperacao.ABRINDO;
        o.nocionalBrl = nocionalBrl;
        o.precoAgoraJogo = precoAgora;
        o.precoPrevistoJogo = precoPrevisto;
        o.criadaEm = agora;
        return o;
    }

    public void registrarAbertura(Execucao e, BigDecimal cambioUsado, Instant agora) {
        status = StatusOperacao.ABERTA;
        cambio = cambioUsado;
        ordemEntrada = e.ordemId();
        quantidadeBtc = e.quantidadeBtc();
        precoEntrada = e.precoMedio();
        fluxoUsdtEntrada = e.fluxoUsdt();
        fluxoBtcEntrada = e.fluxoBtc();
        taxaUsdtEntrada = e.taxaUsdt();
        taxaOutraEntrada = e.taxaOutraUsdt();
        abertaEm = agora;
    }

    public Execucao abertura() {
        return new Execucao(ordemEntrada, quantidadeBtc, precoEntrada, fluxoUsdtEntrada, fluxoBtcEntrada,
                taxaUsdtEntrada, taxaOutraEntrada);
    }

    public void fechar(Execucao saida, ResultadoOperacao resultado, Instant agora) {
        status = StatusOperacao.FECHADA;
        ordemSaida = saida.ordemId();
        precoSaida = saida.precoMedio();
        resultadoBrutoBrl = resultado.brutoBrl();
        taxasBrl = resultado.taxasBrl();
        resultadoLiquidoBrl = resultado.liquidoBrl();
        acertouDirecao = resultado.acertou();
        resultadoBinarioBrl = resultado.binarioBrl();
        fechadaEm = agora;
    }

    public void recusar(String porque, Instant agora) {
        terminar(StatusOperacao.RECUSADA, porque, agora);
    }

    public void falhar(String porque, Instant agora) {
        terminar(StatusOperacao.FALHOU, porque, agora);
    }

    public void contarTentativaFechar() {
        tentativasFechar++;
    }

    private void terminar(StatusOperacao final_, String porque, Instant agora) {
        status = final_;
        motivo = porque == null ? null
                : porque.length() > MOTIVO_MAXIMO ? porque.substring(0, MOTIVO_MAXIMO) : porque;
        fechadaEm = agora;
    }

    public UUID getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public UUID getSessaoId() {
        return sessaoId;
    }

    public String getSimbolo() {
        return simbolo == null ? AtivoEstudo.BTC.simbolo() : simbolo;
    }

    public Direcao getDirecao() {
        return direcao;
    }

    public TipoPrevisao getTipo() {
        return tipo == null ? TipoPrevisao.PREVISAO : tipo;
    }

    public BigDecimal getResultadoBinarioBrl() {
        return resultadoBinarioBrl;
    }

    public ModoEstudo getModo() {
        return modo;
    }

    public StatusOperacao getStatus() {
        return status;
    }

    public String getMotivo() {
        return motivo;
    }

    public BigDecimal getPrecoAgoraJogo() {
        return precoAgoraJogo;
    }

    public BigDecimal getPrecoPrevistoJogo() {
        return precoPrevistoJogo;
    }

    public BigDecimal getNocionalBrl() {
        return nocionalBrl;
    }

    public BigDecimal getCambio() {
        return cambio;
    }

    public BigDecimal getQuantidadeBtc() {
        return quantidadeBtc;
    }

    public BigDecimal getPrecoEntrada() {
        return precoEntrada;
    }

    public BigDecimal getPrecoSaida() {
        return precoSaida;
    }

    public BigDecimal getResultadoBrutoBrl() {
        return resultadoBrutoBrl;
    }

    public BigDecimal getTaxasBrl() {
        return taxasBrl;
    }

    public BigDecimal getResultadoLiquidoBrl() {
        return resultadoLiquidoBrl;
    }

    public Boolean getAcertouDirecao() {
        return acertouDirecao;
    }

    public int getTentativasFechar() {
        return tentativasFechar;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getAbertaEm() {
        return abertaEm;
    }

    public Instant getFechadaEm() {
        return fechadaEm;
    }
}
