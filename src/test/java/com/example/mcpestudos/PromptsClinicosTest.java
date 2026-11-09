package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Os roteiros dos prompts sao texto, mas carregam as regras do servidor (confirmar so depois do sim, consultar a base
 * antes de agendar, nunca inventar ID). Estes testes fixam essas regras: se alguem reescrever um roteiro e perder uma
 * delas, o teste avisa. Que os nomes de ferramentas citados existem de fato e conferido em {@link ContratoMcpTest}.
 */
class PromptsClinicosTest {

    private final PromptsClinicos prompts = new PromptsClinicos();

    private static String texto(GetPromptResult resultado) {
        assertThat(resultado.messages()).hasSize(1);
        PromptMessage mensagem = resultado.messages().get(0);
        // USER: o roteiro e uma instrucao que o usuario escolheu; como ASSISTANT o modelo o trataria como fala propria.
        assertThat(mensagem.role()).isEqualTo(Role.USER);
        assertThat(mensagem.content()).isInstanceOf(TextContent.class);
        return ((TextContent) mensagem.content()).text();
    }

    // ---------- triagem-de-dor ----------

    @Test
    void triagemPoeOsSinaisDeAlarmeAntesDeQualquerAgendamento() {
        String roteiro = texto(prompts.triagemDeDor("dor forte no dente 46 ha 3 dias", null));

        assertThat(roteiro).contains("dor forte no dente 46 ha 3 dias");
        assertThat(roteiro.indexOf("pronto-socorro")).as("alarme antes do agendamento")
                .isPositive().isLessThan(roteiro.indexOf("prepararAgendamento"));
        assertThat(roteiro).contains("não ofereça agendamento");          // com sinal de alarme, nao agenda
        assertThat(roteiro.indexOf("consultarBaseClinica")).isLessThan(roteiro.indexOf("prepararAgendamento"));
    }

    @Test
    void triagemNaoPermiteInventarProtocoloEExigeConfirmacaoExplicita() {
        String roteiro = texto(prompts.triagemDeDor("dor de dente", null));

        assertThat(roteiro).contains("Use somente o que a base")
                .contains("sem inventar protocolo, medicação ou dose")
                .contains("não substitui o julgamento do dentista")
                .contains("só chame confirmarAgendamento depois que o usuário confirmar de forma explícita");
    }

    @Test
    void triagemSemPacienteMandaIdentificarAntesDeUsarOHistorico() {
        String roteiro = texto(prompts.triagemDeDor("dor de dente", null));

        assertThat(roteiro).contains("Paciente ainda não identificado").contains("buscarPaciente");
        assertThat(roteiro).doesNotContain("pacienteId 7");
    }

    @Test
    void triagemComPacientePedeAConsultaComOHistoricoDele() {
        String roteiro = texto(prompts.triagemDeDor("dor de dente", " 7 "));

        assertThat(roteiro).contains("Paciente já identificado: ID 7.")
                .contains("Informe o pacienteId 7 para que alergias, medicamentos e condições dele sejam considerados");
    }

    // ---------- agendar-consulta ----------

    @Test
    void agendarSegueOFluxoDeDoisPassosEConsultaAsRestricoesClinicas() {
        String roteiro = texto(prompts.agendarConsulta("Maria", "Canal", "próxima segunda de manhã"));

        assertThat(roteiro).contains("Paciente: Maria", "Procedimento: Canal", "próxima segunda de manhã");
        assertThat(roteiro).contains("nunca escolha por conta própria nem invente IDs");
        assertThat(roteiro).contains("consultarBaseClinica informando o pacienteId");   // restricao clinica antes de seguir
        assertThat(roteiro).contains("Só chame confirmarAgendamento", "de forma explícita");
        assertThat(roteiro.indexOf("buscarPaciente")).isLessThan(roteiro.indexOf("listarDentistas"));
        assertThat(roteiro.indexOf("horariosLivres")).isLessThan(roteiro.indexOf("prepararAgendamento"));
        assertThat(roteiro.indexOf("prepararAgendamento")).isLessThan(roteiro.indexOf("confirmarAgendamento"));
    }

    @Test
    void agendarSemPreferenciaDeHorarioDizIssoNoTexto() {
        assertThat(texto(prompts.agendarConsulta("Maria", "Limpeza", null))).contains("sem preferência");
        assertThat(texto(prompts.agendarConsulta("Maria", "Limpeza", "   "))).contains("sem preferência");
    }

    // ---------- resumo-do-paciente ----------

    @Test
    void resumoConsultaOHistoricoSoDoPacienteInformadoETrataComoDadoSensivel() {
        String roteiro = texto(prompts.resumoDoPaciente("104"));

        assertThat(roteiro).contains("paciente de ID 104", "consultasDoPaciente")
                .contains("consultarBaseClinica, informando o pacienteId 104")
                .contains("dados sensíveis")
                .contains("Cada acesso ao histórico fica registrado", "acessosAoHistorico")
                .contains("Se a base não tiver histórico do paciente, diga isso claramente em vez de supor")
                .contains("Não proponha tratamento nem prescrição");
    }

    // ---------- argumentos: o MCP nao impoe "required", quem valida e o servidor ----------

    @Test
    void argumentoObrigatorioAusenteOuEmBrancoEhRecusado() {
        for (String vazio : new String[] {null, "", "   ", "\n"}) {
            assertThatThrownBy(() -> prompts.triagemDeDor(vazio, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'sintomas'");
            assertThatThrownBy(() -> prompts.agendarConsulta(vazio, "Limpeza", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'paciente'");
            assertThatThrownBy(() -> prompts.agendarConsulta("Maria", vazio, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'procedimento'");
            assertThatThrownBy(() -> prompts.resumoDoPaciente(vazio))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'pacienteId'");
        }
    }

    @Test
    void pacienteIdPrecisaSerUmNumeroPositivo() {
        for (String invalido : new String[] {"abc", "0", "-3", "1.5", "7; DROP", "99999999999999999999"}) {
            assertThatThrownBy(() -> prompts.resumoDoPaciente(invalido))
                    .as("pacienteId=%s", invalido)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("número positivo");
            assertThatThrownBy(() -> prompts.triagemDeDor("dor", invalido))
                    .as("pacienteId=%s", invalido)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("número positivo");
        }
    }

    @Test
    void textoLivreTemLimiteDeTamanho() {
        String longo = "a".repeat(PromptsClinicos.LIMITE_DE_TEXTO + 1);
        String noLimite = "a".repeat(PromptsClinicos.LIMITE_DE_TEXTO);

        assertThatThrownBy(() -> prompts.triagemDeDor(longo, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no máximo");
        assertThatThrownBy(() -> prompts.agendarConsulta("Maria", "Limpeza", longo))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'quando'");
        assertThat(texto(prompts.triagemDeDor(noLimite, null))).contains(noLimite);
    }

    @Test
    void oTextoDoUsuarioEntraComoDadoSemSerTratadoComoModeloDeFormatacao() {
        // O texto digitado e inserido no roteiro; '%' e '%s' nele nao podem quebrar o String.formatted do roteiro.
        List<String> estranhos = List.of("100% de dor %s %d", "{0} ${sintomas}", "linha1\nlinha2");
        for (String sintomas : estranhos) {
            assertThat(texto(prompts.triagemDeDor(sintomas, null))).contains(sintomas);
        }
    }
}
