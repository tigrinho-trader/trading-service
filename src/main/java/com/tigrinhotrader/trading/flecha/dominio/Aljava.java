package com.tigrinhotrader.trading.flecha.dominio;

import com.tigrinhotrader.trading.flecha.regras.Campo;
import com.tigrinhotrader.trading.flecha.regras.TipoFlecha;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quantidade de flechas por tipo. Usada 3 vezes na sessao (compradas, restantes e disparadas) e na aljava guardada
 * do jogador.
 *
 * <p>As colunas iron, gold e diamond sao das flechas antigas de ferro, ouro e diamante, removidas do jogo: ficam so
 * para as linhas de antes (e as novas gravam 0). Nao contam no total, nao aparecem nos mapas e as guardadas sao
 * reembolsadas pelo preco pago ({@link #valorAntigas(Campo)}).
 */
@Embeddable
public class Aljava {

    /** Fatores de preco das flechas antigas (x preco base do campo), iguais aos de quando eram vendidas. */
    static final int FATOR_FERRO = 2;
    static final int FATOR_OURO = 4;
    static final int FATOR_DIAMANTE = 8;

    @Column(nullable = false)
    private int wood;
    @Column(nullable = false)
    private int iron;
    @Column(nullable = false)
    private int gold;
    @Column(nullable = false)
    private int diamond;
    @Column(nullable = false)
    private int btc;
    @Column(nullable = false)
    private int eth;
    @Column(nullable = false)
    private int ouro;
    @Column(nullable = false)
    private int v100;

    protected Aljava() {
        // JPA
    }

    public static Aljava vazia() {
        return new Aljava();
    }

    public static Aljava de(Map<TipoFlecha, Integer> quantidades) {
        Aljava aljava = new Aljava();
        quantidades.forEach(aljava::somar);
        return aljava;
    }

    /** Aljava so com flechas antigas (ferro, ouro, diamante), como as guardadas antes de elas sairem do jogo. */
    public static Aljava antigas(int ferro, int ouro, int diamante) {
        Aljava aljava = new Aljava();
        aljava.iron = ferro;
        aljava.gold = ouro;
        aljava.diamond = diamante;
        return aljava;
    }

    /** Nova aljava com a soma das flechas das duas. */
    public static Aljava soma(Aljava uma, Aljava outra) {
        Aljava soma = uma.copia();
        soma.somarTudo(outra);
        return soma;
    }

    public Aljava copia() {
        Aljava c = new Aljava();
        c.wood = wood;
        c.iron = iron;
        c.gold = gold;
        c.diamond = diamond;
        c.btc = btc;
        c.eth = eth;
        c.ouro = ouro;
        c.v100 = v100;
        return c;
    }

    public int get(TipoFlecha tipo) {
        return switch (tipo) {
            case WOOD -> wood;
            case BTC -> btc;
            case ETH -> eth;
            case OURO -> ouro;
            case V100 -> v100;
        };
    }

    /** Soma (ou tira, com quantidade negativa) flechas de um tipo. */
    void somar(TipoFlecha tipo, int quantidade) {
        switch (tipo) {
            case WOOD -> wood += quantidade;
            case BTC -> btc += quantidade;
            case ETH -> eth += quantidade;
            case OURO -> ouro += quantidade;
            case V100 -> v100 += quantidade;
        }
    }

    /** Soma todas as flechas de outra aljava a esta, as antigas inclusive. */
    void somarTudo(Aljava outra) {
        for (TipoFlecha tipo : TipoFlecha.values()) {
            somar(tipo, outra.get(tipo));
        }
        iron += outra.iron;
        gold += outra.gold;
        diamond += outra.diamond;
    }

    /** Zera as flechas do jogo e devolve o que havia; as antigas ficam (para o reembolso). */
    Aljava esvaziar() {
        Aljava havia = Aljava.vazia();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            havia.somar(tipo, get(tipo));
            somar(tipo, -get(tipo));
        }
        return havia;
    }

    /** Zera tudo, as antigas inclusive, e devolve o que havia. */
    Aljava esvaziarTudo() {
        Aljava havia = copia();
        esvaziar();
        zerarAntigas();
        return havia;
    }

    /** Quantas flechas antigas (ferro, ouro, diamante) ha. */
    public int antigas() {
        return iron + gold + diamond;
    }

    /** Quanto as flechas antigas custaram no campo: preco base x 2 (ferro), 4 (ouro) ou 8 (diamante). */
    public BigDecimal valorAntigas(Campo campo) {
        long fatores = (long) FATOR_FERRO * iron + (long) FATOR_OURO * gold + (long) FATOR_DIAMANTE * diamond;
        return BigDecimal.valueOf(fatores * campo.flechaBase());
    }

    /** Tira as flechas antigas (depois do reembolso). */
    void zerarAntigas() {
        iron = 0;
        gold = 0;
        diamond = 0;
    }

    /** So as flechas comuns (madeira e as antigas), que valem no campo onde foram compradas. */
    public Aljava comuns() {
        Aljava filtrada = filtrar(false);
        filtrada.iron = iron;
        filtrada.gold = gold;
        filtrada.diamond = diamond;
        return filtrada;
    }

    /** So as flechas de mercado (bitcoin, ethereum, ouro PAXG), que valem em qualquer campo. */
    public Aljava deMercado() {
        return filtrar(true);
    }

    private Aljava filtrar(boolean mercado) {
        Aljava filtrada = new Aljava();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            if (tipo.deMercado() == mercado) {
                filtrada.somar(tipo, get(tipo));
            }
        }
        return filtrada;
    }

    /** Flechas que podem ser usadas (as antigas nao contam). */
    public int total() {
        return wood + btc + eth + ouro + v100;
    }

    /** Quanto custam estas flechas no campo (sem a entrada). */
    public BigDecimal custo(Campo campo) {
        BigDecimal soma = BigDecimal.ZERO;
        for (TipoFlecha tipo : TipoFlecha.values()) {
            soma = soma.add(campo.custoFlecha(tipo).multiply(BigDecimal.valueOf(get(tipo))));
        }
        return soma;
    }

    /** {"wood": n, "btc": n, "eth": n, "ouro": n}, na ordem dos tipos (sem as antigas). */
    public Map<String, Integer> comoMapa() {
        Map<String, Integer> mapa = new LinkedHashMap<>();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            mapa.put(tipo.id(), get(tipo));
        }
        return mapa;
    }
}
