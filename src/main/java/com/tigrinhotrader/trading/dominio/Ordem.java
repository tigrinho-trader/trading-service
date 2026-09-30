package com.tigrinhotrader.trading.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Uma rodada/aposta do jogador. Criada pela {@code OrdemFactory}. */
@Entity
@Table(name = "ordens", indexes = {
        @Index(name = "idx_ordens_usuario", columnList = "usuarioId"),
        @Index(name = "idx_ordens_status_expira", columnList = "status, expiraEm")
})
public class Ordem {

    @Id
    private UUID id;

    @Version
    private Long versao;

    @Column(nullable = false)
    private String usuarioId;

    @Column(nullable = false, length = 20)
    private String simbolo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TipoOrdem tipo;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal valor;

    @Column(nullable = false, precision = 6, scale = 2)
    private BigDecimal multiplicador;

    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal precoEntrada;

    @Column(precision = 24, scale = 8)
    private BigDecimal precoSaida;

    @Column(nullable = false)
    private int duracaoSegundos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatusOrdem status;

    @Column(precision = 14, scale = 2)
    private BigDecimal valorPago;

    @Column(nullable = false)
    private Instant criadaEm;

    @Column(nullable = false)
    private Instant expiraEm;

    private Instant resolvidaEm;

    protected Ordem() {
        // JPA
    }

    public Ordem(UUID id, String usuarioId, String simbolo, TipoOrdem tipo, BigDecimal valor,
                 BigDecimal multiplicador, BigDecimal precoEntrada, int duracaoSegundos, Instant criadaEm) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.simbolo = simbolo;
        this.tipo = tipo;
        this.valor = valor;
        this.multiplicador = multiplicador;
        this.precoEntrada = precoEntrada;
        this.duracaoSegundos = duracaoSegundos;
        this.criadaEm = criadaEm;
        this.expiraEm = criadaEm.plusSeconds(duracaoSegundos);
        this.status = StatusOrdem.ABERTA;
    }

    /** Fecha a rodada com o resultado calculado pela estrategia e o preco de saida. */
    public void resolver(StatusOrdem resultado, BigDecimal precoSaida, Instant quando) {
        if (status != StatusOrdem.ABERTA) {
            throw new IllegalStateException("Ordem " + id + " ja foi resolvida");
        }
        if (resultado == StatusOrdem.ABERTA) {
            throw new IllegalArgumentException("Resultado precisa ser final");
        }
        this.status = resultado;
        this.precoSaida = precoSaida;
        this.resolvidaEm = quando;
        this.valorPago = switch (resultado) {
            case GANHOU -> valor.multiply(multiplicador).setScale(2, RoundingMode.HALF_UP);
            case EMPATOU -> valor;
            default -> BigDecimal.ZERO.setScale(2);
        };
    }

    /** Lucro (positivo) ou prejuizo (negativo) da rodada; zero enquanto aberta. */
    public BigDecimal valorLiquido() {
        return valorPago == null ? BigDecimal.ZERO : valorPago.subtract(valor);
    }

    public boolean isAberta() {
        return status == StatusOrdem.ABERTA;
    }

    public UUID getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public String getSimbolo() {
        return simbolo;
    }

    public TipoOrdem getTipo() {
        return tipo;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public BigDecimal getMultiplicador() {
        return multiplicador;
    }

    public BigDecimal getPrecoEntrada() {
        return precoEntrada;
    }

    public BigDecimal getPrecoSaida() {
        return precoSaida;
    }

    public int getDuracaoSegundos() {
        return duracaoSegundos;
    }

    public StatusOrdem getStatus() {
        return status;
    }

    public BigDecimal getValorPago() {
        return valorPago;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getExpiraEm() {
        return expiraEm;
    }

    public Instant getResolvidaEm() {
        return resolvidaEm;
    }
}
