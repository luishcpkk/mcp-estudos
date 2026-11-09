package com.example.mcpestudos;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ferramentas MCP de agendamento.
 *
 * <p>Contrato de erro: pedidos recusados por regra de negocio (data invalida, horario ocupado, nome
 * curto demais...) voltam como {@link Resultado#falha(String)}, com texto para o usuario final. Falhas
 * tecnicas (banco indisponivel, bug) nao sao capturadas aqui e propagam como excecao.
 *
 * <p><b>Agendar e feito em dois passos.</b> {@link #prepararAgendamento} valida tudo e devolve um resumo e um
 * codigo, sem gravar nada; {@link #confirmarAgendamento} so efetiva com esse codigo. O codigo vale uma vez, expira
 * e e revalidado na confirmacao. Isso torna a confirmacao uma chamada separada, explicita e auditavel. NAO prova
 * que o usuario disse "sim": o modelo poderia encadear as duas chamadas, e as instrucoes do servidor e as
 * descricoes das ferramentas pedem que nao o faca.
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

    private static final String INFORME_DENTISTA = "Informe o ID do dentista (veja listarDentistas).";
    private static final String INFORME_PACIENTE = "Informe o ID do paciente (veja buscarPaciente).";

    /** Quanto tempo o codigo de confirmacao vale (entra tambem na descricao das ferramentas). */
    static final int VALIDADE_EM_MINUTOS = 10;
    static final int TAMANHO_DO_CODIGO = 8;
    /** Sem 0/O e 1/I, que se confundem ao copiar o codigo. */
    private static final String ALFABETO_DO_CODIGO = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private static final String PENDENTE = "PENDENTE";
    private static final String CONFIRMADA = "CONFIRMADA";

    private static final String PROXIMO_PASSO = "Mostre este resumo ao usuario e peca confirmacao explicita. "
            + "So depois do sim, chame confirmarAgendamento com o codigoConfirmacao. Se o usuario quiser mudar "
            + "algo, chame prepararAgendamento de novo.";

    private static final String HORARIO_OCUPADO =
            "Esse horario ja esta ocupado. Consulte horariosLivres.";

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate transacao;

    public AgendamentoTools(JdbcClient jdbc, Clock clock, TransactionTemplate transacao) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.transacao = transacao;
    }

    @Tool(description = "Retorna a data de hoje e o dia da semana. Use sempre que o usuario "
            + "falar em datas relativas como 'amanha', 'segunda' ou 'semana que vem'.")
    public DataAtual dataDeHoje() {
        LocalDate hoje = LocalDate.now(clock);
        return new DataAtual(hoje.toString(), nomeDoDia(hoje));
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
            @ToolParam(description = "ID do dentista, obtido em listarDentistas") Long dentistaId,
            @ToolParam(description = "Data no formato AAAA-MM-DD, ex: 2026-11-10") String data) {
        if (dentistaId == null) {
            return Resultado.falha(INFORME_DENTISTA);
        }
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

    @Tool(description = "Prepara um agendamento SEM grava-lo: valida paciente, dentista, data, horario e "
            + "disponibilidade e devolve um resumo e um codigoConfirmacao (valido por " + VALIDADE_EM_MINUTOS
            + " minutos). Nada e agendado ate confirmarAgendamento. Recusa horario ocupado, data passada e fim de "
            + "semana. Mostre o resumo ao usuario e peca confirmacao explicita antes de confirmar.")
    public Resultado<PropostaAgendamento> prepararAgendamento(
            @ToolParam(description = "ID do dentista, obtido em listarDentistas") Long dentistaId,
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") Long pacienteId,
            @ToolParam(description = "Data no formato AAAA-MM-DD") String data,
            @ToolParam(description = "Horario no formato HH:mm, ex: 09:00. Deve ser um horario cheio.") String hora,
            @ToolParam(description = "Procedimento, ex: Limpeza, Avaliacao, Canal") String procedimento) {
        Resultado<AgendamentoValidado> validacao = validarAgendamento(dentistaId, pacienteId, data, hora, procedimento);
        if (!validacao.sucesso()) {
            return Resultado.falha(validacao.erro());
        }
        AgendamentoValidado v = validacao.dados();
        // Checagem so para nao mostrar ao usuario um resumo de horario que ja esta ocupado. Quem garante de
        // verdade contra dupla marcacao e o indice ux_consulta_ativa, na confirmacao.
        if (horarioOcupado(v)) {
            return Resultado.falha(HORARIO_OCUPADO);
        }
        LocalDateTime agora = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime expiraEm = agora.plus(Duration.ofMinutes(VALIDADE_EM_MINUTOS));
        String codigo = gerarCodigo();
        jdbc.sql("INSERT INTO propostas_agendamento (codigo, dentista_id, paciente_id, data, hora, procedimento, "
                        + "criada_em, expira_em) VALUES (:codigo, :d, :p, :data, :hora, :proc, :criada, :expira)")
                .param("codigo", codigo).param("d", v.dentistaId()).param("p", v.pacienteId())
                .param("data", v.data()).param("hora", v.hora()).param("proc", v.procedimento())
                .param("criada", agora.toString()).param("expira", expiraEm.toString())
                .update();
        String dia = nomeDoDia(v.dia());
        String resumo = "Consulta de " + v.procedimento() + " para " + v.paciente() + " com " + v.dentista()
                + " em " + v.data() + " (" + dia + ") as " + v.hora() + ".";
        return Resultado.ok(new PropostaAgendamento(codigo, resumo, v.dentista(), v.paciente(), v.data(), dia,
                v.hora(), v.procedimento(), expiraEm.toString(), VALIDADE_EM_MINUTOS, PROXIMO_PASSO));
    }

    @Tool(description = "Efetiva um agendamento preparado, usando o codigoConfirmacao devolvido por "
            + "prepararAgendamento. So chame depois que o usuario confirmar explicitamente o resumo. O codigo vale "
            + "uma vez e expira em " + VALIDADE_EM_MINUTOS + " minutos; se expirar, prepare de novo.")
    public Resultado<AgendamentoConfirmado> confirmarAgendamento(
            @ToolParam(description = "Codigo de confirmacao devolvido por prepararAgendamento") String codigoConfirmacao) {
        String codigo = codigoConfirmacao == null ? "" : codigoConfirmacao.trim().toUpperCase(Locale.ROOT);
        if (codigo.isEmpty()) {
            return Resultado.falha("Informe o codigoConfirmacao devolvido por prepararAgendamento.");
        }
        PropostaGravada proposta = buscarProposta(codigo).orElse(null);
        if (proposta == null) {
            return Resultado.falha("Codigo de confirmacao desconhecido. Chame prepararAgendamento para gerar um novo.");
        }
        if (CONFIRMADA.equals(proposta.status())) {
            return Resultado.falha("Este codigo ja foi usado: a consulta " + proposta.consultaId()
                    + " ja esta agendada para " + proposta.data() + " as " + proposta.hora() + ".");
        }
        if (!LocalDateTime.now(clock).isBefore(proposta.expiraEm())) {
            return Resultado.falha("O codigo expirou (valia " + VALIDADE_EM_MINUTOS + " minutos). Chame "
                    + "prepararAgendamento de novo para ver se o horario continua livre.");
        }
        // O tempo passou entre preparar e confirmar: o horario pode ter ficado para tras, o cadastro mudado...
        Resultado<AgendamentoValidado> validacao = validarAgendamento(proposta.dentistaId(), proposta.pacienteId(),
                proposta.data(), proposta.hora(), proposta.procedimento());
        if (!validacao.sucesso()) {
            return Resultado.falha(validacao.erro());
        }
        return transacao.execute(status -> efetivar(status, proposta, validacao.dados()));
    }

    @Tool(description = "Cancela uma consulta agendada pelo ID da consulta. So cancela consultas futuras. "
            + "A consulta nao e apagada: fica com status CANCELADA e o horario volta a ficar livre.")
    public Resultado<CancelamentoConfirmado> cancelarConsulta(
            @ToolParam(description = "ID da consulta, obtido em consultasDoPaciente") Long consultaId,
            @ToolParam(description = "Motivo do cancelamento, se o usuario informar (ate "
                    + TAMANHO_MAXIMO_MOTIVO + " caracteres)", required = false) String motivo) {
        if (consultaId == null) {
            return Resultado.falha("Informe o ID da consulta (veja consultasDoPaciente).");
        }
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

    @Tool(description = "Lista as consultas de um paciente, em ordem de data, incluindo o historico. "
            + "O status e AGENDADA para consultas futuras e REALIZADA para as que ja passaram. "
            + "Consultas canceladas nao aparecem.")
    public Resultado<List<ConsultaResumo>> consultasDoPaciente(
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") Long pacienteId) {
        if (pacienteId == null) {
            return Resultado.falha(INFORME_PACIENTE);
        }
        List<ConsultaResumo> consultas = jdbc.sql("SELECT c.id, c.data, c.hora, c.procedimento, "
                        + "d.nome AS dentista "
                        + "FROM consultas c JOIN dentistas d ON d.id = c.dentista_id "
                        + "WHERE c.paciente_id = :p AND c.status = 'AGENDADA' "
                        + "ORDER BY c.data, c.hora")
                .param("p", pacienteId)
                .query((rs, n) -> {
                    String data = rs.getString("data");
                    String hora = rs.getString("hora");
                    return new ConsultaResumo(rs.getLong("id"), data, hora, rs.getString("procedimento"),
                            rs.getString("dentista"), statusExibido(data, hora));
                })
                .list();
        return Resultado.ok(consultas);
    }

    // ---------- auxiliares (nao viram ferramentas) ----------

    private record AgendamentoValidado(long dentistaId, String dentista, long pacienteId, String paciente,
                                       LocalDate dia, LocalTime horario, String procedimento) {
        String data() {
            return dia.toString();
        }

        String hora() {
            return HORA_FMT.format(horario);
        }
    }

    private record PropostaGravada(String codigo, long dentistaId, long pacienteId, String data, String hora,
                                   String procedimento, LocalDateTime expiraEm, String status, Long consultaId) {
    }

    /**
     * Todas as regras de negocio de um agendamento, na ordem em que o usuario as percebe. Roda ao PREPARAR e de novo
     * ao CONFIRMAR. A disponibilidade do horario nao entra aqui: ela e garantida pelo indice unico no INSERT.
     */
    private Resultado<AgendamentoValidado> validarAgendamento(Long dentistaId, Long pacienteId, String data,
                                                              String hora, String procedimento) {
        if (dentistaId == null) {
            return Resultado.falha(INFORME_DENTISTA);
        }
        if (pacienteId == null) {
            return Resultado.falha(INFORME_PACIENTE);
        }
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
        Optional<String> dentista = nomeDoDentista(dentistaId);
        if (dentista.isEmpty()) {
            return Resultado.falha("Dentista nao encontrado: " + dentistaId);
        }
        Optional<String> paciente = nomeDoPaciente(pacienteId);
        if (paciente.isEmpty()) {
            return Resultado.falha("Paciente nao encontrado: " + pacienteId);
        }
        if (procedimento == null || procedimento.isBlank()) {
            return Resultado.falha("Informe o procedimento.");
        }
        return Resultado.ok(new AgendamentoValidado(dentistaId, dentista.get(), pacienteId, paciente.get(), dia,
                horario, procedimento.trim()));
    }

    /**
     * Efetiva a proposta numa unica transacao: marca o codigo como usado, cria a consulta e liga as duas. Se algo
     * falhar no meio (horario tomado por outra pessoa, por exemplo), tudo e desfeito e o codigo continua PENDENTE.
     * O {@code UPDATE ... WHERE status = 'PENDENTE'} garante o uso unico mesmo com duas confirmacoes simultaneas.
     */
    private Resultado<AgendamentoConfirmado> efetivar(TransactionStatus status, PropostaGravada proposta,
                                                      AgendamentoValidado v) {
        int usadas = jdbc.sql("UPDATE propostas_agendamento SET status = '" + CONFIRMADA
                        + "' WHERE codigo = :codigo AND status = '" + PENDENTE + "'")
                .param("codigo", proposta.codigo()).update();
        if (usadas == 0) {
            status.setRollbackOnly();
            return Resultado.falha("Este codigo acabou de ser usado em outra chamada. Use consultasDoPaciente "
                    + "para ver a consulta.");
        }
        // O indice ux_consulta_ativa e a fonte da verdade contra dupla marcacao: so o INSERT, sem checagem
        // previa (que teria janela de corrida). Apenas a violacao UNIQUE vira recusa de negocio; qualquer
        // outra falha de banco propaga como erro tecnico (e desfaz a transacao).
        try {
            Long id = jdbc.sql("INSERT INTO consultas (dentista_id, paciente_id, data, hora, procedimento) "
                            + "VALUES (:d, :p, :data, :hora, :proc) RETURNING id")
                    .param("d", v.dentistaId()).param("p", v.pacienteId())
                    .param("data", v.data()).param("hora", v.hora()).param("proc", v.procedimento())
                    .query(Long.class).single();
            jdbc.sql("UPDATE propostas_agendamento SET consulta_id = :id WHERE codigo = :codigo")
                    .param("id", id).param("codigo", proposta.codigo()).update();
            return Resultado.ok(new AgendamentoConfirmado(id, v.data(), v.hora(), v.procedimento()));
        } catch (DuplicateKeyException e) {
            status.setRollbackOnly();
            return Resultado.falha(HORARIO_OCUPADO);
        } catch (UncategorizedSQLException e) {
            // O driver do SQLite nao informa SQLState, entao o Spring nao chega a traduzir para
            // DuplicateKeyException: a violacao UNIQUE e reconhecida pelo codigo e pela mensagem.
            if (violacaoUnique(e)) {
                status.setRollbackOnly();
                return Resultado.falha(HORARIO_OCUPADO);
            }
            throw e;
        }
    }

    private Optional<PropostaGravada> buscarProposta(String codigo) {
        return jdbc.sql("SELECT codigo, dentista_id, paciente_id, data, hora, procedimento, expira_em, status, "
                        + "consulta_id FROM propostas_agendamento WHERE codigo = :codigo")
                .param("codigo", codigo)
                .query((rs, n) -> {
                    long consulta = rs.getLong("consulta_id");
                    return new PropostaGravada(rs.getString("codigo"), rs.getLong("dentista_id"),
                            rs.getLong("paciente_id"), rs.getString("data"), rs.getString("hora"),
                            rs.getString("procedimento"), LocalDateTime.parse(rs.getString("expira_em")),
                            rs.getString("status"), rs.wasNull() ? null : consulta);
                })
                .optional();
    }

    private boolean horarioOcupado(AgendamentoValidado v) {
        return jdbc.sql("SELECT COUNT(*) FROM consultas WHERE dentista_id = :d AND data = :data AND hora = :hora "
                        + "AND status = 'AGENDADA'")
                .param("d", v.dentistaId()).param("data", v.data()).param("hora", v.hora())
                .query(Integer.class).single() > 0;
    }

    private static String gerarCodigo() {
        StringBuilder codigo = new StringBuilder(TAMANHO_DO_CODIGO);
        for (int i = 0; i < TAMANHO_DO_CODIGO; i++) {
            codigo.append(ALFABETO_DO_CODIGO.charAt(ALEATORIO.nextInt(ALFABETO_DO_CODIGO.length())));
        }
        return codigo.toString();
    }

    /** Horarios de atendimento no formato HH:mm: fonte unica, usada tambem pelo resource de regras. */
    static List<String> horariosDeAtendimento() {
        return HORARIOS.stream().map(HORA_FMT::format).toList();
    }

    private static String nomeDoDia(LocalDate dia) {
        return dia.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("pt-BR"));
    }

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

    /**
     * Status mostrado ao usuario. No banco a consulta continua AGENDADA; "REALIZADA" e derivado: a consulta
     * cujo inicio nao e mais futuro (pelo Clock) ja aconteceu. Mesmo criterio de {@link #jaPassou}.
     */
    private String statusExibido(String data, String hora) {
        return jaPassou(LocalDate.parse(data), LocalTime.parse(hora, HORA_FMT)) ? "REALIZADA" : "AGENDADA";
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

    private Optional<String> nomeDoDentista(long id) {
        return jdbc.sql("SELECT nome FROM dentistas WHERE id = :id").param("id", id).query(String.class).optional();
    }

    private Optional<String> nomeDoPaciente(long id) {
        return jdbc.sql("SELECT nome FROM pacientes WHERE id = :id").param("id", id).query(String.class).optional();
    }
}
