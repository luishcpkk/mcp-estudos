package com.example.mcpestudos;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Ferramenta MCP para ler o registro de acessos ao historico de um paciente.
 *
 * <p>Ler o registro nao e, ele mesmo, registrado: o registro so tem fatos (quando, qual ferramenta, quantos itens), sem
 * dado clinico, e registrar a leitura do registro nunca terminaria. Mesmo contrato de erro das demais ferramentas:
 * pedido invalido volta como {@link Resultado#falha(String)}.
 */
@Service
public class AuditoriaTools {

    static final int LIMITE_PADRAO = 20;
    static final int LIMITE_MAXIMO = 100;

    private static final String AVISO = "O registro mostra QUANDO o historico foi acessado, por qual ferramenta e quantos "
            + "itens foram devolvidos. Nao mostra a pergunta feita, o conteudo devolvido nem QUEM acessou: este servidor "
            + "nao tem login de usuario. Nao afirme quem foi.";

    private final AuditoriaDeAcessos auditoria;
    private final JdbcClient jdbc;

    public AuditoriaTools(AuditoriaDeAcessos auditoria, JdbcClient jdbc) {
        this.auditoria = auditoria;
        this.jdbc = jdbc;
    }

    @Tool(description = "Lista os acessos mais recentes ao historico de um paciente (consultas e base clinica): quando "
            + "aconteceram, por qual ferramenta e quantos itens foram devolvidos, do mais novo para o mais antigo. Use "
            + "para responder 'quando o historico deste paciente foi consultado?'. Nao mostra o texto das perguntas "
            + "nem quem acessou (o servidor nao tem login de usuario).")
    public Resultado<RegistroDeAcessos> acessosAoHistorico(
            @ToolParam(description = "ID do paciente, obtido em buscarPaciente") Long pacienteId,
            @ToolParam(description = "Quantos acessos listar, dos mais recentes (padrao " + LIMITE_PADRAO + ", maximo "
                    + LIMITE_MAXIMO + ")", required = false) Integer limite) {
        if (pacienteId == null) {
            return Resultado.falha("Informe o ID do paciente (veja buscarPaciente).");
        }
        int quantos = limite == null ? LIMITE_PADRAO : limite;
        if (quantos < 1 || quantos > LIMITE_MAXIMO) {
            return Resultado.falha("O limite deve estar entre 1 e " + LIMITE_MAXIMO + ".");
        }
        boolean existe = jdbc.sql("SELECT COUNT(*) FROM pacientes WHERE id = :id")
                .param("id", pacienteId).query(Integer.class).single() > 0;
        if (!existe) {
            return Resultado.falha("Paciente nao encontrado: " + pacienteId + ".");
        }
        return Resultado.ok(new RegistroDeAcessos(pacienteId, auditoria.total(pacienteId),
                auditoria.ultimos(pacienteId, quantos), AVISO));
    }
}
