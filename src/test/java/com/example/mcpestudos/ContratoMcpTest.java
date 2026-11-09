package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste de CONTRATO do servidor MCP: o que o Claude enxerga (ferramentas, prompts e resources: nomes, parametros,
 * o que e obrigatorio, formato das respostas) nao pode mudar sem que alguem perceba.
 *
 * <p>Sobe o contexto Spring REAL da aplicacao (as mesmas propriedades, nome, instrucoes, ferramentas, prompts e
 * resources de producao). As unicas trocas: o transporte (no lugar do stdio ligado ao {@code System.in/out}, que o
 * Surefire usa para o proprio protocolo, entra um transporte stdio do SDK com streams em memoria, ver
 * {@link ClienteMcpEmMemoria}) e o modelo de embeddings (um falso e deterministico no lugar do Ollama). Os PDFs
 * sinteticos sao gerados numa pasta temporaria e indexados de verdade. Todo o resto e o que o Claude Desktop conversa.
 *
 * <p><b>Quando um teste de retrato falha</b>, algo foi adicionado, removido ou teve parametro alterado. Se a mudanca
 * e intencional, regenere os retratos e revise o diff no git:
 * <pre>mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true</pre>
 */
@SpringBootTest(properties = "spring.ai.mcp.server.stdio=false")   // o transporte vem do bean de teste abaixo
class ContratoMcpTest {

    /** Retratos versionados do contrato, um por tipo de coisa que o servidor publica. */
    private static final String RETRATO_FERRAMENTAS = "ferramentas-mcp.json";
    private static final String RETRATO_PROMPTS = "prompts-mcp.json";
    private static final String RETRATO_RECURSOS = "recursos-mcp.json";
    private static final String PASTA_DOS_RETRATOS = "/contrato/";
    /** Onde os retratos sao regravados com -Dcontrato.atualizar=true (relativo a raiz do projeto). */
    private static final Path PASTA_DOS_RETRATOS_NO_PROJETO = Path.of("src/test/resources/contrato");

    /** Nome de ferramenta ou de parametro como aparece nos textos: camelCase, como {@code consultarBaseClinica}. */
    private static final Pattern IDENTIFICADOR = Pattern.compile("\\b[a-z]+(?:[A-Z][a-z0-9]*)+\\b");

    // Documentos sinteticos (conteudo ficticio). Os nomes tem espaco, '%' e '+' de proposito: sao os caracteres que
    // mais atrapalham quando o nome do arquivo vira parte de um endereco (URI) de resource.
    private static final String PROTOCOLO_COM_ESPACO = "protocolo-anestesia local.pdf";
    private static final String PROTOCOLO_COM_SIMBOLOS = "protocolo-fluor 5%+selante.pdf";
    private static final String HISTORICO_DO_104 = "paciente-104-historico.pdf";
    private static final String MARCA_DO_HISTORICO = "SEGREDO-CLINICO-104";

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final ClienteMcpEmMemoria CLIENTE = new ClienteMcpEmMemoria();
    private static Map<String, Object> inicializacao;

    @TestConfiguration
    static class AmbienteDeTeste {
        @Bean
        StdioServerTransportProvider transporteStdioDeTeste() {
            return CLIENTE.transporte();
        }

        /** Sem Ollama: embeddings deterministicos. A indexacao dos PDFs e a busca sao as de producao. */
        @Bean
        @Primary
        EmbeddingModel embeddingsDeTeste() {
            return new EmbeddingFalso();
        }
    }

    @DynamicPropertySource
    static void ambienteTemporario(DynamicPropertyRegistry registry) throws IOException {
        Path pasta = Files.createTempDirectory("contrato-mcp");
        pasta.toFile().deleteOnExit();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + pasta.resolve("contrato.db") + "?foreign_keys=true");

