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
 */
@Embeddable
public class Aljava {

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
        return c;
    }

    public int get(TipoFlecha tipo) {
        return switch (tipo) {
            case WOOD -> wood;
            case IRON -> iron;
            case GOLD -> gold;
            case DIAMOND -> diamond;
            case BTC -> btc;
            case ETH -> eth;
            case OURO -> ouro;
        };
    }

    /** Soma (ou tira, com quantidade negativa) flechas de um tipo. */
    void somar(TipoFlecha tipo, int quantidade) {
        switch (tipo) {
            case WOOD -> wood += quantidade;
            case IRON -> iron += quantidade;
            case GOLD -> gold += quantidade;
            case DIAMOND -> diamond += quantidade;
            case BTC -> btc += quantidade;
            case ETH -> eth += quantidade;
            case OURO -> ouro += quantidade;
        }
    }

    /** Soma todas as flechas de outra aljava a esta. */
    void somarTudo(Aljava outra) {
        for (TipoFlecha tipo : TipoFlecha.values()) {
            somar(tipo, outra.get(tipo));
        }
    }

    /** Zera todas as flechas e devolve o que havia. */
    Aljava esvaziar() {
        Aljava havia = copia();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            somar(tipo, -get(tipo));
        }
        return havia;
    }

    /** So as flechas comuns (madeira, ferro, ouro, diamante), que valem no campo onde foram compradas. */
    public Aljava comuns() {
        return filtrar(false);
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

    public int total() {
        return wood + iron + gold + diamond + btc + eth + ouro;
    }

    /** Quanto custam estas flechas no campo (sem a entrada). */
    public BigDecimal custo(Campo campo) {
        BigDecimal soma = BigDecimal.ZERO;
        for (TipoFlecha tipo : TipoFlecha.values()) {
            soma = soma.add(campo.custoFlecha(tipo).multiply(BigDecimal.valueOf(get(tipo))));
        }
        return soma;
    }

    /** {"wood": n, "iron": n, "gold": n, "diamond": n, "btc": n, "eth": n, "ouro": n}, na ordem dos tipos. */
    public Map<String, Integer> comoMapa() {
        Map<String, Integer> mapa = new LinkedHashMap<>();
        for (TipoFlecha tipo : TipoFlecha.values()) {
            mapa.put(tipo.id(), get(tipo));
        }
        return mapa;
    }
}
