package com.tigrinhotrader.trading.painel;

/** De onde os servicos leem as regras em vigor (as do painel do super-admin, ou as do ambiente). */
@FunctionalInterface
public interface RegrasVigentes {

    Configuracao atual();
}