        Path docs = Files.createDirectory(pasta.resolve("docs"));
        PdfsDeTeste.criar(docs.resolve(PROTOCOLO_COM_ESPACO), "Protocolo AL-01: pacientes hipertensos nao devem "
                + "receber anestesico local com vasoconstritor em dose alta. Prefira lidocaina sem vasoconstritor.");
        PdfsDeTeste.criar(docs.resolve(PROTOCOLO_COM_SIMBOLOS), "Protocolo FL-05: o verniz de fluor a 5% pode ser "
                + "aplicado a cada tres meses em pacientes de alto risco de carie.");
        PdfsDeTeste.criar(docs.resolve(HISTORICO_DO_104), "Historico do paciente 104: " + MARCA_DO_HISTORICO
                + " alergia a penicilina e hipertensao controlada. Evitar amoxicilina.");
        registry.add("clinica.rag.docs-location", () -> "file:" + docs + "/*.pdf");
    }

    @BeforeEach
    void conectar() {
        if (inicializacao == null) {
            inicializacao = CLIENTE.iniciar();
        }
    }

    // ---------- o servidor ----------

    @Test
    void oServidorSeApresentaComONomeAVersaoEAsInstrucoesCertas() {
        Map<String, Object> servidor = ClienteMcpEmMemoria.mapa(inicializacao.get("serverInfo"));
        assertThat(servidor).containsEntry("name", "odonto-mcp").containsEntry("version", "0.0.1");
        // "odonto-mcp" e a chave que o Claude Desktop mostra: renomear muda o que o usuario ve.

        assertThat((String) inicializacao.get("instructions"))
                .as("instrucoes do servidor (orientam o modelo antes de qualquer chamada)")
                .contains("consultarBaseClinica")
                .contains("pacienteId")
                .contains("prepararAgendamento")
                .contains("confirmarAgendamento")
                .contains("explicita");   // so confirmar depois de o usuario confirmar de forma explicita
    }

    @Test
    void oServidorAnunciaFerramentasPromptsEResources() {
        assertThat(ClienteMcpEmMemoria.mapa(inicializacao.get("capabilities")))
                .containsKeys("tools", "prompts", "resources");
    }

    // ---------- o contrato: retratos versionados ----------

    @Test
    void asFerramentasPublicadasBatemComORetratoVersionado() throws IOException {
        conferirRetrato(RETRATO_FERRAMENTAS, "FERRAMENTAS", ferramentasAtuais());
    }

    @Test
    void osPromptsPublicadosBatemComORetratoVersionado() throws IOException {
        conferirRetrato(RETRATO_PROMPTS, "PROMPTS", promptsAtuais());
    }

    @Test
    void osResourcesPublicadosBatemComORetratoVersionado() throws IOException {
        conferirRetrato(RETRATO_RECURSOS, "RESOURCES", recursosAtuais());
    }

    // ---------- descricoes: o que o modelo e o usuario leem ----------

    @Test
    void todaFerramentaETodoParametroTemDescricaoParaOModelo() {
        // A descricao e o "prompt" da ferramenta: sem ela o modelo nao sabe quando nem como chamar.
        for (Map<String, Object> ferramenta : CLIENTE.listarFerramentas()) {
            String nome = (String) ferramenta.get("name");
            assertThat((String) ferramenta.get("description"))
                    .as("descricao da ferramenta %s", nome).isNotBlank().hasSizeGreaterThan(40);
            Map<String, Object> propriedades = ClienteMcpEmMemoria.mapa(
                    ClienteMcpEmMemoria.mapa(ferramenta.get("inputSchema")).get("properties"));
            propriedades.forEach((parametro, definicao) -> assertThat((String) ClienteMcpEmMemoria.mapa(definicao).get("description"))
                    .as("descricao do parametro %s.%s", nome, parametro).isNotBlank());
        }
    }

    @Test
    void todoPromptETodoArgumentoTemDescricaoParaOUsuario() {
        // O menu de prompts do cliente mostra titulo e descricao; o formulario mostra a descricao de cada argumento.
        for (Map<String, Object> prompt : CLIENTE.listarPrompts()) {
            String nome = (String) prompt.get("name");
            assertThat((String) prompt.get("title")).as("titulo do prompt %s", nome).isNotBlank();
            assertThat((String) prompt.get("description"))
                    .as("descricao do prompt %s", nome).isNotBlank().hasSizeGreaterThan(40);
            for (Map<String, Object> argumento : argumentosDe(prompt)) {
                assertThat((String) argumento.get("description"))
                        .as("descricao do argumento %s.%s", nome, argumento.get("name")).isNotBlank();
            }
        }
    }

    @Test
    void todoResourceTemDescricaoETipoDeConteudo() {
        List<Map<String, Object>> todos = new ArrayList<>(CLIENTE.listarRecursos());
        todos.addAll(CLIENTE.listarModelosDeRecurso());
        assertThat(todos).isNotEmpty();
        for (Map<String, Object> recurso : todos) {
            assertThat((String) recurso.get("name")).as("nome de %s", recurso).isNotBlank();
            assertThat((String) recurso.get("description")).as("descricao de %s", recurso.get("name")).isNotBlank();
            assertThat((String) recurso.get("mimeType")).as("tipo de conteudo de %s", recurso.get("name")).isNotBlank();
        }
    }

    @Test
    void asDescricoesCarregamOsAvisosQueGuiamOComportamentoDoModelo() {
        Map<String, String> descricoes = new LinkedHashMap<>();
        CLIENTE.listarFerramentas().forEach(f -> descricoes.put((String) f.get("name"), (String) f.get("description")));

        assertThat(descricoes.get("prepararAgendamento")).contains("SEM grava-lo")     // preparar nao agenda
                .contains("confirmacao explicita");                                    // e pede o sim do usuario
        assertThat(descricoes.get("confirmarAgendamento")).contains("confirmar explicitamente"); // so depois do sim
        assertThat(descricoes.get("cancelarConsulta")).contains("futuras");            // so cancela consulta futura
        assertThat(descricoes.get("consultarBaseClinica"))
                .contains("ANTES de agendar")                                          // consultar protocolo antes
                .contains("pacienteId")                                                // escopo por paciente
                .contains("em vez de supor");                                          // nao inventar protocolo
    }

    // ---------- os textos nao podem ficar obsoletos ----------

    @Test
    void osRoteirosDosPromptsSoCitamFerramentasEParametrosQueOServidorPublica() {
        // Renomear uma ferramenta ou um parametro sem atualizar o roteiro deixaria o modelo seguindo um passo-a-passo
        // que cita algo que nao existe mais. Este teste pega isso. Cada prompt e pedido de duas formas: so com os
        // argumentos obrigatorios e com todos, para que todo ramo do texto seja examinado.
        Set<String> publicados = nomesDeFerramentasEParametros();
        for (Map<String, Object> prompt : CLIENTE.listarPrompts()) {
            String nome = (String) prompt.get("name");
            for (boolean comOpcionais : new boolean[] {false, true}) {
                String roteiro = textoDoPrompt(nome, argumentosDeExemplo(prompt, comOpcionais));
                Set<String> citados = identificadoresCitados(roteiro);
                assertThat(citados).as("o roteiro '%s' cita ferramentas ou parametros?", nome).isNotEmpty();
                assertThat(publicados).as("o roteiro '%s' cita algo que o servidor nao publica", nome)
                        .containsAll(citados);
            }
        }
    }

    @Test
    void asInstrucoesDoServidorEOResourceDeRegrasSoCitamOQueOServidorPublica() {
        Set<String> publicados = nomesDeFerramentasEParametros();
        String regras = textoDoResource("clinica://regras-de-agendamento");

        for (String[] origemETexto : new String[][] {
                {"instrucoes do servidor", (String) inicializacao.get("instructions")},
                {"clinica://regras-de-agendamento", regras}}) {
            Set<String> citados = identificadoresCitados(origemETexto[1]);
            assertThat(citados).as("identificadores em %s", origemETexto[0]).isNotEmpty();
            assertThat(publicados).as("%s cita algo que o servidor nao publica", origemETexto[0]).containsAll(citados);
        }
    }

    // ---------- prompts pelo protocolo ----------

    @Test
    void umPromptDevolveUmaMensagemDoUsuarioComOsArgumentosNoTexto() {
        Map<String, Object> resposta = CLIENTE.obterPrompt("agendar-consulta",
                Map.of("paciente", "Maria", "procedimento", "Limpeza", "quando", "proxima segunda de manha"));

        Map<String, Object> resultado = ClienteMcpEmMemoria.mapa(resposta.get("result"));
        List<?> mensagens = (List<?>) resultado.get("messages");
        assertThat(mensagens).hasSize(1);
        Map<String, Object> mensagem = ClienteMcpEmMemoria.mapa(mensagens.get(0));
        // USER: um roteiro que o usuario escolheu, nao uma fala do assistente.
        assertThat(mensagem.get("role")).isEqualTo("user");
        Map<String, Object> conteudo = ClienteMcpEmMemoria.mapa(mensagem.get("content"));
        assertThat(conteudo.get("type")).isEqualTo("text");
        assertThat((String) conteudo.get("text")).contains("Maria", "Limpeza", "proxima segunda de manha");
    }

    @Test
    void promptSemArgumentoObrigatorioViraErroDoProtocoloComMensagemClara() {
        // O MCP nao impoe "required" dos argumentos de prompt: quem valida e o proprio servidor.
        Map<String, Object> resposta = CLIENTE.obterPrompt("agendar-consulta", Map.of("paciente", "Maria"));

        assertThat(resposta).doesNotContainKey("result");
        Map<String, Object> erro = ClienteMcpEmMemoria.mapa(resposta.get("error"));
        assertThat(erro.get("code")).isEqualTo(-32602);   // parametros invalidos
        assertThat((String) erro.get("data")).contains("procedimento");
    }

    @Test
    void promptComPacienteIdInvalidoOuInexistenteViraErroDoProtocolo() {
        Map<String, Object> idInvalido = CLIENTE.obterPrompt("resumo-do-paciente", Map.of("pacienteId", "abc"));
        assertThat(ClienteMcpEmMemoria.mapa(idInvalido.get("error")).get("data").toString()).contains("pacienteId");

        Map<String, Object> inexistente = CLIENTE.obterPrompt("nao-existe", Map.of());
        assertThat(ClienteMcpEmMemoria.mapa(inexistente.get("error")).get("code")).isEqualTo(-32602);
    }

    // ---------- resources pelo protocolo ----------

    @Test
    void oResourceDeDentistasTemOMesmoFormatoDaFerramenta() {
        List<Object> doResource = lista(lerJson("clinica://dentistas"));
        List<Object> daFerramenta = lista(CLIENTE.chamarFerramentaEObterJson("listarDentistas", Map.of()));

        assertThat(chaves(doResource.get(0))).containsExactlyInAnyOrder("id", "nome", "especialidade");
        assertThat(doResource).containsExactlyInAnyOrderElementsOf(daFerramenta);
    }

    @Test
    void oResourceDeRegrasDescreveOAgendamentoEmDoisPassos() {
        assertThat(textoDoResource("clinica://regras-de-agendamento"))
                .contains("segunda a sexta-feira", "08:00", "17:00", "America/Sao_Paulo", "10 minutos")
                .contains("prepararAgendamento", "confirmarAgendamento");
    }

    @Test
    void oIndiceDeProtocolosListaSoProtocolosComEnderecosQueFuncionam() {
        Map<?, ?> indice = (Map<?, ?>) lerJson("clinica://protocolos");

        assertThat(indice.get("estado")).isEqualTo("PRONTA");
        List<Object> itens = lista(indice.get("protocolos"));
        assertThat(itens).extracting(i -> campo(i, "arquivo"))
                .containsExactly(PROTOCOLO_COM_ESPACO, PROTOCOLO_COM_SIMBOLOS);     // so protocolos, em ordem
        assertThat(chaves(itens.get(0))).containsExactlyInAnyOrder("arquivo", "uri", "paginas", "trechos");
        // O nome vai na URI codificado: espaco -> %20, '%' -> %25, '+' -> %2B.
        assertThat(itens).extracting(i -> campo(i, "uri")).containsExactly(
                "clinica://protocolos/protocolo-anestesia%20local.pdf",
                "clinica://protocolos/protocolo-fluor%205%25%2Bselante.pdf");

        // Cada endereco do indice, lido como esta, devolve o texto do protocolo certo.
        for (Object item : itens) {
            Map<?, ?> protocolo = (Map<?, ?>) item;
            String texto = textoDoResource((String) protocolo.get("uri"));
            assertThat(texto).startsWith("# " + protocolo.get("arquivo") + "\n").contains("[página 1]");
        }
        assertThat(textoDoResource("clinica://protocolos/protocolo-anestesia%20local.pdf")).contains("Protocolo AL-01");
        assertThat(textoDoResource("clinica://protocolos/protocolo-fluor%205%25%2Bselante.pdf")).contains("Protocolo FL-05");
    }

    @Test
    void historicoDePacienteNuncaEhLegivelPorResource() {
        // O resource nao passa pelo filtro por paciente de consultarBaseClinica: nao pode haver caminho ate o historico.
        Map<String, Object> pedido = CLIENTE.lerRecurso("clinica://protocolos/" + HISTORICO_DO_104);
        assertThat(pedido).containsKey("error");
        assertThat(pedido.toString()).doesNotContain(MARCA_DO_HISTORICO);

        List<String> enderecos = new ArrayList<>();
        CLIENTE.listarRecursos().forEach(r -> enderecos.add((String) r.get("uri")));
        ((List<?>) ((Map<?, ?>) lerJson("clinica://protocolos")).get("protocolos"))
                .forEach(p -> enderecos.add((String) ((Map<?, ?>) p).get("uri")));
        assertThat(enderecos).isNotEmpty().noneMatch(e -> e.contains("paciente"));
        for (String endereco : enderecos) {
            assertThat(textoDoResource(endereco)).as("conteudo de %s", endereco)
                    .doesNotContain(MARCA_DO_HISTORICO).doesNotContain("penicilina");
        }
    }

    @Test
    void resourceInexistenteOuProtocoloInexistenteViraErroDoProtocolo() {
        Map<String, Object> semResource = CLIENTE.lerRecurso("clinica://nao-existe");
        assertThat(ClienteMcpEmMemoria.mapa(semResource.get("error")).get("code")).isEqualTo(-32002);

        Map<String, Object> semProtocolo = CLIENTE.lerRecurso("clinica://protocolos/inexistente.pdf");
        Map<String, Object> erro = ClienteMcpEmMemoria.mapa(semProtocolo.get("error"));
        assertThat((String) erro.get("data")).contains("Protocolo não encontrado").contains("clinica://protocolos");
    }

    // ---------- o formato das respostas das ferramentas ----------

    @Test
    void asRespostasMantemOFormatoEsperadoPeloModelo() {
        Map<?, ?> hoje = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("dataDeHoje", Map.of());
        assertThat(chaves(hoje)).containsExactlyInAnyOrder("data", "diaDaSemana");
        assertThat((String) hoje.get("data")).matches("\\d{4}-\\d{2}-\\d{2}");

        List<?> dentistas = (List<?>) CLIENTE.chamarFerramentaEObterJson("listarDentistas", Map.of());
        assertThat(dentistas).isNotEmpty();
        assertThat(chaves(dentistas.get(0))).containsExactlyInAnyOrder("id", "nome", "especialidade");

        // Envelope Resultado: {sucesso, dados, erro}
        Map<?, ?> pacientes = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("buscarPaciente", Map.of("nome", "Maria"));
        assertThat(chaves(pacientes)).containsExactlyInAnyOrder("sucesso", "dados", "erro");
        assertThat(pacientes.get("sucesso")).isEqualTo(true);
        assertThat(chaves(((List<?>) pacientes.get("dados")).get(0)))
                .containsExactlyInAnyOrder("id", "nome", "telefone");

        Map<?, ?> recusa = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("buscarPaciente", Map.of("nome", "ab"));
        assertThat(recusa.get("sucesso")).isEqualTo(false);
        assertThat((String) recusa.get("erro")).contains("3 caracteres");

        Map<?, ?> dataInvalida = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("horariosLivres",
                Map.of("dentistaId", 1, "data", "nao-e-uma-data"));
        assertThat(dataInvalida.get("sucesso")).isEqualTo(false);
        assertThat((String) dataInvalida.get("erro")).contains("AAAA-MM-DD");

        Map<?, ?> duvidaCurta = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("consultarBaseClinica", Map.of("duvida", "abc"));
        assertThat(duvidaCurta.get("sucesso")).isEqualTo(false);
        assertThat((String) duvidaCurta.get("erro")).contains("5 caracteres");
    }

    @Test
    void aBaseClinicaDevolveTrechosNoFormatoEsperadoEOHistoricoSoComOPacienteCerto() {
        String duvida = "anestesico com vasoconstritor para paciente hipertenso alergia penicilina";

        // Sem pacienteId: so orientacao geral (protocolos), nunca o historico de ninguem.
        Map<?, ?> geral = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("consultarBaseClinica", Map.of("duvida", duvida));
        assertThat(geral.get("sucesso")).isEqualTo(true);
        Map<?, ?> dados = (Map<?, ?>) geral.get("dados");
        assertThat(chaves(dados)).containsExactlyInAnyOrder("duvida", "pacienteId", "trechos", "aviso");
        List<Object> trechos = lista(dados.get("trechos"));
        assertThat(trechos).isNotEmpty();
        assertThat(chaves(trechos.get(0)))
                .containsExactlyInAnyOrder("fonte", "pagina", "similaridade", "escopo", "pacienteId", "texto");
        assertThat(trechos).extracting(t -> campo(t, "escopo")).containsOnly("PROTOCOLO");
        assertThat(geral.toString()).doesNotContain(MARCA_DO_HISTORICO);

        // Com o pacienteId do dono do historico, o historico dele entra; com o de outro paciente, nao.
        Map<?, ?> doPaciente = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("consultarBaseClinica",
                Map.of("duvida", duvida, "pacienteId", 104));
        assertThat(doPaciente.toString()).contains(MARCA_DO_HISTORICO);
        Map<?, ?> deOutro = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("consultarBaseClinica",
                Map.of("duvida", duvida, "pacienteId", 1));
        assertThat(deOutro.toString()).doesNotContain(MARCA_DO_HISTORICO);
    }

    @Test
    void oAcessoAoHistoricoFicaRegistradoELegivelPelaFerramentaDeAuditoria() {
        CLIENTE.chamarFerramentaEObterJson("consultasDoPaciente", Map.of("pacienteId", 104));
        CLIENTE.chamarFerramentaEObterJson("consultarBaseClinica",
                Map.of("duvida", "alergia a penicilina do paciente", "pacienteId", 104));

        Map<?, ?> resposta = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("acessosAoHistorico",
                Map.of("pacienteId", 104));

        assertThat(resposta.get("sucesso")).isEqualTo(true);
        Map<?, ?> dados = (Map<?, ?>) resposta.get("dados");
        assertThat(chaves(dados)).containsExactlyInAnyOrder("pacienteId", "total", "acessos", "aviso");
        List<Object> acessos = lista(dados.get("acessos"));
        assertThat(chaves(acessos.get(0)))
                .containsExactlyInAnyOrder("id", "pacienteId", "ferramenta", "acessadoEm", "trechos");
        assertThat((String) campo(acessos.get(0), "acessadoEm")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[-+]\\d{2}:\\d{2}");
        // O nome gravado no registro e o nome PUBLICADO da ferramenta: renomear uma ferramenta sem ajustar o registro
        // deixaria o historico de auditoria apontando para algo que nao existe mais.
        Set<String> publicadas = new TreeSet<>();
        CLIENTE.listarFerramentas().forEach(f -> publicadas.add((String) f.get("name")));
        assertThat(acessos).extracting(a -> campo(a, "ferramenta"))
                .contains("consultasDoPaciente", "consultarBaseClinica")
                .allSatisfy(nome -> assertThat(publicadas).contains((String) nome));
        // Nada da pergunta nem do conteudo do historico vai para o registro.
        assertThat(resposta.toString()).doesNotContain("penicilina").doesNotContain(MARCA_DO_HISTORICO);
        assertThat((String) dados.get("aviso")).contains("QUEM").contains("nao tem login");
    }

    @Test
    void oFluxoDeAgendamentoEmDoisPassosMantemOFormatoDasRespostas() {
        // Proximo dia util daqui a alguns dias, longe da consulta de exemplo que a aplicacao semeia no 1o dia util.
        LocalDate dia = LocalDate.now(ZoneId.of("America/Sao_Paulo")).plusDays(5);
        while (dia.getDayOfWeek() == DayOfWeek.SATURDAY || dia.getDayOfWeek() == DayOfWeek.SUNDAY) {
            dia = dia.plusDays(1);
        }

        Map<?, ?> proposta = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("prepararAgendamento",
                Map.of("dentistaId", 2, "pacienteId", 2, "data", dia.toString(), "hora", "08:00", "procedimento", "Avaliacao"));
        assertThat(proposta.get("sucesso")).isEqualTo(true);
        Map<?, ?> dados = (Map<?, ?>) proposta.get("dados");
        assertThat(chaves(dados)).containsExactlyInAnyOrder("codigoConfirmacao", "resumo", "dentista", "paciente", "data",
                "diaDaSemana", "hora", "procedimento", "expiraEm", "validadeEmMinutos", "proximoPasso");
        assertThat((String) dados.get("codigoConfirmacao")).matches("[2-9A-HJ-NP-Z]{8}");

        Map<?, ?> confirmada = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("confirmarAgendamento",
                Map.of("codigoConfirmacao", dados.get("codigoConfirmacao")));
        assertThat(confirmada.get("sucesso")).isEqualTo(true);
        assertThat(chaves(confirmada.get("dados"))).containsExactlyInAnyOrder("consultaId", "data", "hora", "procedimento");

        Map<?, ?> repetida = (Map<?, ?>) CLIENTE.chamarFerramentaEObterJson("confirmarAgendamento",
                Map.of("codigoConfirmacao", dados.get("codigoConfirmacao")));
        assertThat(repetida.get("sucesso")).isEqualTo(false);
        assertThat((String) repetida.get("erro")).contains("ja foi usado");
    }

    @Test
    void parametroObrigatorioAusenteViraErroDoProtocoloENaoChegaNaFerramenta() {
        // O SDK valida a entrada contra o schema antes de chamar o metodo: sem pacienteId, nem executa.
        Map<String, Object> resultado = CLIENTE.chamarFerramenta("consultasDoPaciente", Map.of());

        assertThat(resultado.get("isError")).isEqualTo(true);
        assertThat(resultado.toString()).contains("pacienteId");
    }

    // ---------- auxiliares de leitura ----------

    private static Set<String> chaves(Object mapa) {
        Set<String> nomes = new TreeSet<>();
        ((Map<?, ?>) mapa).keySet().forEach(k -> nomes.add(String.valueOf(k)));
        return nomes;
    }

    /** O valor de uma chave de um objeto JSON ja lido (o tipo curinga de {@code Map<?, ?>} atrapalha o AssertJ). */
    private static Object campo(Object objetoJson, String chave) {
        return ((Map<?, ?>) objetoJson).get(chave);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> lista(Object valor) {
        return (List<Object>) valor;
    }

    private static List<Map<String, Object>> argumentosDe(Map<String, Object> prompt) {
        List<?> argumentos = (List<?>) prompt.getOrDefault("arguments", List.of());
        return argumentos.stream().map(ClienteMcpEmMemoria::mapa).toList();
    }

    /** Valores validos para os argumentos de um prompt; {@code pacienteId} precisa ser numerico. */
    private static Map<String, Object> argumentosDeExemplo(Map<String, Object> prompt, boolean comOpcionais) {
        Map<String, Object> valores = new LinkedHashMap<>();
        for (Map<String, Object> argumento : argumentosDe(prompt)) {
            if (comOpcionais || Boolean.TRUE.equals(argumento.get("required"))) {
                valores.put((String) argumento.get("name"), "pacienteId".equals(argumento.get("name")) ? "1" : "exemplo");
            }
        }
        return valores;
    }

    private static String textoDoPrompt(String nome, Map<String, Object> argumentos) {
        Map<String, Object> resposta = CLIENTE.obterPrompt(nome, argumentos);
        assertThat(resposta).as("prompt %s com %s", nome, argumentos).containsKey("result");
        List<?> mensagens = (List<?>) ClienteMcpEmMemoria.mapa(resposta.get("result")).get("messages");
        return (String) ClienteMcpEmMemoria.mapa(ClienteMcpEmMemoria.mapa(mensagens.get(0)).get("content")).get("text");
    }

    private static String textoDoResource(String uri) {
        Map<String, Object> resposta = CLIENTE.lerRecurso(uri);
        assertThat(resposta).as("leitura de %s", uri).containsKey("result");
        List<?> conteudos = (List<?>) ClienteMcpEmMemoria.mapa(resposta.get("result")).get("contents");
        assertThat(conteudos).as("conteudos de %s", uri).hasSize(1);
        return (String) ClienteMcpEmMemoria.mapa(conteudos.get(0)).get("text");
    }

    private static Object lerJson(String uri) {
        return JSON.readValue(textoDoResource(uri), Object.class);
    }

    /** Nomes de ferramentas e de parametros de ferramentas que o servidor publica (o vocabulario dos textos). */
    private static Set<String> nomesDeFerramentasEParametros() {
        Set<String> nomes = new TreeSet<>();
        for (Map<String, Object> ferramenta : CLIENTE.listarFerramentas()) {
            nomes.add((String) ferramenta.get("name"));
            nomes.addAll(ClienteMcpEmMemoria.mapa(
                    ClienteMcpEmMemoria.mapa(ferramenta.get("inputSchema")).get("properties")).keySet());
        }
        return nomes;
    }

    private static Set<String> identificadoresCitados(String texto) {
        Set<String> citados = new TreeSet<>();
        Matcher m = IDENTIFICADOR.matcher(texto);
        while (m.find()) {
            citados.add(m.group());
        }
        return citados;
    }

    // ---------- os retratos ----------

    /** Ferramentas como o cliente as ve, em forma canonica (chaves ordenadas, sem textos de descricao). */
    private static Map<String, Object> ferramentasAtuais() {
        Map<String, Object> contrato = new TreeMap<>();
        for (Map<String, Object> ferramenta : CLIENTE.listarFerramentas()) {
            Map<String, Object> entrada = new TreeMap<>();
            entrada.put("inputSchema", canonico(ferramenta.get("inputSchema")));
            contrato.put((String) ferramenta.get("name"), entrada);
        }
        return contrato;
    }

    /** Prompts: o nome e, de cada argumento, o nome e se e obrigatorio (titulo e descricoes sao texto, nao estrutura). */
    private static Map<String, Object> promptsAtuais() {
        Map<String, Object> contrato = new TreeMap<>();
        for (Map<String, Object> prompt : CLIENTE.listarPrompts()) {
            Map<String, Boolean> argumentos = new TreeMap<>();
            argumentosDe(prompt).forEach(a -> argumentos.put((String) a.get("name"), Boolean.TRUE.equals(a.get("required"))));
            Map<String, Object> entrada = new TreeMap<>();
            entrada.put("argumentos", argumentos);
            contrato.put((String) prompt.get("name"), entrada);
        }
        return contrato;
    }

    /** Resources fixos (chave = URI) e modelos de resource (chave = modelo de URI): nome e tipo de conteudo. */
    private static Map<String, Object> recursosAtuais() {
        Map<String, Object> contrato = new TreeMap<>();
        for (Map<String, Object> recurso : CLIENTE.listarRecursos()) {
            contrato.put((String) recurso.get("uri"), entradaDeRecurso(recurso, false));
        }
        for (Map<String, Object> modelo : CLIENTE.listarModelosDeRecurso()) {
            contrato.put((String) modelo.get("uriTemplate"), entradaDeRecurso(modelo, true));
        }
        return contrato;
    }

    private static Map<String, Object> entradaDeRecurso(Map<String, Object> recurso, boolean modelo) {
        Map<String, Object> entrada = new TreeMap<>();
        entrada.put("name", recurso.get("name"));
        entrada.put("mimeType", recurso.get("mimeType"));
        entrada.put("modeloDeUri", modelo);
        return entrada;
    }

    /**
     * Ordena as chaves, ordena as listas de campos obrigatorios e descarta os textos {@code description}:
     * o contrato e a ESTRUTURA (nomes, tipos, obrigatoriedade). Ajustar o texto de uma descricao e engenharia
     * de prompt, nao quebra de contrato; ele e vigiado a parte pelas regras de descricao acima.
     */
    private static Object canonico(Object valor) {
        if (valor instanceof Map<?, ?> mapa) {
            Map<String, Object> ordenado = new TreeMap<>();
            mapa.forEach((chave, v) -> {
                String nome = String.valueOf(chave);
                if (!"description".equals(nome) || ehPropriedadeChamadaDescription(mapa)) {
                    ordenado.put(nome, canonico(v));
                }
            });
            return ordenado;
        }
        if (valor instanceof List<?> lista) {
            List<Object> itens = new ArrayList<>();
            lista.forEach(v -> itens.add(canonico(v)));
            if (itens.stream().allMatch(String.class::isInstance)) {
                itens.sort(null);
            }
            return itens;
        }
        return valor;
    }

    /** Um parametro de nome "description" dentro de {@code properties} nao e o texto de descricao. */
    private static boolean ehPropriedadeChamadaDescription(Map<?, ?> mapa) {
        Object descricao = mapa.get("description");
        return descricao instanceof Map<?, ?>;
    }

    /**
     * Compara o contrato atual com o retrato versionado. Com {@code -Dcontrato.atualizar=true} regrava o retrato em
     * vez de comparar.
     */
    private static void conferirRetrato(String arquivo, String titulo, Map<String, Object> atual) throws IOException {
        if (Boolean.getBoolean("contrato.atualizar")) {
            Path destino = PASTA_DOS_RETRATOS_NO_PROJETO.resolve(arquivo);
            Files.createDirectories(destino.getParent());
            Files.writeString(destino, JSON.writeValueAsString(atual) + "\n", StandardCharsets.UTF_8);
            System.out.println("[contrato] " + titulo + " regravado em " + destino.toAbsolutePath()
                    + " (" + atual.size() + " itens). Revise o diff no git antes de commitar.");
            return;
        }
        Map<String, Object> esperado = lerRetrato(arquivo);
        if (!esperado.equals(atual)) {
            fail(explicarDiferencas(titulo, esperado, atual));
        }
    }

    private static Map<String, Object> lerRetrato(String arquivo) throws IOException {
        try (InputStream in = ContratoMcpTest.class.getResourceAsStream(PASTA_DOS_RETRATOS + arquivo)) {
            if (in == null) {
                fail("Retrato do contrato nao encontrado em " + PASTA_DOS_RETRATOS + arquivo + ". Gere-o uma vez com: "
                        + "mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true");
            }
            return ClienteMcpEmMemoria.mapa(JSON.readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Object.class));
        }
    }

    private static String explicarDiferencas(String titulo, Map<String, Object> esperado, Map<String, Object> atual) {
        TreeSet<String> removidos = new TreeSet<>(esperado.keySet());
        removidos.removeAll(atual.keySet());
        TreeSet<String> adicionados = new TreeSet<>(atual.keySet());
        adicionados.removeAll(esperado.keySet());

        StringBuilder msg = new StringBuilder("\nO CONTRATO MCP MUDOU (" + titulo + ").\n");
        if (!removidos.isEmpty()) {
            msg.append("\n- REMOVIDOS ou RENOMEADOS (o Claude e o usuario deixam de encontra-los): ").append(removidos);
        }
        if (!adicionados.isEmpty()) {
            msg.append("\n- NOVOS: ").append(adicionados);
        }
        for (String nome : esperado.keySet()) {
            if (atual.containsKey(nome) && !esperado.get(nome).equals(atual.get(nome))) {
                msg.append("\n- ALTERADO: ").append(nome).append("\n    esperado: ")
                        .append(JSON.writeValueAsString(esperado.get(nome)).replaceAll("\\s+", " "))
                        .append("\n    atual:    ")
                        .append(JSON.writeValueAsString(atual.get(nome)).replaceAll("\\s+", " "));
            }
        }
        msg.append("\n\nSe a mudanca e intencional, regenere os retratos e revise o diff no git:\n")
                .append("    mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true\n");
        return msg.toString();
    }
}
