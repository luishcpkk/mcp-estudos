# PDFs da base clínica

Coloque aqui os PDFs (protocolos clínicos, históricos...) que o servidor indexa na inicialização.
Reinicie o servidor depois de adicionar ou alterar arquivos: o índice fica em memória.

- Só PDFs com **texto selecionável** são indexados; PDFs escaneados (apenas imagem) são ignorados.
- Os `*.pdf` desta pasta **não são versionados** (veja o `.gitignore`), para que prontuários de
  pacientes não vão parar no GitHub. Para versionar um protocolo público: `git add -f`.
- Para usar uma pasta fora do projeto, defina `clinica.rag.docs-location`, por exemplo
  `file:${user.home}/odonto-docs/*.pdf`.
