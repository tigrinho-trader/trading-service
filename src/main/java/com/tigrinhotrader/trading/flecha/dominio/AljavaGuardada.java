package com.tigrinhotrader.trading.flecha.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Flechas que sobraram de rodadas anteriores, ja pagas, guardadas para o jogador usar depois. Uma linha por jogador
 * e escopo: o slug de um campo guarda as flechas comuns compradas nele (so valem naquele campo, para ninguem comprar
 * barato no Vilarejo e usar na Reserva Real) e {@link #MERCADO} guarda as flechas de mercado, que custam o mesmo em
 * todo campo e valem em qualquer um.
 */
@Entity
@Table(name = "aljavas_guardadas", uniqueConstraints = @UniqueConstraint(name = "uk_aljavas_guardadas_usuario_escopo",
        columnNames = {"usuarioId", "escopo"}))
public class AljavaGuardada {

    /** Escopo das flechas de mercado (bitcoin, ethereum, ouro PAXG). */
    public static final String MERCADO = "mercado";

    @Id
    private UUID id;

    @Version
    private Long versao;

    @Column(nullable = false)
    private String usuarioId;

    /** Slug do campo (flechas comuns) ou {@link #MERCADO}. */
    @Column(nullable = false, length = 20)
    private String escopo;

    @Embedded
    private Aljava flechas;

    @Column(nullable = false)
    private Instant atualizadaEm;

    protected AljavaGuardada() {
        // JPA
    }

    public AljavaGuardada(String usuarioId, String escopo, Instant agora) {
        this.id = UUID.randomUUID();
        this.usuarioId = usuarioId;
        this.escopo = escopo;
        this.flechas = Aljava.vazia();
        this.atualizadaEm = agora;
    }

    /** Guarda mais flechas. */
    public void guardar(Aljava sobra, Instant agora) {
        flechas.somarTudo(sobra);
        atualizadaEm = agora;
    }

    /**
     * Tira todas as flechas guardadas (vao para a aljava da sessao) e devolve o que havia. As antigas (ferro, ouro,
     * diamante) ficam, esperando o reembolso.
     */
    public Aljava retirar(Instant agora) {
        atualizadaEm = agora;
        return flechas.esvaziar();
    }

    /** Tira as flechas antigas, depois de o servico mandar o reembolso para a carteira. */
    public void zerarAntigas(Instant agora) {
        flechas.zerarAntigas();
        atualizadaEm = agora;
    }

    /**
     * Referencia do reembolso das flechas antigas desta aljava nesta versao: a mesma se a transacao voltar e o
     * reembolso for tentado de novo (a carteira e idempotente por ordemId), outra se a aljava mudar depois.
     */
    public UUID referenciaReembolso() {
        return UUID.nameUUIDFromBytes(("reembolso-flechas:" + id + ":" + versao).getBytes(StandardCharsets.UTF_8));
    }

    public UUID getId() {
        return id;
    }

    public String getUsuarioId() {
        return usuarioId;
    }

    public String getEscopo() {
        return escopo;
    }

    public Aljava getFlechas() {
        return flechas;
    }
}
