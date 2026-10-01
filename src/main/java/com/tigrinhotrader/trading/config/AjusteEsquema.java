package com.tigrinhotrader.trading.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O Hibernate 6 cria um CHECK com os valores do enum na coluna {@code tipo}, e o {@code ddl-auto: update} nao
 * atualiza constraints que ja existem. Bancos criados antes do tipo BARREIRA recusariam a ordem nova, entao a
 * constraint antiga sai na subida (o enum do Java continua validando). Idempotente; vira migration se o projeto
 * adotar Flyway.
 */
@Component
public class AjusteEsquema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AjusteEsquema.class);

    private final JdbcTemplate jdbc;

    public AjusteEsquema(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("alter table ordens drop constraint if exists ordens_tipo_check");
        } catch (DataAccessException e) {
            log.warn("Nao consegui remover ordens_tipo_check: {}", e.getMessage());
        }
    }
}
