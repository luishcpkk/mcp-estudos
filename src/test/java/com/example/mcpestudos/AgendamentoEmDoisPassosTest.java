package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Agendar em dois passos: {@code prepararAgendamento} (valida, mostra o resumo, NAO grava) e
 * {@code confirmarAgendamento} (so com o codigo; uso unico; expira; revalida; atomico).
 *
 * <p>SQLite real e um pool de UMA conexao (Hikari), como em producao. O instante "agora" e controlado por um Clock
 * fixo; para simular a passagem do tempo entre preparar e confirmar, cria-se outra instancia das ferramentas sobre o
 * mesmo banco com outro Clock (o estado das propostas fica no banco, nao na memoria).
 */
class AgendamentoEmDoisPassosTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Segunda-feira, 2026-11-09, 10:30 em Sao Paulo. */
    private static final String AGORA = "2026-11-09T13:30:00Z";
    private static final String HOJE = "2026-11-09";
    private static final String TERCA = "2026-11-10";
    private static final String SABADO = "2026-11-14";

    @TempDir
    Path dir;

    HikariDataSource ds;
    JdbcClient jdbc;
    TransactionTemplate transacao;
    AgendamentoTools tools;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dir.resolve("agenda.db") + "?foreign_keys=true");
        config.setMaximumPoolSize(1);
        ds = new HikariDataSource(config);
        var populator = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"), new ClassPathResource("data.sql"));
        populator.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        populator.execute(ds);
        jdbc = JdbcClient.create(ds);
        transacao = new TransactionTemplate(new DataSourceTransactionManager(ds));
        tools = ferramentasEm(AGORA);
    }

    @AfterEach
    void fechar() {
        ds.close();
    }

    private AgendamentoTools ferramentasEm(String instante) {
        return new AgendamentoTools(jdbc, Clock.fixed(Instant.parse(instante), SP), transacao);
    }

    private int contar(String tabela) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tabela).query(Integer.class).single();
    }

    private String statusDaProposta(String codigo) {
        return jdbc.sql("SELECT status FROM propostas_agendamento WHERE codigo = :c").param("c", codigo)
                .query(String.class).single();
    }

    private PropostaAgendamento preparar(Long dentista, Long paciente, String data, String hora) {
        Resultado<PropostaAgendamento> r = tools.prepararAgendamento(dentista, paciente, data, hora, "Avaliacao");
        assertThat(r.sucesso()).as("preparar %s %s: %s", data, hora, r.erro()).isTrue();
        return r.dados();
    }

    // ---------- passo 1: preparar ----------

    @Test
    void prepararMostraOResumoEUmCodigoESemGravarNadaNaAgenda() {
        Resultado<PropostaAgendamento> r = tools.prepararAgendamento(2L, 1L, TERCA, "09:00", " Avaliacao ");

        assertThat(r.sucesso()).isTrue();
        PropostaAgendamento p = r.dados();
        assertThat(p.codigoConfirmacao()).matches("[2-9A-HJ-NP-Z]{8}"); // sem 0/O/1/I
        assertThat(p.resumo()).isEqualTo(
                "Consulta de Avaliacao para Maria Oliveira com Dr. Bruno Lima em 2026-11-10 (terça-feira) as 09:00.");
        assertThat(p.dentista()).isEqualTo("Dr. Bruno Lima");
        assertThat(p.paciente()).isEqualTo("Maria Oliveira");
        assertThat(p.expiraEm()).isEqualTo("2026-11-09T10:40"); // 10:30 + 10 minutos
        assertThat(p.validadeEmMinutos()).isEqualTo(10);
        assertThat(p.proximoPasso()).contains("confirmarAgendamento").contains("confirmacao explicita");

        assertThat(contar("consultas")).as("preparar NAO agenda nada").isZero();
        assertThat(contar("propostas_agendamento")).isEqualTo(1);
        assertThat(statusDaProposta(p.codigoConfirmacao())).isEqualTo("PENDENTE");
    }

    @Test
    void prepararRecusaTudoQueOAgendamentoDiretoJaRecusavaESemCriarProposta() {
        record Caso(Long dentista, Long paciente, String data, String hora, String procedimento, String erro) {
        }
        List<Caso> casos = List.of(
                new Caso(null, 1L, TERCA, "09:00", "x", "ID do dentista"),
                new Caso(2L, null, TERCA, "09:00", "x", "ID do paciente"),
                new Caso(2L, 1L, "nao-e-data", "09:00", "x", "Data invalida"),
                new Caso(2L, 1L, TERCA, "09:30", "x", "Horario invalido"),
                new Caso(2L, 1L, SABADO, "09:00", "x", "fins de semana"),
                new Caso(2L, 1L, "2026-11-06", "09:00", "x", "A data ja passou"),
                new Caso(2L, 1L, HOJE, "10:00", "x", "horario de hoje ja passou"),
                new Caso(99L, 1L, TERCA, "09:00", "x", "Dentista nao encontrado: 99"),
                new Caso(2L, 99L, TERCA, "09:00", "x", "Paciente nao encontrado: 99"),
                new Caso(2L, 1L, TERCA, "09:00", "   ", "Informe o procedimento"));

        for (Caso c : casos) {
            Resultado<PropostaAgendamento> r = tools.prepararAgendamento(c.dentista(), c.paciente(), c.data(), c.hora(),
                    c.procedimento());
            assertThat(r.sucesso()).as("caso %s", c).isFalse();
            assertThat(r.erro()).as("caso %s", c).contains(c.erro());
        }
        assertThat(contar("propostas_agendamento")).as("nenhuma recusa gera proposta").isZero();
    }

    @Test
    void prepararRecusaHorarioQueJaEstaOcupado() {
        tools.confirmarAgendamento(preparar(2L, 1L, TERCA, "09:00").codigoConfirmacao());

        Resultado<PropostaAgendamento> segunda = tools.prepararAgendamento(2L, 2L, TERCA, "09:00", "Canal");

        assertThat(segunda.sucesso()).isFalse();
        assertThat(segunda.erro()).contains("ocupado");
        assertThat(contar("propostas_agendamento")).as("so a primeira proposta existe").isEqualTo(1);
    }

    // ---------- passo 2: confirmar ----------

    @Test
    void confirmarEfetivaOAgendamentoELigaAPropostaAConsulta() {
        PropostaAgendamento proposta = preparar(2L, 1L, TERCA, "09:00");

        Resultado<AgendamentoConfirmado> r = tools.confirmarAgendamento(proposta.codigoConfirmacao());

        assertThat(r.sucesso()).isTrue();
        assertThat(r.dados()).extracting(AgendamentoConfirmado::data, AgendamentoConfirmado::hora,
                AgendamentoConfirmado::procedimento).containsExactly(TERCA, "09:00", "Avaliacao");
        assertThat(contar("consultas")).isEqualTo(1);
        var linha = jdbc.sql("SELECT dentista_id, paciente_id, status FROM consultas WHERE id = :id")
                .param("id", r.dados().consultaId()).query().singleRow();
        assertThat(linha).containsEntry("dentista_id", 2).containsEntry("paciente_id", 1).containsEntry("status", "AGENDADA");
        assertThat(statusDaProposta(proposta.codigoConfirmacao())).isEqualTo("CONFIRMADA");
        assertThat(jdbc.sql("SELECT consulta_id FROM propostas_agendamento WHERE codigo = :c")
                .param("c", proposta.codigoConfirmacao()).query(Long.class).single()).isEqualTo(r.dados().consultaId());
    }

    @Test
    void confirmarRecusaCodigoAusenteEmBrancoOuDesconhecido() {
        for (String codigo : new String[] {null, "", "   ", "ZZZZZZZZ"}) {
            Resultado<AgendamentoConfirmado> r = tools.confirmarAgendamento(codigo);
            assertThat(r.sucesso()).as("codigo=%s", codigo).isFalse();
        }
        assertThat(tools.confirmarAgendamento("ZZZZZZZZ").erro()).contains("desconhecido").contains("prepararAgendamento");
        assertThat(contar("consultas")).isZero();
    }

    @Test
    void oCodigoAceitaMinusculasEEspacosNasPontas() {
        PropostaAgendamento proposta = preparar(2L, 1L, TERCA, "09:00");

        Resultado<AgendamentoConfirmado> r = tools.confirmarAgendamento("  " + proposta.codigoConfirmacao().toLowerCase() + " ");

        assertThat(r.sucesso()).isTrue();
    }

    @Test
    void oCodigoValeUmaUnicaVezEDizOndeFicouAConsulta() {
        PropostaAgendamento proposta = preparar(2L, 1L, TERCA, "09:00");
        long consultaId = tools.confirmarAgendamento(proposta.codigoConfirmacao()).dados().consultaId();

        Resultado<AgendamentoConfirmado> repetida = tools.confirmarAgendamento(proposta.codigoConfirmacao());

        assertThat(repetida.sucesso()).isFalse();
        assertThat(repetida.erro()).contains("ja foi usado").contains("consulta " + consultaId).contains(TERCA);
        assertThat(contar("consultas")).as("nenhuma consulta duplicada").isEqualTo(1);
    }

    @Test
    void oCodigoExpiraAposDezMinutos() {
        PropostaAgendamento a = preparar(2L, 1L, TERCA, "09:00"); // criada 10:30, vale ate 10:40
        PropostaAgendamento b = preparar(2L, 1L, TERCA, "10:00");

        // 10:39:59 ainda vale; 10:40:00 ja nao vale.
        assertThat(ferramentasEm("2026-11-09T13:39:59Z").confirmarAgendamento(a.codigoConfirmacao()).sucesso()).isTrue();
        Resultado<AgendamentoConfirmado> tarde = ferramentasEm("2026-11-09T13:40:00Z").confirmarAgendamento(b.codigoConfirmacao());

        assertThat(tarde.sucesso()).isFalse();
        assertThat(tarde.erro()).contains("expirou").contains("prepararAgendamento");
        assertThat(contar("consultas")).isEqualTo(1);
        assertThat(statusDaProposta(b.codigoConfirmacao())).as("expirar nao consome o codigo").isEqualTo("PENDENTE");
    }

    @Test
    void confirmarRevalidaAsRegrasPoisOTempoPassouEntrePrepararEConfirmar() {
        // Preparado as 10:55 para hoje 11:00 (valido); confirmado as 11:01: o codigo ainda vale (ate 11:05), mas o horario passou.
        PropostaAgendamento proposta = ferramentasEm("2026-11-09T13:55:00Z")
                .prepararAgendamento(2L, 1L, HOJE, "11:00", "Avaliacao").dados();

        Resultado<AgendamentoConfirmado> r = ferramentasEm("2026-11-09T14:01:00Z")
                .confirmarAgendamento(proposta.codigoConfirmacao());

        assertThat(r.sucesso()).isFalse();
        assertThat(r.erro()).contains("ja passou");
        assertThat(contar("consultas")).isZero();
        assertThat(statusDaProposta(proposta.codigoConfirmacao())).isEqualTo("PENDENTE");
    }

    // ---------- corridas ----------

    @Test
    void duasPropostasParaOMesmoHorarioSoUmaViraConsultaEAOutraFicaPendente() {
        // As duas passam no preparo (o horario ainda esta livre); a primeira a confirmar leva.
        PropostaAgendamento a = preparar(2L, 1L, TERCA, "09:00");
        PropostaAgendamento b = preparar(2L, 2L, TERCA, "09:00");

        assertThat(tools.confirmarAgendamento(a.codigoConfirmacao()).sucesso()).isTrue();
        Resultado<AgendamentoConfirmado> segunda = tools.confirmarAgendamento(b.codigoConfirmacao());

        assertThat(segunda.sucesso()).isFalse();
        assertThat(segunda.erro()).contains("ocupado");
        assertThat(contar("consultas")).isEqualTo(1);
        assertThat(statusDaProposta(a.codigoConfirmacao())).isEqualTo("CONFIRMADA");
        // A transacao foi desfeita por inteiro: o codigo perdedor NAO ficou marcado como usado sem ter consulta.
        assertThat(statusDaProposta(b.codigoConfirmacao())).isEqualTo("PENDENTE");
    }

    @Test
    void confirmacoesSimultaneasDoMesmoCodigoCriamUmaUnicaConsulta() throws Exception {
        PropostaAgendamento proposta = preparar(2L, 1L, TERCA, "09:00");
        int chamadas = 8;
        ExecutorService pool = Executors.newFixedThreadPool(chamadas);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Resultado<AgendamentoConfirmado>>> futuros = new ArrayList<>();
            for (int i = 0; i < chamadas; i++) {
                futuros.add(pool.submit(() -> {
                    largada.await();
                    return tools.confirmarAgendamento(proposta.codigoConfirmacao());
                }));
            }
            largada.countDown();

            int sucessos = 0;
            for (Future<Resultado<AgendamentoConfirmado>> futuro : futuros) {
                Resultado<AgendamentoConfirmado> r = futuro.get();
                if (r.sucesso()) {
                    sucessos++;
                } else {
                    assertThat(r.erro()).contains("usado");
                }
            }
            assertThat(sucessos).as("exatamente uma confirmacao vence").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(contar("consultas")).isEqualTo(1);
        assertThat(statusDaProposta(proposta.codigoConfirmacao())).isEqualTo("CONFIRMADA");
    }
}
