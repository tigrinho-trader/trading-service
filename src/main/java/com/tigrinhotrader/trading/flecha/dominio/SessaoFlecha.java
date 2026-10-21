package com.tigrinhotrader.trading.flecha.dominio;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.Passaro;
import com.tigrinhotrader.trading.flecha.regras.RegrasFlecha;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Uma partida do Flecha Certeira: o jogador paga entrada + flechas ao abrir (e ao comprar mais), cada tiro soma
 * as moedas dos passaros (hoje 0: so conta os acertos) e, ao encerrar, o resultado (ganhos - custo) vai para a
 * carteira pelo evento ordem.executada (resultado zero tambem: a carteira registra como empate). As flechas
 * guardadas de rodadas anteriores entram na aljava sem custo (ja foram pagas) e as comuns que sobram voltam para a
 * guarda. Flechas de mercado nao sao disparadas aqui: o estudo gasta a flecha e soma o pagamento da aposta aos
 * ganhos.
 *
 * <p>As flechas de mercado ficam em lotes por tipo e valor ({@link LotesMercado}, coluna {@code lotes_mercado});
 * as aljavas {@code compradas/restantes/disparadas} continuam com os totais por tipo. As de mercado que sobram nao
 * sao guardadas: ao encerrar voltam em moedas, valor x quantidade menos a taxa de devolucao, somadas ao resultado.
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
            @AttributeOverride(name = "btc", column = @Column(name = "compradas_btc", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "eth", column = @Column(name = "compradas_eth", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "ouro", column = @Column(name = "compradas_ouro", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "v100", column = @Column(name = "compradas_v100", columnDefinition = "integer default 0 not null"))
    })
    private Aljava compradas;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "wood", column = @Column(name = "restantes_wood", nullable = false)),
            @AttributeOverride(name = "iron", column = @Column(name = "restantes_iron", nullable = false)),
            @AttributeOverride(name = "gold", column = @Column(name = "restantes_gold", nullable = false)),
            @AttributeOverride(name = "diamond", column = @Column(name = "restantes_diamond", nullable = false)),
            @AttributeOverride(name = "btc", column = @Column(name = "restantes_btc", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "eth", column = @Column(name = "restantes_eth", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "ouro", column = @Column(name = "restantes_ouro", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "v100", column = @Column(name = "restantes_v100", columnDefinition = "integer default 0 not null"))
    })
    private Aljava restantes;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "wood", column = @Column(name = "disparadas_wood", nullable = false)),
            @AttributeOverride(name = "iron", column = @Column(name = "disparadas_iron", nullable = false)),
            @AttributeOverride(name = "gold", column = @Column(name = "disparadas_gold", nullable = false)),
            @AttributeOverride(name = "diamond", column = @Column(name = "disparadas_diamond", nullable = false)),
            @AttributeOverride(name = "btc", column = @Column(name = "disparadas_btc", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "eth", column = @Column(name = "disparadas_eth", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "ouro", column = @Column(name = "disparadas_ouro", columnDefinition = "integer default 0 not null")),
            @AttributeOverride(name = "v100", column = @Column(name = "disparadas_v100", columnDefinition = "integer default 0 not null"))
    })
    private Aljava disparadas;

    /**
     * Lotes das flechas de mercado restantes, em JSON. Default '[]': a coluna entra numa tabela que ja tem linhas;
     * as flechas de mercado dessas linhas (sem lote) contam como lotes do valor antigo ({@link #lotes()}).
     */
    @Column(name = "lotes_mercado", columnDefinition = "varchar(4000) default '[]' not null")
    private String lotesMercado;

    // devolucao das flechas de mercado que sobraram ao encerrar (zeros antes de encerrar ou sem sobra); default 0
    // porque as colunas entram numa tabela que ja tem linhas
    @Column(name = "devolucao_quantidade", columnDefinition = "integer default 0 not null")
    private int devolucaoQuantidade;
    @Column(name = "devolucao_bruto", columnDefinition = "numeric(14,2) default 0 not null")
    private BigDecimal devolucaoBruto;
    @Column(name = "devolucao_taxa", columnDefinition = "numeric(14,2) default 0 not null")
    private BigDecimal devolucaoTaxa;

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

    /**
     * Abre a sessao cobrando entrada + flechas pedidas; as de mercado do {@code pedido} viram lotes do valor padrao
     * ({@link RegrasFlecha#VALOR_FLECHA_PADRAO}), como as de um cliente antigo.
     */
    public SessaoFlecha(UUID id, String usuarioId, Campo campo, Aljava pedido, Instant agora) {
        this(id, usuarioId, campo, pedido, Aljava.vazia(), agora);
    }

    /** Igual, com as {@code guardadas} (pagas em rodadas anteriores) entrando na aljava sem custo. */
    public SessaoFlecha(UUID id, String usuarioId, Campo campo, Aljava pedido, Aljava guardadas, Instant agora) {
        this(id, usuarioId, campo, pedido.comuns(), lotesPadrao(pedido.deMercado()), guardadas, agora);
    }

    /**
     * Abre a sessao cobrando entrada + flechas comuns + valor x quantidade de cada lote de mercado. As
     * {@code guardadas} (pagas em rodadas anteriores) entram na aljava sem custo e sem contar como compradas; as de
     * mercado entre elas viram lotes do valor antigo ({@link RegrasFlecha#VALOR_FLECHA_ANTIGA}).
     */
    public SessaoFlecha(UUID id, String usuarioId, Campo campo, Aljava comuns, List<LoteFlecha> mercado,
                        Aljava guardadas, Instant agora) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.usuarioAberta = usuarioId;
        this.campo = campo.slug();
        this.status = StatusSessao.ABERTA;
        this.custo = dinheiro(custoAbertura(campo, comuns, mercado));
        this.ganhos = dinheiro(BigDecimal.ZERO);
        this.compradas = comuns.comuns();
        LotesMercado lotes = new LotesMercado();
        for (LoteFlecha lote : mercado) {
            lotes.somar(lote.flecha(), lote.valor(), lote.quantidade());
            compradas.somar(lote.flecha(), lote.quantidade());
        }
        this.restantes = compradas.copia();
        this.restantes.somarTudo(guardadas);
        this.lotesMercado = lotes.json();
        // as de mercado guardadas (sem lote) viram lotes do valor antigo
        this.lotesMercado = lotes().json();
        this.disparadas = Aljava.vazia();
        this.devolucaoBruto = dinheiro(BigDecimal.ZERO);
        this.devolucaoTaxa = dinheiro(BigDecimal.ZERO);
        this.acertos = 0;
        this.criadaEm = agora;
        this.ultimaAtividadeEm = agora;
    }

    /** Entrada do campo + preco das flechas comuns + valor x quantidade dos lotes de mercado. */
    public static BigDecimal custoAbertura(Campo campo, Aljava comuns, List<LoteFlecha> mercado) {
        BigDecimal soma = campo.entrada().add(comuns.custo(campo));
        for (LoteFlecha lote : mercado) {
            soma = soma.add(lote.total());
        }
        return soma;
    }

    /** Lotes do valor padrao com as flechas de mercado de uma aljava (pedido de cliente antigo). */
    public static List<LoteFlecha> lotesPadrao(Aljava mercado) {
        List<LoteFlecha> lotes = new ArrayList<>();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            if (tipo.deMercado() && mercado.get(tipo) > 0) {
                lotes.add(new LoteFlecha(tipo, RegrasFlecha.VALOR_FLECHA_PADRAO, mercado.get(tipo)));
            }
        }
        return lotes;
    }

    /**
     * Os lotes gravados mais, para cada tipo de mercado com mais flechas restantes do que nos lotes (sessao aberta
     * antes dos lotes, ou flechas guardadas do preco fixo antigo), um lote do valor antigo com a diferenca.
     */
    private LotesMercado lotes() {
        LotesMercado lotes = LotesMercado.ler(lotesMercado);
        for (TipoFlecha tipo : TipoFlecha.values()) {
            int semLote = tipo.deMercado() ? restantes.get(tipo) - lotes.quantidade(tipo) : 0;
            lotes.somar(tipo, RegrasFlecha.VALOR_FLECHA_ANTIGA, semLote);
        }
        return lotes;
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

    /**
     * Gasta uma flecha de mercado do tipo e valor na operacao do estudo (a aposta ja foi paga na compra da flecha).
     * Sem valor (cliente antigo) gasta a de menor valor do tipo.
     *
     * @return o valor da flecha gasta (2 casas): a aposta e o tamanho da operacao
     * @throws IllegalStateException se a sessao esta encerrada, a flecha nao e de mercado ou nao ha desse valor
     */
    public BigDecimal usarFlechaDeMercado(TipoFlecha flecha, BigDecimal valor, Instant agora) {
        exigirAberta();
        LotesMercado lotes = lotes();
        Optional<BigDecimal> escolhido = valor == null ? lotes.menorValor(flecha) : Optional.of(valor);
        if (escolhido.isEmpty() || !lotes.tirar(flecha, escolhido.get())) {
            throw new IllegalStateException("Sem flecha de mercado " + flecha.id() + " de " + valor);
        }
        lotesMercado = lotes.json();
        restantes.somar(flecha, -1);
        disparadas.somar(flecha, 1);
        ultimaAtividadeEm = agora;
        return dinheiro(escolhido.get());
    }

    /** Gasta a flecha de mercado de menor valor do tipo. */
    public BigDecimal usarFlechaDeMercado(TipoFlecha flecha, Instant agora) {
        return usarFlechaDeMercado(flecha, null, agora);
    }

    /** Ha flecha de mercado do tipo com este valor na aljava? */
    public boolean temFlechaDeMercado(TipoFlecha flecha, BigDecimal valor) {
        return lotes().tem(flecha, valor);
    }

    /** Lotes das flechas de mercado restantes, por tipo e depois por valor. */
    public List<LoteFlecha> getLotesMercado() {
        return lotes().lista();
    }

    /**
     * Soma aos ganhos o pagamento da aposta de uma flecha de mercado (com centavos: segue o resultado liquido da
     * operacao); acertar a direcao conta como um acerto.
     */
    public void receberApostaDeMercado(BigDecimal moedas, boolean acertou) {
        exigirAberta();
        ganhos = dinheiro(ganhos.add(moedas));
        if (acertou) {
            acertos++;
        }
    }

    /**
     * Compra mais flechas durante a sessao: soma ao custo e as compradas/restantes. Flecha de mercado entra no lote
     * do {@code valor} (sem valor, o padrao) e custa valor x quantidade; nas comuns o valor e ignorado.
     *
     * @return quanto a compra somou ao custo
     */
    public BigDecimal comprar(TipoFlecha flecha, int quantidade, BigDecimal valor, Instant agora) {
        exigirAberta();
        BigDecimal extra;
        if (flecha.deMercado()) {
            LoteFlecha lote = new LoteFlecha(flecha, valor == null ? RegrasFlecha.VALOR_FLECHA_PADRAO : valor,
                    quantidade);
            LotesMercado lotes = lotes();
            lotes.somar(flecha, lote.valor(), quantidade);
            lotesMercado = lotes.json();
            extra = lote.total();
        } else {
            extra = getCampo().custoFlecha(flecha).multiply(BigDecimal.valueOf(quantidade));
        }
        custo = dinheiro(custo.add(extra));
        compradas.somar(flecha, quantidade);
        restantes.somar(flecha, quantidade);
        ultimaAtividadeEm = agora;
        return extra;
    }

    /** Compra flechas comuns (ou de mercado do valor padrao). */
    public BigDecimal comprar(TipoFlecha flecha, int quantidade, Instant agora) {
        return comprar(flecha, quantidade, null, agora);
    }

    /**
     * Encerra a sessao e tira da aljava as flechas que sobraram. As comuns (e as antigas de uma sessao aberta antes
     * de elas sairem do jogo) voltam para o servico guardar; as de mercado viram a devolucao: valor x quantidade de
     * cada lote menos {@code taxaDevolucao} (fracao, ex. 0.002), em centavos HALF_UP, somada ao resultado.
     *
     * @return as flechas comuns que sobraram
     */
    public Aljava encerrar(Instant agora, BigDecimal taxaDevolucao) {
        exigirAberta();
        LotesMercado lotes = lotes();
        BigDecimal bruto = dinheiro(lotes.valorTotal());
        BigDecimal devolvido = dinheiro(bruto.multiply(BigDecimal.ONE.subtract(taxaDevolucao)));
        devolucaoQuantidade = lotes.quantidade();
        devolucaoBruto = bruto;
        devolucaoTaxa = bruto.subtract(devolvido);
        lotesMercado = LotesMercado.VAZIO;
        status = StatusSessao.ENCERRADA;
        encerradaEm = agora;
        usuarioAberta = null;
        return restantes.esvaziarTudo().comuns();
    }

    /** Encerra sem taxa na devolucao das flechas de mercado. */
    public Aljava encerrar(Instant agora) {
        return encerrar(agora, BigDecimal.ZERO);
    }

    private void exigirAberta() {
        if (!isAberta()) {
            throw new IllegalStateException("Sessao " + id + " ja encerrada");
        }
    }

    /** Lucro (positivo) ou prejuizo (negativo) da sessao ate agora: ganhos + devolucao - custo. */
    public BigDecimal resultado() {
        return ganhos.add(getDevolucaoValor()).subtract(custo);
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

    /** Flechas de mercado devolvidas ao encerrar. */
    public int getDevolucaoQuantidade() {
        return devolucaoQuantidade;
    }

    /** Valor x quantidade das flechas de mercado devolvidas, antes da taxa. */
    public BigDecimal getDevolucaoBruto() {
        return devolucaoBruto;
    }

    /** Taxa descontada da devolucao, em moedas. */
    public BigDecimal getDevolucaoTaxa() {
        return devolucaoTaxa;
    }

    /** O que voltou em moedas: bruto - taxa. */
    public BigDecimal getDevolucaoValor() {
        return devolucaoBruto.subtract(devolucaoTaxa);
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
