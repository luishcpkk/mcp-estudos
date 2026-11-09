package com.example.mcpestudos;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Prompts do MCP: roteiros prontos que o USUARIO escolhe no cliente (no Claude Desktop, costumam aparecer no menu de
 * anexos) e que dizem ao modelo, passo a passo, quais ferramentas usar e em que ordem.
 *
 * <p>Sao engenharia de prompt versionada junto com o codigo: o roteiro de "agendar" cita as mesmas ferramentas e as
 * mesmas regras (confirmar so depois do sim) que o servidor impoe. {@code ContratoMcpTest} confere que todo nome de
 * ferramenta ou parametro citado aqui existe de fato, para que renomear uma ferramenta nao deixe um roteiro obsoleto.
 *
 * <p>Cada metodo devolve uma mensagem com papel USER (o {@code String} puro viraria mensagem do assistente) e recusa
 * argumento ausente ou invalido com {@link IllegalArgumentException}, que o MCP entrega como erro do protocolo.
 */
@Component
public class PromptsClinicos {

    /** Limite do texto livre que o usuario digita como argumento. */
    static final int LIMITE_DE_TEXTO = 1000;

    @McpPrompt(name = "triagem-de-dor", title = "Triagem de dor de dente",
            description = "Conduz a triagem de uma dor de dente: sinais de alarme, consulta ao protocolo da clinica "
                    + "e, se o usuario quiser, o agendamento.")
    public GetPromptResult triagemDeDor(
            @McpArg(name = "sintomas", required = true,
                    description = "Relato da dor, nas palavras do paciente. Ex.: 'dor forte no dente 46 ha 3 dias, "
                            + "piora a noite'.") String sintomas,
            @McpArg(name = "pacienteId", required = false,
                    description = "ID do paciente, se ja identificado (veja buscarPaciente). Opcional.")
            String pacienteId) {
        String relato = textoObrigatorio(sintomas, "sintomas");
        Long paciente = idOpcional(pacienteId);
        String linhaDoPaciente = paciente == null
                ? "Paciente ainda não identificado. Se precisar do histórico dele, use buscarPaciente e confirme com "
                        + "o usuário qual é o paciente antes de continuar."
                : "Paciente já identificado: ID " + paciente + ".";
        String complemento = paciente == null ? ""
                : " Informe o pacienteId " + paciente + " para que alergias, medicamentos e condições dele sejam "
                        + "considerados.";
        return roteiro("Triagem de dor de dente", """
                Você é o assistente de uma clínica odontológica e vai fazer a triagem de uma dor de dente. \
                Siga os passos na ordem.

                Relato recebido: %s
                %s

                1. Sinais de alarme primeiro. Procure no relato febre alta, inchaço que se estende ao olho, ao pescoço \
                ou ao assoalho da boca, dificuldade para engolir ou respirar e abertura de boca muito limitada. Se \
                houver qualquer um deles, oriente o usuário a procurar um pronto-socorro ou o SAMU (192) imediatamente \
                e pare por aí: não ofereça agendamento.
                2. Consulte a base clínica com consultarBaseClinica, descrevendo o quadro.%s Use somente o que a base \
                devolver. Se ela não tiver resposta, diga isso ao usuário, sem inventar protocolo, medicação ou dose.
                3. Se faltar informação importante, faça uma única rodada de perguntas objetivas: quando começou a dor \
                e como ela é, o que a provoca, alergias, medicamentos em uso, gravidez, e condições como hipertensão \
                ou diabetes.
                4. Resuma para o usuário o que o protocolo da clínica indica e o que depende da avaliação do dentista. \
                Lembre que isto é apoio e não substitui o julgamento do dentista responsável.
                5. Se o usuário quiser marcar consulta, use dataDeHoje para datas relativas, listarDentistas, \
                horariosLivres e prepararAgendamento. Mostre o resumo do agendamento e só chame confirmarAgendamento \
                depois que o usuário confirmar de forma explícita.
                """.formatted(relato, linhaDoPaciente, complemento));
    }

