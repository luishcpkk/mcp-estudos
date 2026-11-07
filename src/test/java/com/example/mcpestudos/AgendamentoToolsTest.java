package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** SQLite real em arquivo temporario, com a mesma URL (foreign_keys=true) da aplicacao. */
class AgendamentoToolsTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Segunda-feira, 2026-11-09, 10:30 em Sao Paulo. */
    private static final Instant AGORA = Instant.parse("2026-11-09T13:30:00Z");
    private static final String HOJE = "2026-11-09";
    private static final String TERCA = "2026-11-10";

    @TempDir
    Path dir;

    JdbcClient jdbc;
    AgendamentoTools tools;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("teste.db") + "?foreign_keys=true");
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"),
                new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(ds);
        jdbc = JdbcClient.create(ds);
        tools = new AgendamentoTools(jdbc, Clock.fixed(AGORA, SP));
    }

    // ---------- banco ----------

    @Test
    void chaveEstrangeiraEhAplicada() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, "
                + "procedimento) VALUES (99, 1, '2026-11-10', '09:00', 'X')").update())
                .isInstanceOfAny(DataIntegrityViolationException.class, UncategorizedSQLException.class)
                .hasMessageContaining("FOREIGN KEY");
    }

    // ---------- Clock ----------

    @Test
    void dataDeHojeUsaOClock() {
        DataAtual hoje = tools.dataDeHoje();
        assertThat(hoje.data()).isEqualTo(HOJE);
        assertThat(hoje.diaDaSemana()).isEqualToIgnoringCase("segunda-feira");
    }

    // ---------- buscarPaciente ----------

    @Test
    void buscaExigeTamanhoMinimo() {
        for (String termo : new String[] {null, "", "  ", "ma", " ma "}) {
            Resultado<List<Paciente>> r = tools.buscarPaciente(termo);
            assertThat(r.sucesso()).as("termo=%s", termo).isFalse();
            assertThat(r.erro()).contains("3 caracteres");
        }
    }

    @Test
    void buscaEncontraPacientes() {
        Resultado<List<Paciente>> r = tools.buscarPaciente("Maria");
        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados()).extracting(Paciente::nome)
                .containsExactly("Maria Fernanda Costa", "Maria Oliveira");
    }

    @Test
    void buscaNaoTrataCuringasComoPadrao() {
        assertThat(tools.buscarPaciente("%%%").dados()).isEmpty();
        assertThat(tools.buscarPaciente("___").dados()).isEmpty();
    }

    @Test
    void buscaLimitaA20Resultados() {
        for (int i = 0; i < 25; i++) {
            jdbc.sql("INSERT INTO pacientes (nome) VALUES (:n)").param("n", "Teste " + i).update();
        }
        assertThat(tools.buscarPaciente("Teste").dados()).hasSize(AgendamentoTools.LIMITE_BUSCA);
    }

    // ---------- horariosLivres ----------

    @Test
    void horariosLivresDeHojeIgnoramOsQueJaPassaram() {
        // Sao 10:30: 08:00, 09:00 e 10:00 ja passaram.
        assertThat(tools.horariosLivres(1L, HOJE).dados().horariosLivres())
                .containsExactly("11:00", "14:00", "15:00", "16:00", "17:00");
    }

    @Test
    void horariosLivresDeDiaFuturoListamTodosMenosOsOcupados() {
        assertThat(tools.horariosLivres(1L, TERCA).dados().horariosLivres()).hasSize(8);
        tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza");
        assertThat(tools.horariosLivres(1L, TERCA).dados().horariosLivres())
                .hasSize(7).doesNotContain("09:00");
    }

    @Test
    void horariosLivresRecusaEntradasInvalidas() {
        assertThat(tools.horariosLivres(1L, "10/11/2026").erro()).contains("Data invalida");
        assertThat(tools.horariosLivres(99L, TERCA).erro()).contains("Dentista nao encontrado");
        assertThat(tools.horariosLivres(1L, "2026-11-14").erro()).contains("fins de semana");
        assertThat(tools.horariosLivres(1L, "2026-11-06").erro()).contains("ja passou");
    }

    // ---------- agendarConsulta ----------

    @Test
    void agendaComSucessoEDevolveOId() {
        Resultado<AgendamentoConfirmado> r = tools.agendarConsulta(1L, 1L, TERCA, " 09:00 ", " Limpeza ");
        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().consultaId()).isPositive();
        assertThat(r.dados()).extracting(AgendamentoConfirmado::data, AgendamentoConfirmado::hora,
                AgendamentoConfirmado::procedimento).containsExactly(TERCA, "09:00", "Limpeza");
    }

    @Test
    void horarioOcupadoViraRecusaDeNegocio() {
        assertThat(tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza").sucesso()).isTrue();
        // Sem checagem previa: aqui quem recusa e a violacao do indice UNIQUE.
        Resultado<AgendamentoConfirmado> r = tools.agendarConsulta(1L, 2L, TERCA, "09:00", "Canal");
        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("ocupado");
    }

    @Test
    void mesmoHorarioEmOutroDentistaEPermitido() {
        assertThat(tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza").sucesso()).isTrue();
        assertThat(tools.agendarConsulta(2L, 2L, TERCA, "09:00", "Avaliacao").sucesso()).isTrue();
    }

    @Test
    void recusaHorarioDeHojeQueJaPassou() {
        assertThat(tools.agendarConsulta(1L, 1L, HOJE, "10:00", "Limpeza").erro()).contains("ja passou");
        assertThat(tools.agendarConsulta(1L, 1L, HOJE, "11:00", "Limpeza").sucesso()).isTrue();
    }

    @Test
    void recusaEntradasInvalidas() {
        assertThat(tools.agendarConsulta(1L, 1L, "x", "09:00", "Limpeza").erro()).contains("Data invalida");
        assertThat(tools.agendarConsulta(1L, 1L, TERCA, "09:30", "Limpeza").erro()).contains("Horario invalido");
        assertThat(tools.agendarConsulta(1L, 1L, TERCA, "9:00", "Limpeza").erro()).contains("Horario invalido");
        assertThat(tools.agendarConsulta(1L, 1L, TERCA, null, "Limpeza").erro()).contains("Horario invalido");
        assertThat(tools.agendarConsulta(1L, 1L, "2026-11-14", "09:00", "Limpeza").erro()).contains("fins de semana");
        assertThat(tools.agendarConsulta(99L, 1L, TERCA, "09:00", "Limpeza").erro()).contains("Dentista");
        assertThat(tools.agendarConsulta(1L, 99L, TERCA, "09:00", "Limpeza").erro()).contains("Paciente");
        assertThat(tools.agendarConsulta(1L, 1L, TERCA, "09:00", "  ").erro()).contains("procedimento");
    }

    // ---------- cancelarConsulta ----------

    @Test
    void cancelaSemApagarERegistraMotivoEData() {
        long id = tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza").dados().consultaId();

        Resultado<CancelamentoConfirmado> r = tools.cancelarConsulta(id, "  Paciente viajou  ");

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados().status()).isEqualTo("CANCELADA");
        var linha = jdbc.sql("SELECT status, motivo_cancelamento, cancelada_em FROM consultas WHERE id = :id")
                .param("id", id).query().singleRow();
        assertThat(linha).containsEntry("status", "CANCELADA")
                .containsEntry("motivo_cancelamento", "Paciente viajou")
                .containsEntry("cancelada_em", "2026-11-09T10:30");
        // Linha preservada e horario liberado para novo agendamento.
        assertThat(tools.consultasDoPaciente(1L).dados()).isEmpty();
        assertThat(tools.agendarConsulta(1L, 2L, TERCA, "09:00", "Canal").sucesso()).isTrue();
    }

    @Test
    void cancelarSemMotivoGravaNulo() {
        long id = tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza").dados().consultaId();
        assertThat(tools.cancelarConsulta(id, null).sucesso()).isTrue();
        assertThat(jdbc.sql("SELECT motivo_cancelamento FROM consultas WHERE id = :id").param("id", id)
                .query(String.class).optional()).isEmpty();
    }

    @Test
    void naoCancelaDuasVezes() {
        long id = tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza").dados().consultaId();
        assertThat(tools.cancelarConsulta(id, null).sucesso()).isTrue();
        assertThat(tools.cancelarConsulta(id, null).erro()).contains("nao esta agendada");
    }

    @Test
    void naoCancelaConsultaQueJaPassouNemInexistente() {
        // Hoje 10:00 (antes de agora, 10:30) e ontem: inseridos direto, pois agendar recusaria.
        jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                + "VALUES (1, 1, :d, '10:00', 'Limpeza')").param("d", HOJE).update();
        jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                + "VALUES (1, 1, :d, '09:00', 'Limpeza')")
                .param("d", LocalDate.parse(HOJE).minusDays(1).toString()).update();
        List<Long> ids = jdbc.sql("SELECT id FROM consultas ORDER BY id").query(Long.class).list();

        for (long id : ids) {
            assertThat(tools.cancelarConsulta(id, null).erro()).contains("consultas futuras");
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM consultas WHERE status = 'AGENDADA'")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(tools.cancelarConsulta(9999L, null).erro()).contains("nao encontrada");
    }

    @Test
    void recusaMotivoLongoDemais() {
        long id = tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Limpeza").dados().consultaId();
        String longo = "x".repeat(AgendamentoTools.TAMANHO_MAXIMO_MOTIVO + 1);
        assertThat(tools.cancelarConsulta(id, longo).erro()).contains("no maximo");
        assertThat(tools.consultasDoPaciente(1L).dados()).hasSize(1);
    }

    // ---------- consultasDoPaciente ----------

    private void inserirConsulta(long dentistaId, long pacienteId, String data, String hora, String status) {
        jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento, status) "
                        + "VALUES (:d, :p, :data, :hora, 'Limpeza', :s)")
                .param("d", dentistaId).param("p", pacienteId).param("data", data)
                .param("hora", hora).param("s", status).update();
    }

    @Test
    void historicoMostraPassadasComoRealizadasEFuturasComoAgendadas() {
        // Agora: 2026-11-09 10:30. Passadas sao inseridas direto, pois agendar recusaria.
        inserirConsulta(1, 1, "2026-10-01", "09:00", "AGENDADA"); // meses atras
        inserirConsulta(1, 1, HOJE, "10:00", "AGENDADA");         // hoje, 30 min atras
        inserirConsulta(1, 1, HOJE, "11:00", "AGENDADA");         // hoje, daqui a 30 min
        tools.agendarConsulta(1L, 1L, TERCA, "09:00", "Canal");   // amanha

        List<ConsultaResumo> consultas = tools.consultasDoPaciente(1L).dados();

        assertThat(consultas).extracting(ConsultaResumo::data, ConsultaResumo::hora, ConsultaResumo::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-10-01", "09:00", "REALIZADA"),
                        org.assertj.core.groups.Tuple.tuple(HOJE, "10:00", "REALIZADA"),
                        org.assertj.core.groups.Tuple.tuple(HOJE, "11:00", "AGENDADA"),
                        org.assertj.core.groups.Tuple.tuple(TERCA, "09:00", "AGENDADA"));
    }

    @Test
    void consultaNoInstanteExatoDeAgoraJaContaComoRealizada() {
        inserirConsulta(1, 1, HOJE, "10:30", "AGENDADA");
        // 10:30 nao e horario de atendimento, mas o criterio e o mesmo de jaPassou: inicio nao e futuro.
        assertThat(tools.consultasDoPaciente(1L).dados()).extracting(ConsultaResumo::status)
                .containsExactly("REALIZADA");
    }

    @Test
    void historicoNaoMudaOStatusGravadoNoBanco() {
        inserirConsulta(1, 1, "2026-10-01", "09:00", "AGENDADA");
        tools.consultasDoPaciente(1L);
        assertThat(jdbc.sql("SELECT status FROM consultas").query(String.class).single()).isEqualTo("AGENDADA");
    }

    @Test
    void historicoNaoMostraCanceladasNemConsultasDeOutroPaciente() {
        inserirConsulta(1, 1, "2026-10-01", "09:00", "CANCELADA");
        inserirConsulta(1, 2, "2026-10-02", "09:00", "AGENDADA");
        assertThat(tools.consultasDoPaciente(1L).dados()).isEmpty();
        assertThat(tools.consultasDoPaciente(2L).dados()).hasSize(1);
    }

    // ---------- IDs nulos (Long) ----------

    @Test
    void idsNulosViramRecusaClaraEmVezDeZero() {
        assertThat(tools.horariosLivres(null, TERCA).erro()).contains("ID do dentista");
        assertThat(tools.agendarConsulta(null, 1L, TERCA, "09:00", "Limpeza").erro()).contains("ID do dentista");
        assertThat(tools.agendarConsulta(1L, null, TERCA, "09:00", "Limpeza").erro()).contains("ID do paciente");
        assertThat(tools.consultasDoPaciente(null).erro()).contains("ID do paciente");
        assertThat(tools.cancelarConsulta(null, null).erro()).contains("ID da consulta");
    }
}
