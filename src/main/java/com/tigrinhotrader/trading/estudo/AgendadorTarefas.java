package com.tigrinhotrader.trading.estudo;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

/** Agendador proprio do estudo (nao e o TaskScheduler do Spring, para nao disputar com os @Scheduled). */
@Component
public class AgendadorTarefas implements Agendador {

    private static final Logger LOG = LoggerFactory.getLogger(AgendadorTarefas.class);

    private final ThreadPoolTaskScheduler fila = new ThreadPoolTaskScheduler();

    public AgendadorTarefas() {
        fila.setPoolSize(4);
        fila.setThreadNamePrefix("estudo-");
        fila.initialize();
    }

    @Override
    public void executar(Runnable tarefa) {
        fila.execute(protegida(tarefa));
    }

    @Override
    public void agendar(Runnable tarefa, Instant quando) {
        fila.schedule(protegida(tarefa), quando);
    }

    @PreDestroy
    void desligar() {
        fila.shutdown();
    }

    static Runnable protegida(Runnable tarefa) {
        return () -> {
            try {
                tarefa.run();
            } catch (RuntimeException e) {
                LOG.error("Tarefa do estudo falhou", e);
            }
        };
    }
}
