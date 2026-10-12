package com.tigrinhotrader.trading.flecha.dominio;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.util.List;
import java.util.UUID;

/**
 * Uma partida do Flecha Certeira: o jogador paga entrada + flechas ao abrir (e ao comprar mais), cada tiro soma
 * moedas e, ao encerrar, o resultado (ganhos - custo) vai para a carteira pelo evento ordem.executada.
 */
@Entity
@Table(name = "sessoes_flecha", indexes = {
        @Index(name = "idx_sessoes_flecha_usuario", columnList = "usuarioId"),
        @Index(name = "idx_sessoes_flecha_status_atividade", columnList = "status, ultimaAtividadeEm")
})
public class SessaoFlecha {

    @Id
    private UUID id;

    @Version
    private Long versao;

    @Column(nullable = false)
    private String usuarioId;

    /**
     * Igual a usuarioId enquanto ABERTA e nulo depois. O UNIQUE garante no banco uma sessao aberta por jogador,
     * mesmo com dois pedidos simultaneos (nulos nao conflitam).
     */
    @Column(unique = true)
    private String usuarioAberta;

    @Column(nullable = false, length = 20)
    private String campo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatusSessao status;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal custo;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal ganhos;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "wood", column = @Column(name = "compradas_wood", nullable = false)),
            @AttributeOverride(name = "iron", column = @Column(name = "compradas_iron", nullable = false)),
            @AttributeOverride(name = "gold", column = @Column(name = "compradas_gold", nullable = false)),
            @AttributeOverride(name = "diamond", column = @Column(name = "compradas_diamond", nullable = false)),
            @AttributeOverride(name = "btc", column = @Column(name = "compradas_btc", columnDefinition = "integer default 0 not null"))
    })
    private Aljava compradas;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "wood", column = @Column(name = "restantes_wood", nullable = false)),
            @AttributeOverride(name = "iron", column = @Column(name = "restantes_iron", nullable = false)),
            @AttributeOverride(name = "gold", column = @Column(name = "restantes_gold", nullable = false)),
            @AttributeOverride(name = "diamond", column = @Column(name = "restantes_diamond", nullable = false)),
            @AttributeOverride(name = "btc", column = @Column(name = "restantes_btc", columnDefinition = "integer default 0 not null"))
    })
    private Aljava restantes;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "wood", column = @Column(name = "disparadas_wood", nullable = false)),
            @AttributeOverride(name = "iron", column = @Column(name = "disparadas_iron", nullable = false)),
            @AttributeOverride(name = "gold", column = @Column(name = "disparadas_gold", nullable = false)),
            @AttributeOverride(name = "diamond", column = @Column(name = "disparadas_diamond", nullable = false)),
            @AttributeOverride(name = "btc", column = @Column(name = "disparadas_btc", columnDefinition = "integer default 0 not null"))
    })
    private Aljava disparadas;

    @Column(nullable = false)
    private int acertos;

    @Column(nullable = false)
    private Instant criadaEm;

    @Column(nullable = false)
    private Instant ultimaAtividadeEm;

    private Instant ultimoTiroEm;

    private Instant encerradaEm;

    protected SessaoFlecha() {
        // JPA
    }

    /** Abre a sessao cobrando entrada + flechas pedidas. */
    public SessaoFlecha(UUID id, String usuarioId, Campo campo, Aljava pedido, Instant agora) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.usuarioAberta = usuarioId;
        this.campo = campo.slug();
        this.status = StatusSessao.ABERTA;
        this.custo = dinheiro(custoAbertura(campo, pedido));
        this.ganhos = dinheiro(BigDecimal.ZERO);
        this.compradas = pedido.copia();
        this.restantes = pedido.copia();
        this.disparadas = Aljava.vazia();
        this.acertos = 0;
        this.criadaEm = agora;
        this.ultimaAtividadeEm = agora;
    }

    /** Entrada do campo + preco das flechas do pedido. */
    public static BigDecimal custoAbertura(Campo campo, Aljava pedido) {
        return campo.entrada().add(pedido.custo(campo));
    }

    /**
     * Gasta uma flecha do tipo e soma as moedas dos passaros que ela atravessou, na ordem (combo).
     * As regras de plausibilidade ficam no servico.
     *
     * @return moedas ganhas neste tiro
     */
    public int registrarTiro(TipoFlecha flecha, List<Passaro> atingidos, Instant agora) {
        exigirAberta();
        if (restantes.get(flecha) <= 0) {
            throw new IllegalStateException("Sem flecha " + flecha.id());
        }
        Campo c = getCampo();
        int ganhosTiro = 0;
        for (int i = 0; i < atingidos.size(); i++) {
            ganhosTiro += c.moedasAcerto(atingidos.get(i), flecha, i + 1);
        }
        restantes.somar(flecha, -1);
        disparadas.somar(flecha, 1);
        acertos += atingidos.size();
        ganhos = dinheiro(ganhos.add(BigDecimal.valueOf(ganhosTiro)));
        ultimoTiroEm = agora;
        ultimaAtividadeEm = agora;
        return ganhosTiro;
    }

    /** Compra mais flechas durante a sessao: soma ao custo e as compradas/restantes. */
    public BigDecimal comprar(TipoFlecha flecha, int quantidade, Instant agora) {
        exigirAberta();
        BigDecimal extra = getCampo().custoFlecha(flecha).multiply(BigDecimal.valueOf(quantidade));
        custo = dinheiro(custo.add(extra));
        compradas.somar(flecha, quantidade);
        restantes.somar(flecha, quantidade);
        ultimaAtividadeEm = agora;
        return extra;
    }

    public void encerrar(Instant agora) {
        exigirAberta();
        status = StatusSessao.ENCERRADA;
        encerradaEm = agora;
        usuarioAberta = null;
    }

    private void exigirAberta() {
        if (!isAberta()) {
            throw new IllegalStateException("Sessao " + id + " ja encerrada");
        }
    }

    /** Lucro (positivo) ou prejuizo (negativo) da sessao ate agora. */
    public BigDecimal resultado() {
        return ganhos.subtract(custo);
    }

    /** GANHOU / PERDEU / EMPATOU, no mesmo vocabulario das ordens. */
    public String resultadoFinal() {
        return switch (resultado().signum()) {
            case 1 -> "GANHOU";
            case -1 -> "PERDEU";
            default -> "EMPATOU";
        };
    }

    private static BigDecimal dinheiro(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isAberta() {
        return status == StatusSessao.ABERTA;
    }

    public UUID getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public Campo getCampo() {
        return Campo.porSlug(campo).orElseThrow();
    }

    public StatusSessao getStatus() {
        return status;
    }

    public BigDecimal getCusto() {
        return custo;
    }

    public BigDecimal getGanhos() {
        return ganhos;
    }

    public Aljava getCompradas() {
        return compradas;
    }

    public Aljava getRestantes() {
        return restantes;
    }

    public Aljava getDisparadas() {
        return disparadas;
    }

    public int getAcertos() {
        return acertos;
    }

    public Instant getCriadaEm() {
        return criadaEm;
    }

    public Instant getUltimaAtividadeEm() {
        return ultimaAtividadeEm;
    }

    public Instant getUltimoTiroEm() {
        return ultimoTiroEm;
    }

    public Instant getEncerradaEm() {
        return encerradaEm;
    }
}
