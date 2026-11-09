package com.example.mcpestudos;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.example.mcpestudos.DocumentIngestionService.TrechoDeProtocolo;

import tools.jackson.databind.json.JsonMapper;

/**
 * Resources do MCP: conteudo que o CLIENTE pode anexar a conversa sem que o modelo precise chamar uma ferramenta
 * (ferramenta = o modelo decide chamar; resource = o usuario ou a aplicacao escolhe anexar).
 *
 * <ul>
 *   <li>{@code clinica://dentistas}: a lista de dentistas.</li>
 *   <li>{@code clinica://regras-de-agendamento}: horarios, dias e regras do agendamento.</li>
 *   <li>{@code clinica://protocolos}: o indice dos protocolos indexados.</li>
 *   <li>{@code clinica://protocolos/{arquivo}}: o texto de um protocolo.</li>
 * </ul>
 *
 * <p><b>Historicos de paciente NUNCA viram resource.</b> Um resource nao passa pelo filtro por paciente de
 * {@code consultarBaseClinica}; expor o historico aqui o tornaria legivel por qualquer cliente, para qualquer
 * paciente. So documentos de escopo PROTOCOLO existem neste indice.
 *
 * <p>Erros (protocolo inexistente, por exemplo) saem como {@link IllegalArgumentException}, que o MCP entrega como
 * erro do protocolo.
 */
@Component
public class RecursosClinicos {

    static final String URI_DENTISTAS = "clinica://dentistas";
    static final String URI_REGRAS = "clinica://regras-de-agendamento";
    static final String URI_PROTOCOLOS = "clinica://protocolos";

    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    /** Indice dos protocolos; {@code estado} diz se a indexacao ja terminou. */
    record IndiceDeProtocolos(String estado, List<ItemDoIndice> protocolos) {
    }

    record ItemDoIndice(String arquivo, String uri, int paginas, int trechos) {
    }

    private final JdbcClient jdbc;
    private final DocumentIngestionService ingestao;
    private final JsonMapper json;

    public RecursosClinicos(JdbcClient jdbc, DocumentIngestionService ingestao, JsonMapper json) {
        this.jdbc = jdbc;
        this.ingestao = ingestao;
        this.json = json;
    }

    @McpResource(uri = URI_DENTISTAS, name = "dentistas", title = "Dentistas da clínica",
            description = "Lista dos dentistas da clinica com id, nome e especialidade.", mimeType = "application/json")
    public String dentistas() {
        List<Dentista> dentistas = jdbc.sql("SELECT id, nome, especialidade FROM dentistas ORDER BY nome")
                .query((rs, n) -> new Dentista(rs.getLong("id"), rs.getString("nome"), rs.getString("especialidade")))
                .list();
        return json.writeValueAsString(dentistas);
    }

    @McpResource(uri = URI_REGRAS, name = "regras-de-agendamento", title = "Regras de agendamento",
            description = "Dias e horarios de atendimento e as regras para agendar e cancelar consultas.",
            mimeType = "text/markdown")
    public String regrasDeAgendamento() {
        return """
                # Regras de agendamento da clínica

                - Atendimento de segunda a sexta-feira, em horários cheios: %s.
                - Não há atendimento aos fins de semana (%s e %s).
                - Só se agenda um horário que ainda não passou, no fuso %s.
                - Um dentista atende uma consulta por horário.
                - O agendamento é feito em dois passos: prepararAgendamento mostra o resumo e gera um código, e \
                confirmarAgendamento efetiva a consulta. O código vale uma vez e expira em %d minutos.
                - Só se cancela uma consulta futura (cancelarConsulta). A consulta cancelada não é apagada e o horário \
                volta a ficar livre.
                - No histórico, a consulta cujo horário já passou aparece como REALIZADA.
                """.formatted(String.join(", ", AgendamentoTools.horariosDeAtendimento()),
                DayOfWeek.SATURDAY.getDisplayName(TextStyle.FULL, PT_BR),
                DayOfWeek.SUNDAY.getDisplayName(TextStyle.FULL, PT_BR),
                McpEstudosApplication.FUSO_CLINICA, AgendamentoTools.VALIDADE_EM_MINUTOS);
    }

    @McpResource(uri = URI_PROTOCOLOS, name = "protocolos", title = "Protocolos clínicos",
            description = "Indice dos protocolos clinicos indexados, com o endereco de cada um.",
            mimeType = "application/json")
    public String protocolos() {
        List<ItemDoIndice> itens = new ArrayList<>();
        ingestao.getProtocolosIndexados().forEach((arquivo, trechos) -> itens.add(new ItemDoIndice(arquivo,
                URI_PROTOCOLOS + "/" + codificar(arquivo),
                (int) trechos.stream().map(TrechoDeProtocolo::pagina).distinct().count(), trechos.size())));
        return json.writeValueAsString(new IndiceDeProtocolos(ingestao.getStatus().estado().name(), itens));
    }

    @McpResource(uri = URI_PROTOCOLOS + "/{arquivo}", name = "protocolo", title = "Texto de um protocolo",
            description = "Texto de um protocolo clinico, por pagina. O nome do arquivo vem do indice clinica://protocolos.",
            mimeType = "text/plain")
    public String protocolo(String arquivo) {
        String nome = decodificar(arquivo);
        List<TrechoDeProtocolo> trechos = ingestao.getProtocolosIndexados().get(nome);
        if (trechos == null) {
            throw new IllegalArgumentException("Protocolo não encontrado: " + nome + ". Veja os disponíveis em "
                    + URI_PROTOCOLOS + ".");
        }
        StringBuilder texto = new StringBuilder("# ").append(nome).append("\n");
        Integer paginaAtual = null;
        for (TrechoDeProtocolo trecho : trechos) {
            if (paginaAtual == null || !paginaAtual.equals(trecho.pagina())) {
                paginaAtual = trecho.pagina();
                texto.append("\n[página ").append(paginaAtual).append("]\n");
            }
            texto.append(trecho.texto().strip()).append("\n\n");
        }
        return texto.toString().stripTrailing() + "\n";
    }

    /** O nome do arquivo vai na URI: espacos e acentos precisam de codificacao. */
    static String codificar(String nome) {
        return URLEncoder.encode(nome, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String decodificar(String parteDaUri) {
        // '+' em caminho de URI e um '+' literal, nao um espaco (URLDecoder o trataria como espaco).
        return URLDecoder.decode(parteDaUri.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
