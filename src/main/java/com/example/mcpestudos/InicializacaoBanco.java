package com.example.mcpestudos;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Passos de inicializacao que nao cabem em schema.sql/data.sql. Roda depois deles e e idempotente.
 * <ul>
 *   <li>Migra bancos antigos: {@code CREATE TABLE IF NOT EXISTS} nao acrescenta colunas a uma tabela
 *       que ja existe, e o SQLite nao tem {@code ADD COLUMN IF NOT EXISTS}.</li>
 *   <li>Cria a consulta de exemplo com datas calculadas pelo {@link Clock} (fuso da clinica), em vez de
 *       {@code date('now')} do SQLite, que e UTC.</li>
 *   <li>Torna o registro de acessos ao historico (tabela {@code acessos_ao_historico}) somente de acrescimo, com
 *       gatilhos que recusam UPDATE e DELETE. Gatilho tem {@code ;} dentro de {@code BEGIN...END}, e o leitor de
 *       script do Spring corta o comando ali; por isso nao pode ficar em schema.sql.</li>
 * </ul>
 */
@Component
class InicializacaoBanco implements ApplicationRunner {

    private final JdbcClient jdbc;
    private final Clock clock;

    InicializacaoBanco(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        adicionarColunaSeAusente("motivo_cancelamento");
        adicionarColunaSeAusente("cancelada_em");
        protegerRegistroDeAcessos();
        semearConsultaDeExemplo();
    }

    /** O registro de acessos so recebe INSERT: corrigir ou apagar uma linha esconderia um acesso. */
    private void protegerRegistroDeAcessos() {
        for (String operacao : List.of("UPDATE", "DELETE")) {
            // 'operacao' vem de uma lista fixa deste metodo, nunca de entrada externa.
            jdbc.sql("CREATE TRIGGER IF NOT EXISTS trg_acessos_ao_historico_sem_" + operacao.toLowerCase()
                    + " BEFORE " + operacao + " ON acessos_ao_historico BEGIN "
                    + "SELECT RAISE(ABORT, 'acessos_ao_historico e somente de acrescimo'); END").update();
        }
    }

    private void adicionarColunaSeAusente(String coluna) {
        List<String> existentes = jdbc.sql("PRAGMA table_info(consultas)")
                .query((rs, n) -> rs.getString("name")).list();
        if (!existentes.contains(coluna)) {
            // 'coluna' vem de constantes deste metodo, nunca de entrada externa.
            jdbc.sql("ALTER TABLE consultas ADD COLUMN " + coluna + " TEXT").update();
        }
    }

    private void semearConsultaDeExemplo() {
        Integer total = jdbc.sql("SELECT COUNT(*) FROM consultas").query(Integer.class).single();
        if (total > 0) {
            return;
        }
        LocalDate dia = LocalDate.now(clock).plusDays(1);
        while (dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
            dia = dia.plusDays(1);
        }
        // Com foreign_keys ativo, o INSERT falharia (e derrubaria a inicializacao) se o dentista 1 ou o
        // paciente 1 nao existissem mais; por isso so semeia quando os dois estao la.
        jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                        + "SELECT 1, 1, :data, '09:00', 'Limpeza' "
                        + "WHERE EXISTS (SELECT 1 FROM dentistas WHERE id = 1) "
                        + "AND EXISTS (SELECT 1 FROM pacientes WHERE id = 1)")
                .param("data", dia.toString())
                .update();
    }
}
