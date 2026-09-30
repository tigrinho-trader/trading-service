package com.tigrinhotrader.trading.estrategia;

import com.tigrinhotrader.trading.dominio.TipoOrdem;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Descobre todas as estrategias registradas no Spring e escolhe a certa pelo tipo da ordem. */
@Component
public class RegistroEstrategias {

    private final Map<TipoOrdem, EstrategiaResultado> porTipo = new EnumMap<>(TipoOrdem.class);

    public RegistroEstrategias(List<EstrategiaResultado> estrategias) {
        for (EstrategiaResultado estrategia : estrategias) {
            if (porTipo.put(estrategia.tipo(), estrategia) != null) {
                throw new IllegalStateException("Duas estrategias para o tipo " + estrategia.tipo());
            }
        }
    }

    public EstrategiaResultado para(TipoOrdem tipo) {
        EstrategiaResultado estrategia = porTipo.get(tipo);
        if (estrategia == null) {
            throw new IllegalArgumentException("Tipo de ordem sem estrategia: " + tipo);
        }
        return estrategia;
    }
}
