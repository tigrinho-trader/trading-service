package com.tigrinhotrader.trading.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/** A conversao de moedas_pagas para numeric: so no Postgres, so se a coluna ainda for inteira, sem derrubar a subida. */
class AjusteEsquemaTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AjusteEsquema ajuste = new AjusteEsquema(jdbc);

    /** O banco responde com este nome de produto e este tipo da coluna (vazio = sem a tabela). */
    private void banco(String produto, List<String> tipoColuna) throws Exception {
        Connection conexao = mock(Connection.class);
        DatabaseMetaData metadados = mock(DatabaseMetaData.class);
        when(conexao.getMetaData()).thenReturn(metadados);
        when(metadados.getDatabaseProductName()).thenReturn(produto);
        // doAnswer/doReturn: restubar com when() chamaria a resposta anterior
        doAnswer(i -> i.<ConnectionCallback<?>>getArgument(0).doInConnection(conexao))
                .when(jdbc).execute(any(ConnectionCallback.class));
        doReturn(tipoColuna).when(jdbc).queryForList(AjusteEsquema.TIPO_MOEDAS_PAGAS, String.class);
    }

    @Test
    void postgresComAColunaInteiraConverteParaNumeric() throws Exception {
        banco("PostgreSQL", List.of("integer"));

        ajuste.run(null);

        verify(jdbc).execute("alter table ordens drop constraint if exists ordens_tipo_check");
        verify(jdbc).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
    }

    @Test
    void colunaJaConvertidaOuTabelaQueNaoExisteNaoMexe() throws Exception {
        banco("PostgreSQL", List.of("numeric"));
        ajuste.moedasPagasComCentavos();
        banco("PostgreSQL", List.of());
        ajuste.moedasPagasComCentavos();

        verify(jdbc, never()).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
    }

    @Test
    void foraDoPostgresNemConsulta() throws Exception {
        banco("H2", List.of("INTEGER"));

        ajuste.moedasPagasComCentavos();

        verify(jdbc, never()).queryForList(anyString(), eq(String.class));
        verify(jdbc, never()).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
    }

    @Test
    void falhaNoAjusteNaoDerrubaASubida() throws Exception {
        banco("PostgreSQL", List.of("integer"));
        doThrow(new DataAccessResourceFailureException("sem permissao")).when(jdbc).execute(anyString());

        assertThatCode(() -> ajuste.run(null)).doesNotThrowAnyException();

        verify(jdbc).execute(AjusteEsquema.MOEDAS_PAGAS_DECIMAL);
    }
}
