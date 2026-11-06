package com.example.mcpestudos;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgendamentoTools {

    private static final List<String> HORARIOS = List.of(
            "08:00", "09:00", "10:00", "11:00", "14:00", "15:00", "16:00", "17:00");

    private final JdbcClient jdbc;

    public AgendamentoTools(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Tool(description = "Retorna a data de hoje e o dia da semana. Use sempre que o usuario "
            + "falar em datas relativas como 'amanha', 'segunda' ou 'semana que vem'.")
    public String dataDeHoje() {
        LocalDate hoje = LocalDate.now();
        String dia = hoje.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"));
        return "Hoje e " + hoje + " (" + dia + ")";
    }

    @Tool(description = "Lista os dentistas da clinica com id, nome e especialidade. "
            + "O filtro de especialidade e opcional.")
    public List<Map<String, Object>> listarDentistas(
            @ToolParam(description = "Parte do nome da especialidade, ex: 'orto'. Deixe vazio para listar todos.",
                    required = false) String especialidade) {
        if (especialidade == null || especialidade.isBlank()) {
            return jdbc.sql("SELECT id, nome, especialidade FROM dentistas ORDER BY nome")
                    .query().listOfRows();
        }
        return jdbc.sql("SELECT id, nome, especialidade FROM dentistas "
                        + "WHERE LOWER(especialidade) LIKE LOWER(:esp) ORDER BY nome")
                .param("esp", "%" + especialidade.trim() + "%")
                .query().listOfRows();
    }

    @Tool(description = "Busca pacientes pelo nome (ou parte dele) e retorna id, nome e telefone. "
            + "Pode retornar mais de um paciente: se houver ambiguidade, pergunte ao usuario qual e.")
    public List<Map<String, Object>> buscarPaciente(
            @ToolParam(description = "Nome ou parte do nome do paciente") String nome) {
        return jdbc.sql("SELECT id, nome, telefone FROM pacientes "
                        + "WHERE LOWER(nome) LIKE LOWER(:nome) ORDER BY nome")
                .param("nome", "%" + (nome == null ? "" : nome.trim()) + "%")
                .query().listOfRows();
    }

    @Tool(description = "Lista os horarios livres de um dentista em uma data. "
            + "A clinica atende de segunda a sexta, em horarios cheios (08h a 11h e 14h a 17h).")
    public Map<String, Object> horariosLivres(
            @ToolParam(description = "ID do dentista, obtido em listarDentistas") long dentistaId,
            @ToolParam(description = "Data no formato AAAA-MM-DD, ex: 2026-11-10") String data) {
        LocalDate dia = parseData(data);
        if (dia == null) {
            return Map.of("erro", "Data invalida. Use o formato AAAA-MM-DD.");
        }
        if (!dentistaExiste(dentistaId)) {
            return Map.of("erro", "Dentista nao encontrado: " + dentistaId);
        }
        String erroDia = validarDia(dia);
        if (erroDia != null) {
            return Map.of("erro", erroDia);
        }
        List<String> ocupados = jdbc.sql("SELECT hora FROM consultas "
                        + "WHERE dentista_id = :d AND data = :data AND status = 'AGENDADA'")
                .param("d", dentistaId)
                .param("data", dia.toString())
                .query(String.class).list();
        List<String> livres = HORARIOS.stream().filter(h -> !ocupados.contains(h)).toList();
        return Map.of("dentistaId", dentistaId, "data", dia.toString(), "horariosLivres", livres);
    }

    @Tool(description = "Agenda uma consulta. Recusa horario ocupado, data passada e fim de semana. "
            + "So chame depois de confirmar paciente, dentista, data e horario com o usuario.")
    public String agendarConsulta(
            @ToolParam(description = "ID do dentista, obtido em listarDentistas") long dentistaId,
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") long pacienteId,
            @ToolParam(description = "Data no formato AAAA-MM-DD") String data,
            @ToolParam(description = "Horario no formato HH:mm, ex: 09:00. Deve ser um horario cheio.") String hora,
            @ToolParam(description = "Procedimento, ex: Limpeza, Avaliacao, Canal") String procedimento) {
        LocalDate dia = parseData(data);
        if (dia == null) {
            return "Erro: data invalida. Use o formato AAAA-MM-DD.";
        }
        if (hora == null || !HORARIOS.contains(hora.trim())) {
            return "Erro: horario invalido. Horarios possiveis: " + HORARIOS;
        }
        String horaOk = hora.trim();
        String erroDia = validarDia(dia);
        if (erroDia != null) {
            return "Erro: " + erroDia;
        }
        if (dia.equals(LocalDate.now()) && !LocalTime.parse(horaOk).isAfter(LocalTime.now())) {
            return "Erro: esse horario de hoje ja passou.";
        }
        if (!dentistaExiste(dentistaId)) {
            return "Erro: dentista nao encontrado: " + dentistaId;
        }
        if (!pacienteExiste(pacienteId)) {
            return "Erro: paciente nao encontrado: " + pacienteId;
        }
        if (procedimento == null || procedimento.isBlank()) {
            return "Erro: informe o procedimento.";
        }
        Integer conflito = jdbc.sql("SELECT COUNT(*) FROM consultas WHERE dentista_id = :d "
                        + "AND data = :data AND hora = :hora AND status = 'AGENDADA'")
                .param("d", dentistaId).param("data", dia.toString()).param("hora", horaOk)
                .query(Integer.class).single();
        if (conflito > 0) {
            return "Erro: esse horario ja esta ocupado. Consulte horariosLivres.";
        }
        try {
            jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                            + "VALUES (:d, :p, :data, :hora, :proc)")
                    .param("d", dentistaId).param("p", pacienteId)
                    .param("data", dia.toString()).param("hora", horaOk)
                    .param("proc", procedimento.trim())
                    .update();
        } catch (DataAccessException e) {
            return "Erro: nao foi possivel agendar (horario possivelmente ocupado).";
        }
        Long id = jdbc.sql("SELECT id FROM consultas WHERE dentista_id = :d AND data = :data "
                        + "AND hora = :hora AND status = 'AGENDADA'")
                .param("d", dentistaId).param("data", dia.toString()).param("hora", horaOk)
                .query(Long.class).single();
        return "Consulta " + id + " agendada para " + dia + " as " + horaOk + ".";
    }

    @Tool(description = "Cancela uma consulta agendada pelo ID da consulta.")
    public String cancelarConsulta(
            @ToolParam(description = "ID da consulta, obtido em consultasDoPaciente") long consultaId) {
        int linhas = jdbc.sql("UPDATE consultas SET status = 'CANCELADA' "
                        + "WHERE id = :id AND status = 'AGENDADA'")
                .param("id", consultaId).update();
        return linhas == 0
                ? "Erro: nao existe consulta agendada com o ID " + consultaId + "."
                : "Consulta " + consultaId + " cancelada.";
    }

    @Tool(description = "Lista as consultas agendadas de um paciente, em ordem de data.")
    public List<Map<String, Object>> consultasDoPaciente(
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") long pacienteId) {
        return jdbc.sql("SELECT c.id, c.data, c.hora, c.procedimento, d.nome AS dentista "
                        + "FROM consultas c JOIN dentistas d ON d.id = c.dentista_id "
                        + "WHERE c.paciente_id = :p AND c.status = 'AGENDADA' "
                        + "ORDER BY c.data, c.hora")
                .param("p", pacienteId)
                .query().listOfRows();
    }

    // ---------- auxiliares (nao viram ferramentas) ----------

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

    private static String validarDia(LocalDate dia) {
        if (dia.isBefore(LocalDate.now())) {
            return "A data ja passou.";
        }
        if (dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "A clinica nao atende aos fins de semana.";
        }
        return null;
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