    @McpPrompt(name = "agendar-consulta", title = "Agendar consulta",
            description = "Roteiro para agendar uma consulta: identifica o paciente, escolhe dentista e horario, mostra "
                    + "o resumo e so confirma depois do sim do usuario.")
    public GetPromptResult agendarConsulta(
            @McpArg(name = "paciente", required = true,
                    description = "Nome, ou parte do nome, do paciente.") String paciente,
            @McpArg(name = "procedimento", required = true,
                    description = "Procedimento desejado. Ex.: Limpeza, Avaliacao, Canal.") String procedimento,
            @McpArg(name = "quando", required = false,
                    description = "Preferencia de data e horario, em linguagem natural. Ex.: 'proxima segunda de "
                            + "manha'. Opcional.") String quando) {
        String nome = textoObrigatorio(paciente, "paciente");
        String motivo = textoObrigatorio(procedimento, "procedimento");
        String preferencia = quando == null || quando.isBlank() ? "sem preferência" : texto(quando, "quando");
        return roteiro("Agendar consulta", """
                Você é o assistente de uma clínica odontológica e vai agendar uma consulta. Siga os passos na ordem.

                Paciente: %s
                Procedimento: %s
                Preferência de data e horário: %s

                1. Identifique o paciente com buscarPaciente. Se vier mais de um resultado, pergunte ao usuário qual é; \
                nunca escolha por conta própria nem invente IDs.
                2. Escolha o dentista com listarDentistas, usando a especialidade do procedimento como filtro quando \
                fizer sentido. Se houver mais de uma opção razoável, pergunte ao usuário.
                3. Se a preferência for relativa ("amanhã", "próxima segunda"), use dataDeHoje para saber a data. Veja \
                os horários livres com horariosLivres e ofereça ao usuário algumas opções.
                4. Se o procedimento tiver restrição clínica (anestesia, canal, extração, clareamento) e o paciente \
                tiver hipertensão, diabetes, gravidez ou alergia conhecida, consulte consultarBaseClinica informando o \
                pacienteId antes de seguir e diga ao usuário o que o protocolo indica. Se a base não responder, diga \
                isso.
                5. Com paciente, dentista, data, horário e procedimento definidos, chame prepararAgendamento e mostre o \
                resumo ao usuário. Só chame confirmarAgendamento, com o código recebido, depois que o usuário \
                confirmar de forma explícita. Se o código expirar, prepare de novo.
                6. Depois de confirmar, informe o número da consulta, a data e o horário.
                """.formatted(nome, motivo, preferencia));
    }

    @McpPrompt(name = "resumo-do-paciente", title = "Resumo do paciente",
            description = "Monta o resumo de um paciente para o atendimento: alergias, condicoes, medicamentos, "
                    + "ultima consulta e proximas consultas.")
    public GetPromptResult resumoDoPaciente(
            @McpArg(name = "pacienteId", required = true,
                    description = "ID do paciente (veja buscarPaciente).") String pacienteId) {
        Long paciente = idOpcional(pacienteId);
        if (paciente == null) {
            throw new IllegalArgumentException("Informe o argumento 'pacienteId'.");
        }
        return roteiro("Resumo do paciente", """
                Você é o assistente de uma clínica odontológica e vai preparar o resumo do paciente de ID %d para o \
                atendimento. São dados sensíveis: use-os só para este atendimento e não os repita fora dele. Cada acesso ao \
histórico fica registrado (acessosAoHistorico mostra o registro).

                1. Liste as consultas com consultasDoPaciente.
                2. Consulte a base clínica com consultarBaseClinica, informando o pacienteId %d, e pergunte pelas \
                alergias, pelos medicamentos em uso, pelas condições de saúde e pelo que foi feito na última consulta.
                3. Apresente o resumo nesta ordem: alergias (em destaque), condições de saúde e medicamentos, última \
                consulta, próximas consultas e pendências.
                4. Se a base não tiver histórico do paciente, diga isso claramente em vez de supor.
                5. Não proponha tratamento nem prescrição: isso é decisão do dentista.
                """.formatted(paciente, paciente));
    }

    // ---------- auxiliares ----------

    private static GetPromptResult roteiro(String descricao, String texto) {
        PromptMessage mensagem = PromptMessage.builder(Role.USER, TextContent.builder(texto).build()).build();
        return GetPromptResult.builder(List.of(mensagem)).description(descricao).build();
    }

    private static String textoObrigatorio(String valor, String argumento) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Informe o argumento '" + argumento + "'.");
        }
        return texto(valor, argumento);
    }

    private static String texto(String valor, String argumento) {
        String limpo = valor.strip();
        if (limpo.length() > LIMITE_DE_TEXTO) {
            throw new IllegalArgumentException("O argumento '" + argumento + "' deve ter no máximo "
                    + LIMITE_DE_TEXTO + " caracteres.");
        }
        return limpo;
    }

    /** @return o ID, ou {@code null} se o argumento foi omitido; recusa valor que nao seja um numero positivo */
    private static Long idOpcional(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(valor.strip());
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // cai na recusa abaixo
        }
        throw new IllegalArgumentException("O argumento 'pacienteId' deve ser um número positivo (veja buscarPaciente).");
    }
}
