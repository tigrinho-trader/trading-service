package com.tigrinhotrader.trading.estudo;

import java.time.Instant;

/** Roda tarefas fora da requisicao: abrir a operacao agora e fecha-la 5 s depois. Troca nos testes. */
public interface Agendador {

    void executar(Runnable tarefa);

    void agendar(Runnable tarefa, Instant quando);
}
