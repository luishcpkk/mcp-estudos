package com.example.mcpestudos;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Ferramentas MCP de agendamento.
 *
 * <p>Contrato de erro: pedidos recusados por regra de negocio (data invalida, horario ocupado, nome
 * curto demais...) voltam como {@link Resultado#falha(String)}, com texto para o usuario final. Falhas
 * tecnicas (banco indisponivel, bug) nao sao capturadas aqui e propagam como excecao.
 */
@Service
public class AgendamentoTools {

    private static final DateTimeFormatter HORA_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private static final List<LocalTime> HORARIOS = List.of(
            LocalTime.of(8, 0), LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
            LocalTime.of(14, 0), LocalTime.of(15, 0), LocalTime.of(16, 0), LocalTime.of(17, 0));

    static final int TAMANHO_MINIMO_BUSCA = 3;
    static final int LIMITE_BUSCA = 20;
    static final int TAMANHO_MAXIMO_MOTIVO = 200;

    // Codigos do SQLite: o driver informa SQLITE_CONSTRAINT (19) e, em algumas versoes, o estendido (2067).
    private static final int SQLITE_CONSTRAINT = 19;
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    private static final String HORARIO_OCUPADO =
            "Esse horario ja esta ocupado. Consulte horariosLivres.";

    private final JdbcClient jdbc;
    private final Clock clock;

    public AgendamentoTools(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Tool(description = "Retorna a data de hoje e o dia da semana. Use sempre que o usuario "
            + "falar em datas relativas como 'amanha', 'segunda' ou 'semana que vem'.")
    public DataAtual dataDeHoje() {
        LocalDate hoje = LocalDate.now(clock);
        String dia = hoje.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"));
        return new DataAtual(hoje.toString(), dia);
    }

    @Tool(description = "Lista os dentistas da clinica com id, nome e especialidade. "
            + "O filtro de especialidade e opcional.")
    public List<Dentista> listarDentistas(
            @ToolParam(description = "Parte do nome da especialidade, ex: 'orto'. Deixe vazio para listar todos.",
                    required = false) String especialidade) {
        if (especialidade == null || especialidade.isBlank()) {
            return jdbc.sql("SELECT id, nome, especialidade FROM dentistas ORDER BY nome")
                    .query((rs, n) -> new Dentista(rs.getLong("id"), rs.getString("nome"),
                            rs.getString("especialidade")))
                    .list();
        }
        return jdbc.sql("SELECT id, nome, especialidade FROM dentistas "
                        + "WHERE LOWER(especialidade) LIKE LOWER(:esp) ESCAPE '\\' ORDER BY nome")
                .param("esp", "%" + escaparLike(especialidade.trim()) + "%")
                .query((rs, n) -> new Dentista(rs.getLong("id"), rs.getString("nome"),
                        rs.getString("especialidade")))
                .list();
    }

    @Tool(description = "Busca pacientes pelo nome (ou parte dele) e retorna id, nome e telefone. "
            + "Exige ao menos 3 caracteres e devolve no maximo 20 pacientes. "
            + "Pode retornar mais de um paciente: se houver ambiguidade, pergunte ao usuario qual e.")
    public Resultado<List<Paciente>> buscarPaciente(
            @ToolParam(description = "Nome ou parte do nome do paciente (minimo 3 caracteres)") String nome) {
        String termo = nome == null ? "" : nome.trim();
        if (termo.length() < TAMANHO_MINIMO_BUSCA) {
            return Resultado.falha("Informe ao menos " + TAMANHO_MINIMO_BUSCA
                    + " caracteres do nome do paciente.");
        }
        List<Paciente> pacientes = jdbc.sql("SELECT id, nome, telefone FROM pacientes "
                        + "WHERE LOWER(nome) LIKE LOWER(:nome) ESCAPE '\\' ORDER BY nome LIMIT :limite")
                .param("nome", "%" + escaparLike(termo) + "%")
                .param("limite", LIMITE_BUSCA)
                .query((rs, n) -> new Paciente(rs.getLong("id"), rs.getString("nome"),
                        rs.getString("telefone")))
                .list();
        return Resultado.ok(pacientes);
    }

    @Tool(description = "Lista os horarios livres de um dentista em uma data. "
            + "A clinica atende de segunda a sexta, em horarios cheios (08h a 11h e 14h a 17h). "
            + "Em 'hoje', horarios que ja passaram nao sao listados.")
    public Resultado<HorariosLivres> horariosLivres(
            @ToolParam(description = "ID do dentista, obtido em listarDentistas") long dentistaId,
            @ToolParam(description = "Data no formato AAAA-MM-DD, ex: 2026-11-10") String data) {
        LocalDate dia = parseData(data);
        if (dia == null) {
            return Resultado.falha("Data invalida. Use o formato AAAA-MM-DD.");
        }
        if (!dentistaExiste(dentistaId)) {
            return Resultado.falha("Dentista nao encontrado: " + dentistaId);
        }
        String erroDia = validarDia(dia);
        if (erroDia != null) {
            return Resultado.falha(erroDia);
        }
        List<String> ocupados = jdbc.sql("SELECT hora FROM consultas "
                        + "WHERE dentista_id = :d AND data = :data AND status = 'AGENDADA'")
                .param("d", dentistaId)
                .param("data", dia.toString())
                .query(String.class).list();
        List<String> livres = HORARIOS.stream()
                .filter(h -> !jaPassou(dia, h))
                .map(HORA_FMT::format)
                .filter(h -> !ocupados.contains(h))
                .toList();
        return Resultado.ok(new HorariosLivres(dentistaId, dia.toString(), livres));
    }

    @Tool(description = "Agenda uma consulta. Recusa horario ocupado, data passada e fim de semana. "
            + "So chame depois de confirmar paciente, dentista, data e horario com o usuario.")
    public Resultado<AgendamentoConfirmado> agendarConsulta(
            @ToolParam(description = "ID do dentista, obtido em listarDentistas") long dentistaId,
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") long pacienteId,
            @ToolParam(description = "Data no formato AAAA-MM-DD") String data,
            @ToolParam(description = "Horario no formato HH:mm, ex: 09:00. Deve ser um horario cheio.") String hora,
            @ToolParam(description = "Procedimento, ex: Limpeza, Avaliacao, Canal") String procedimento) {
        LocalDate dia = parseData(data);
        if (dia == null) {
            return Resultado.falha("Data invalida. Use o formato AAAA-MM-DD.");
        }
        LocalTime horario = parseHora(hora);
        if (horario == null) {
            return Resultado.falha("Horario invalido. Horarios possiveis: "
                    + HORARIOS.stream().map(HORA_FMT::format).toList());
        }
        String erroDia = validarDia(dia);
        if (erroDia != null) {
            return Resultado.falha(erroDia);
        }
        if (jaPassou(dia, horario)) {
            return Resultado.falha("Esse horario de hoje ja passou.");
        }
        if (!dentistaExiste(dentistaId)) {
            return Resultado.falha("Dentista nao encontrado: " + dentistaId);
        }
        if (!pacienteExiste(pacienteId)) {
            return Resultado.falha("Paciente nao encontrado: " + pacienteId);
        }
        if (procedimento == null || procedimento.isBlank()) {
            return Resultado.falha("Informe o procedimento.");
        }
        String procedimentoOk = procedimento.trim();
        String horaOk = HORA_FMT.format(horario);

        // O indice ux_consulta_ativa e a fonte da verdade contra dupla marcacao: nao ha checagem
        // previa (que teria janela de corrida), so o INSERT. Apenas a violacao UNIQUE vira recusa
        // de negocio; qualquer outra falha de banco propaga como erro tecnico.
        try {
            Long id = jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                            + "VALUES (:d, :p, :data, :hora, :proc) RETURNING id")
                    .param("d", dentistaId).param("p", pacienteId)
                    .param("data", dia.toString()).param("hora", horaOk)
                    .param("proc", procedimentoOk)
                    .query(Long.class).single();
            return Resultado.ok(new AgendamentoConfirmado(id, dia.toString(), horaOk, procedimentoOk));
        } catch (DuplicateKeyException e) {
            return Resultado.falha(HORARIO_OCUPADO);
        } catch (UncategorizedSQLException e) {
            // O driver do SQLite nao informa SQLState, entao o Spring nao chega a traduzir para
            // DuplicateKeyException: a violacao UNIQUE e reconhecida pelo codigo e pela mensagem.
            if (violacaoUnique(e)) {
                return Resultado.falha(HORARIO_OCUPADO);
            }
            throw e;
        }
    }

    @Tool(description = "Cancela uma consulta agendada pelo ID da consulta. So cancela consultas futuras. "
            + "A consulta nao e apagada: fica com status CANCELADA e o horario volta a ficar livre.")
    public Resultado<CancelamentoConfirmado> cancelarConsulta(
            @ToolParam(description = "ID da consulta, obtido em consultasDoPaciente") long consultaId,
            @ToolParam(description = "Motivo do cancelamento, se o usuario informar (ate "
                    + TAMANHO_MAXIMO_MOTIVO + " caracteres)", required = false) String motivo) {
        String motivoOk = motivo == null || motivo.isBlank() ? null : motivo.trim();
        if (motivoOk != null && motivoOk.length() > TAMANHO_MAXIMO_MOTIVO) {
            return Resultado.falha("O motivo deve ter no maximo " + TAMANHO_MAXIMO_MOTIVO + " caracteres.");
        }
        ConsultaRef consulta = jdbc.sql("SELECT data, hora, status FROM consultas WHERE id = :id")
                .param("id", consultaId)
                .query((rs, n) -> new ConsultaRef(rs.getString("data"), rs.getString("hora"),
                        rs.getString("status")))
                .optional().orElse(null);
        if (consulta == null) {
            return Resultado.falha("Consulta nao encontrada: " + consultaId + ".");
        }
        if (!"AGENDADA".equals(consulta.status())) {
            return Resultado.falha("A consulta " + consultaId + " nao esta agendada (status "
                    + consulta.status() + ").");
        }
        LocalDateTime inicio = LocalDateTime.of(LocalDate.parse(consulta.data()),
                LocalTime.parse(consulta.hora(), HORA_FMT));
        LocalDateTime agora = LocalDateTime.now(clock);
        if (!inicio.isAfter(agora)) {
            return Resultado.falha("So e possivel cancelar consultas futuras. A consulta " + consultaId
                    + " era em " + consulta.data() + " as " + consulta.hora() + ".");
        }
        // UPDATE (nunca DELETE). O filtro por status evita cancelar duas vezes se outro pedido chegar junto.
        int linhas = jdbc.sql("UPDATE consultas SET status = 'CANCELADA', "
                        + "motivo_cancelamento = :motivo, cancelada_em = :em "
                        + "WHERE id = :id AND status = 'AGENDADA'")
                .param("motivo", motivoOk)
                .param("em", agora.truncatedTo(ChronoUnit.SECONDS).toString())
                .param("id", consultaId)
                .update();
        if (linhas == 0) {
            return Resultado.falha("A consulta " + consultaId + " ja foi cancelada.");
        }
        return Resultado.ok(new CancelamentoConfirmado(consultaId, consulta.data(), consulta.hora(), "CANCELADA"));
    }

    @Tool(description = "Lista as consultas agendadas de um paciente, em ordem de data.")
    public List<ConsultaResumo> consultasDoPaciente(
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") long pacienteId) {
        return jdbc.sql("SELECT c.id, c.data, c.hora, c.procedimento, d.nome AS dentista "
                        + "FROM consultas c JOIN dentistas d ON d.id = c.dentista_id "
                        + "WHERE c.paciente_id = :p AND c.status = 'AGENDADA' "
                        + "ORDER BY c.data, c.hora")
                .param("p", pacienteId)
                .query((rs, n) -> new ConsultaResumo(rs.getLong("id"), rs.getString("data"),
                        rs.getString("hora"), rs.getString("procedimento"), rs.getString("dentista")))
                .list();
    }

    // ---------- auxiliares (nao viram ferramentas) ----------

    private record ConsultaRef(String data, String hora, String status) {
    }

    private static LocalDate parseData(String data) {
        if (data == null) {
            return null;
        }
        try {
            return LocalDate.parse(data.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Converte "HH:mm" e so aceita um dos horarios de atendimento. */
    private static LocalTime parseHora(String hora) {
        if (hora == null) {
            return null;
        }
        try {
            LocalTime horario = LocalTime.parse(hora.trim(), HORA_FMT);
            return HORARIOS.contains(horario) ? horario : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String validarDia(LocalDate dia) {
        if (dia.isBefore(LocalDate.now(clock))) {
            return "A data ja passou.";
        }
        if (dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "A clinica nao atende aos fins de semana.";
        }
        return null;
    }

    /** Um horario so e agendavel se comecar depois de agora (fuso da clinica). */
    private boolean jaPassou(LocalDate dia, LocalTime hora) {
        return !LocalDateTime.of(dia, hora).isAfter(LocalDateTime.now(clock));
    }

    private static boolean violacaoUnique(UncategorizedSQLException e) {
        SQLException causa = e.getSQLException();
        boolean codigoDeConstraint = causa.getErrorCode() == SQLITE_CONSTRAINT
                || causa.getErrorCode() == SQLITE_CONSTRAINT_UNIQUE;
        return codigoDeConstraint && causa.getMessage() != null
                && causa.getMessage().contains("UNIQUE constraint failed");
    }

    /** Neutraliza %, _ e \ digitados pelo usuario para que valham como texto literal no LIKE. */
    private static String escaparLike(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private boolean dentistaExiste(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM dentistas WHERE id = :id")
                .param("id", id).query(Integer.class).single() > 0;
    }

    private boolean pacienteExiste(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM pacientes WHERE id = :id")
                .param("id", id).query(Integer.class).single() > 0;
    }
}
