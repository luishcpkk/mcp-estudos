# PDFs da base clínica

Coloque aqui os PDFs que o servidor indexa na inicialização. Reinicie o servidor depois de adicionar ou
alterar arquivos: o índice fica em memória.

## O nome do arquivo define quem pode ver o conteúdo

| Nome do arquivo | Escopo | Quem enxerga |
|---|---|---|
| `protocolo-<nome>.pdf` | Orientação geral da clínica | Toda consulta à base |
| `paciente-<id>-<nome>.pdf` | Histórico do paciente `<id>` (o ID de `buscarPaciente`) | Só consultas feitas **informando esse `pacienteId`** |
| qualquer outro nome | — | **Ninguém: o arquivo não é indexado** (aparece um aviso no log) |

Exemplos: `protocolo-anestesia.pdf`, `paciente-12-historico.pdf`.

Sem o `pacienteId`, `consultarBaseClinica` só consulta protocolos. Com ele, consulta protocolos **e** os
documentos daquele paciente, nunca os de outro. Um arquivo com nome fora do padrão é ignorado de propósito:
se não se sabe de quem é um documento, o seguro é não oferecê-lo a nenhuma busca.

> Isto é *escopo*, não autorização: o servidor não sabe quem está do outro lado e quem escolhe o
> `pacienteId` é o modelo, a pedido do usuário. Evita o vazamento acidental entre pacientes numa pergunta
> genérica; não impede que alguém peça de propósito o histórico de outro paciente.

## Outras regras

- Só PDFs com **texto selecionável** são indexados; PDFs escaneados (apenas imagem) são ignorados.
- Os `*.pdf` desta pasta **não são versionados** (veja o `.gitignore`), para que prontuários não vão parar
  no GitHub. Para versionar um protocolo público: `git add -f`.
- Para usar uma pasta fora do projeto, defina `clinica.rag.docs-location`, por exemplo
  `file:${user.home}/odonto-docs/*.pdf`.
