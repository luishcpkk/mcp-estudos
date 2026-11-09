# mcp-estudos

Servidor MCP (Spring Boot + Spring AI) de uma clínica odontológica fictícia: agendamento de consultas, consulta a uma
base clínica em PDFs (RAG) e, além das ferramentas, **prompts** e **resources** do MCP. Conversa com o Claude Desktop por
**stdio**.

## Requisitos

- JDK 17 ou superior e Maven (ou `mvnw`).
- Para a base clínica: [Ollama](https://ollama.com) em execução com o modelo de embeddings (`ollama pull bge-m3`).
  Sem o Ollama o servidor sobe normalmente; só `consultarBaseClinica` avisa que a base está indisponível.

## Rodar no Claude Desktop

```bash
mvn package
```

No `claude_desktop_config.json`, aponte para o jar gerado em `target/` (reinicie o Claude Desktop depois de recompilar):

```json
{
  "mcpServers": {
    "odonto-mcp": {
      "command": "java",
      "args": ["-jar", "C:\\caminho\\para\\mcp-estudos\\target\\mcp-estudos-0.0.1-SNAPSHOT.jar"]
    }
  }
}
```

Para a base clínica, acrescente em `args` a pasta dos PDFs (assim não precisa recompilar a cada PDF novo):
`"--clinica.rag.docs-location=file:C:/odonto-docs/*.pdf"`. Convenção de nomes dos PDFs em
[`src/main/resources/docs/README.md`](src/main/resources/docs/README.md).

## Ferramentas, prompts e resources

O MCP tem três formas de o servidor oferecer algo ao cliente, e cada uma tem um "dono":

| Primitiva | Quem aciona | Neste servidor |
|---|---|---|
| **Tool** (ferramenta) | o **modelo** decide chamar | `buscarPaciente`, `horariosLivres`, `prepararAgendamento`, `confirmarAgendamento`, `consultarBaseClinica`... |
| **Prompt** | o **usuário** escolhe, como um atalho | `triagem-de-dor`, `agendar-consulta`, `resumo-do-paciente` |
| **Resource** | o **usuário** (ou a aplicação) anexa à conversa | `clinica://dentistas`, `clinica://regras-de-agendamento`, `clinica://protocolos`, `clinica://protocolos/{arquivo}` |

**Prompts** são roteiros versionados junto com o código: dizem ao modelo, passo a passo, quais ferramentas usar e em que
ordem. Cada um chega ao modelo como uma mensagem do **usuário**, com os argumentos que você preencheu no texto.

| Prompt | Argumentos | O roteiro |
|---|---|---|
| `triagem-de-dor` | `sintomas` (obrigatório), `pacienteId` | Sinais de alarme primeiro (se houver, orienta pronto-socorro e **não** agenda), depois `consultarBaseClinica`, perguntas objetivas e, se o usuário quiser, o agendamento |
| `agendar-consulta` | `paciente`, `procedimento` (obrigatórios), `quando` | `buscarPaciente` → dentista → horários livres → restrições clínicas na base → `prepararAgendamento` → `confirmarAgendamento` só depois do "sim" explícito |
| `resumo-do-paciente` | `pacienteId` (obrigatório) | Consultas do paciente + o histórico dele na base (alergias, medicamentos, condições), tratado como dado sensível |

**Resources** são conteúdo de leitura que o cliente anexa sem o modelo precisar chamar uma ferramenta:
`clinica://dentistas` (JSON), `clinica://regras-de-agendamento` (Markdown, montado a partir das mesmas constantes que as
ferramentas usam), `clinica://protocolos` (índice dos protocolos indexados, com o endereço de cada um) e
`clinica://protocolos/{arquivo}` (o texto de um protocolo, por página).

**Histórico de paciente nunca é resource.** Um resource não passa pelo filtro por paciente de `consultarBaseClinica`; se o
histórico fosse um resource, qualquer cliente conseguiria ler o de qualquer paciente. Só PDFs `protocolo-*.pdf` entram no
índice, e `ContratoMcpTest` garante que nenhum caminho de resource chega a um `paciente-<id>-*.pdf`.

No Claude Desktop, prompts e resources costumam aparecer no menu de anexar (o botão "+") do campo de mensagem, sob o nome
do servidor (`odonto-mcp`). **Não consegui conferir isso na sua instalação**: a tela muda de versão para versão, e o que o
Claude faz com um roteiro é decisão do modelo, que os testes não medem. Se o menu não mostrar nada, o Inspector (abas
**Prompts** e **Resources**, ou os comandos abaixo) mostra o que o servidor publica.

## Depurar com o MCP Inspector (sem gerar o `.jar`)

O [MCP Inspector](https://github.com/modelcontextprotocol/inspector) conecta no servidor, lista as ferramentas e deixa
você chamá-las, sem configurar o Claude Desktop. Ele **inicia** o servidor por você, então basta dizer qual comando
sobe o código-fonte: `mvn -q spring-boot:run`. Precisa do [Node.js](https://nodejs.org) (testado com a versão 22).
Os comandos abaixo foram testados no Linux; o Windows está descrito mais adiante e **não foi testado**.

Abra um terminal **na raiz do projeto** (no IntelliJ: `Alt+F12`). Antes, compile uma vez, para o servidor subir rápido
quando o Inspector o iniciar:

```bash
mvn -q compile
```

### Interface web (a mais prática)

```bash
npx @modelcontextprotocol/inspector --web --config inspector/mcp-inspector.json
```

O Inspector imprime uma URL parecida com `http://127.0.0.1:6274?MCP_INSPECTOR_API_TOKEN=...` e abre o navegador. A URL já
traz o token: use a que o seu terminal mostrar. Na tela, escolha o servidor **`odonto-dev`** (no Windows, **`odonto-dev-windows`**),
conecte, abra a aba **Tools** e chame, por exemplo, `listarDentistas` ou `consultarBaseClinica` (as abas **Prompts** e
**Resources** mostram os roteiros e os resources). O arquivo
[`inspector/mcp-inspector.json`](inspector/mcp-inspector.json) define esses servidores (`mvn -q spring-boot:run`); o modo web lista
todos os servidores do arquivo.

### Linha de comando (para conferir algo rápido ou automatizar)

O `--` separa as opções do Inspector do comando que sobe o servidor:

```bash
# listar as ferramentas
npx @modelcontextprotocol/inspector --cli --method tools/list -- mvn -q spring-boot:run

# chamar uma ferramenta
npx @modelcontextprotocol/inspector --cli --method tools/call --tool-name dataDeHoje -- mvn -q spring-boot:run

# chamar com argumentos (--tool-arg chave=valor)
npx @modelcontextprotocol/inspector --cli --method tools/call --tool-name buscarPaciente --tool-arg "nome=Maria" -- mvn -q spring-boot:run

# prompts: listar e obter um (--prompt-args chave=valor)
npx @modelcontextprotocol/inspector --cli --method prompts/list -- mvn -q spring-boot:run
npx @modelcontextprotocol/inspector --cli --method prompts/get --prompt-name resumo-do-paciente --prompt-args pacienteId=104 -- mvn -q spring-boot:run

# resources: listar, listar os modelos de endereço e ler um
npx @modelcontextprotocol/inspector --cli --method resources/list -- mvn -q spring-boot:run
npx @modelcontextprotocol/inspector --cli --method resources/templates/list -- mvn -q spring-boot:run
npx @modelcontextprotocol/inspector --cli --method resources/read --uri clinica://regras-de-agendamento -- mvn -q spring-boot:run
```

Cada comando inicia e encerra o servidor. Uma chamada a `consultarBaseClinica` feita logo ao conectar pode responder
"ainda esta sendo indexada": a indexação dos PDFs acontece na partida; repita a chamada.

### Windows

O Maven é um `.cmd`: use `mvn.cmd` no lugar de `mvn` (já é o que a entrada `odonto-dev-windows` faz) ou `mvnw.cmd`.
Não testei no Windows: se o Inspector não achar o comando, confira se `mvn.cmd -v` funciona no mesmo terminal.

## Testes

```bash
mvn test
```

| Teste | O que garante | Precisa de Ollama? |
|---|---|---|
| `ContratoMcpTest` | O que o Claude enxerga do servidor (nome, instruções, ferramentas, prompts, resources, parâmetros, formato das respostas) não muda sem aviso, e os textos não citam ferramenta que não existe mais | não |
| `PromptsClinicosTest`, `RecursosClinicosTest` | As regras que os roteiros carregam (alarme antes de agendar, confirmar só depois do "sim"), a validação dos argumentos e os endereços dos resources | não |
| `AvaliacaoRagMecanicaTest` | O conjunto de perguntas é coerente com os PDFs, a regra de escopo entre pacientes não vaza e as métricas estão certas | não |
| `AvaliacaoRagOllamaTest` | Mede a qualidade da recuperação **com o modelo real** | sim (senão é pulado) |
| os demais | Regras da agenda, ingestão de PDFs, filtro por paciente, transporte stdio | não |

### Teste de contrato

`ContratoMcpTest` sobe o contexto Spring real, com o transporte stdio em memória (o stdio de verdade pertence ao Surefire),
pede `tools/list`, `prompts/list` e `resources/list` como o Claude faria e compara com os retratos versionados em
`src/test/resources/contrato/`: `ferramentas-mcp.json` (nomes, parâmetros, tipos e o que é obrigatório),
`prompts-mcp.json` (nomes e argumentos, com o que é obrigatório) e `recursos-mcp.json` (endereços, nomes e tipos de conteúdo).
Os **textos** das descrições ficam de fora dos retratos (ajustar prompt não é quebra de contrato); eles têm regras próprias
(toda ferramenta, prompt, resource e parâmetro precisa de descrição, e as descrições-chave precisam manter seus avisos).

Dois outros cuidados: os roteiros dos prompts, as instruções do servidor e o resource de regras **só podem citar
ferramentas e parâmetros que o servidor publica** (renomear uma ferramenta sem atualizar o roteiro deixaria o modelo seguindo
um passo-a-passo obsoleto), e os PDFs usados na verificação são sintéticos, com nomes que têm espaço, `%` e `+` de propósito,
para provar que o nome do arquivo sobrevive ao caminho até virar endereço de resource.

Quando ele falha, a mensagem diz o que mudou (item removido, novo, ou alterado). Se a mudança é
intencional, regrave os retratos e revise o diff no git:

```bash
mvn test -Dtest=ContratoMcpTest -Dcontrato.atualizar=true
```

### Avaliação do RAG

As perguntas ficam em `src/test/resources/avaliacao-rag/perguntas.json` (20 perguntas + 2 verificações de escopo) e usam os
dois PDFs de `src/test/resources/avaliacao-rag/docs/` (conteúdo fictício). Cada pergunta **positiva** aponta uma frase curta
que existe no PDF; a avaliação confere se um trecho com aquela frase voltou e em que posição.

```bash
ollama pull bge-m3                                   # uma vez; o Ollama precisa estar em execução
mvn test -Dtest=AvaliacaoRagOllamaTest
```

O relatório vai para o console e para `target/avaliacao-rag/relatorio-<modelo>.md`:

- **Recall@k**: fração das perguntas cujo trecho certo apareceu entre os `k` primeiros (o servidor usa `k = 4` por padrão).
- **MRR**: 1,0 = o trecho certo é sempre o primeiro; 0 = nunca aparece.
- **Limiar sugerido**: compara a similaridade dos trechos corretos com a do melhor trecho das perguntas **sem resposta** na base e
  diz se existe um `clinica.rag.similarity-threshold` que as separa. Só recomenda um valor quando há separação limpa.
- **Escopo**: nenhum histórico de paciente pode aparecer sem o `pacienteId` correspondente. Qualquer vazamento falha o teste.

O teste falha se houver vazamento de escopo ou se o Recall@4 ficar abaixo de `0.70`. Esse piso é um ponto de partida:
ajuste com `-Drag.eval.recall.minimo=0.85` depois de ver os números reais. A URL e o modelo do Ollama vêm do
`application.properties` (podem ser trocados com `-Dspring.ai.ollama.base-url=...` e `-Dspring.ai.ollama.embedding.options.model=...`).

**Para acrescentar uma pergunta**, edite o `perguntas.json`: `tipo` `POSITIVA` (com `evidencias`: `fonte` + `trecho`), `NEGATIVA`
(sem resposta na base) ou `ESCOPO` (com `fontesProibidas`). Escolha como `trecho` uma frase curta e distintiva do PDF e **evite
palavras com hífen** (um PDF pode quebrar a linha no hífen e o texto extraído vira `anti- hipertensivos`). O
`AvaliacaoRagMecanicaTest` confere que cada frase existe no PDF e cabe inteira em um trecho indexado.

**Ao trocar os PDFs de avaliação**, gere-os sem ligaduras tipográficas (fontes como Calibri/Carlito, no LibreOffice, fazem o
leitor de PDF do Spring AI perder letras dos títulos: "Periodontite" vira "periodontte"). O mesmo vale para os seus PDFs
reais: confira o texto extraído antes de confiar na busca.
