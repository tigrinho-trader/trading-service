package com.tigrinhotrader.trading.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ajustes de esquema que o {@code ddl-auto: update} nao faz. Idempotentes; viram migrations se o projeto adotar
 * Flyway.
 *
 * <p>O Hibernate 6 cria um CHECK com os valores do enum na coluna {@code tipo}, e o update nao atualiza constraints
 * que ja existem. Bancos criados antes do tipo BARREIRA recusariam a ordem nova, entao a constraint antiga sai na
 * subida (o enum do Java continua validando).
 *
 * <p>O update tambem nao muda o tipo de uma coluna: {@code operacoes_estudo.moedas_pagas} nasceu {@code integer}
 * (pagamento 200/100/0) e agora guarda centavos; {@code operacoes_estudo.aposta_moedas} nasceu {@code integer}
 * (aposta fixa de 100) e agora guarda o valor da flecha escolhido pelo jogador, com centavos. No Postgres, se ainda
 * forem inteiras, viram {@code numeric(14,2)} (sem perda: as linhas antigas ficam com os valores delas). No H2 dos
 * testes, ou sem a tabela, nao faz nada.
 */
@Component
public class AjusteEsquema implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AjusteEsquema.class);

    static final String TIPO_MOEDAS_PAGAS = "select data_type from information_schema.columns"
            + " where table_schema = current_schema() and table_name = 'operacoes_estudo'"
            + " and column_name = 'moedas_pagas'";
    static final String MOEDAS_PAGAS_DECIMAL =
            "alter table operacoes_estudo alter column moedas_pagas type numeric(14,2)";
    static final String TIPO_APOSTA_MOEDAS = "select data_type from information_schema.columns"
            + " where table_schema = current_schema() and table_name = 'operacoes_estudo'"
            + " and column_name = 'aposta_moedas'";
    static final String APOSTA_MOEDAS_DECIMAL =
            "alter table operacoes_estudo alter column aposta_moedas type numeric(14,2)";

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
        try {
            moedasPagasComCentavos();
        } catch (DataAccessException e) {
            log.warn("Nao consegui converter operacoes_estudo.moedas_pagas para numeric: {}", e.getMessage());
        }
        try {
            apostaMoedasComCentavos();
        } catch (DataAccessException e) {
            log.warn("Nao consegui converter operacoes_estudo.aposta_moedas para numeric: {}", e.getMessage());
        }
    }

    /** Converte {@code moedas_pagas} de integer para numeric(14,2), so no Postgres e so se ainda for inteira. */
    void moedasPagasComCentavos() {
        paraNumeric("moedas_pagas", TIPO_MOEDAS_PAGAS, MOEDAS_PAGAS_DECIMAL);
    }

    /** Converte {@code aposta_moedas} de integer para numeric(14,2), so no Postgres e so se ainda for inteira. */
    void apostaMoedasComCentavos() {
        paraNumeric("aposta_moedas", TIPO_APOSTA_MOEDAS, APOSTA_MOEDAS_DECIMAL);
    }

    private void paraNumeric(String coluna, String consultaTipo, String conversao) {
        String banco = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
        if (!"PostgreSQL".equals(banco)) {
            return;
        }
        List<String> tipo = jdbc.queryForList(consultaTipo, String.class);
        if (tipo.isEmpty() || !"integer".equalsIgnoreCase(tipo.get(0))) {
            // tabela ainda nao existe ou coluna ja convertida
            return;
        }
        jdbc.execute(conversao);
        log.info("Coluna operacoes_estudo.{} convertida de integer para numeric(14,2)", coluna);
    }
}
