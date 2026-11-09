package com.example.mcpestudos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Function;

import com.example.mcpestudos.PerguntaRag.EvidenciaEsperada;
import com.example.mcpestudos.PerguntaRag.Tipo;

/**
 * Mede a qualidade da recuperacao do RAG: para cada pergunta do conjunto de avaliacao, roda a ferramenta
 * {@code consultarBaseClinica} e confere SE os trechos certos voltaram e EM QUE POSICAO.
 *
 * <p>Nao conhece modelo nem banco: recebe uma funcao que consulta. Assim a mesma logica serve para o teste
 * mecanico (embedding falso, sempre roda) e para a avaliacao com o modelo real (Ollama).
 *
 * <p>Metricas (sobre as perguntas {@link Tipo#POSITIVA}):
 * <ul>
 *   <li><b>Recall@k por pergunta</b>: fracao das perguntas cujas evidencias TODAS apareceram entre os k primeiros trechos.</li>
 *   <li><b>Recall@k por evidencia</b>: o mesmo, contando cada evidencia separadamente.</li>
 *   <li><b>MRR</b>: media de 1/posicao da evidencia (1,0 = sempre em primeiro; 0 = nunca apareceu).</li>
 * </ul>
 * As perguntas {@link Tipo#NEGATIVA} (sem resposta na base) servem para sugerir o limiar de similaridade.
 */
final class AvaliadorRag {

    /** O {@code clinica.rag.top-k} padrao do servidor: e com ele que o usuario real e atendido. */
    static final int K_PADRAO_DO_SERVIDOR = 4;

    private AvaliadorRag() {
    }

    // ---------- resultados ----------

    record ResultadoEvidencia(EvidenciaEsperada evidencia, Integer posicao, Double similaridade) {
    }

    record ResultadoPergunta(PerguntaRag pergunta, String erro, List<TrechoClinico> trechos,
                             List<ResultadoEvidencia> evidencias, List<String> violacoesDeEscopo) {

        boolean consultaOk() {
            return erro == null;
        }

        boolean recuperouTudoAte(int k) {
            return !evidencias.isEmpty()
                    && evidencias.stream().allMatch(e -> e.posicao() != null && e.posicao() <= k);
        }

        boolean recuperouTudo() {
            return !evidencias.isEmpty() && evidencias.stream().allMatch(e -> e.posicao() != null);
        }

        /** A MENOR similaridade entre as evidencias encontradas (o elo mais fraco da resposta). */
        Double similaridadeDaEvidenciaMaisFraca() {
            return evidencias.stream().filter(e -> e.similaridade() != null)
                    .map(ResultadoEvidencia::similaridade).min(Comparator.naturalOrder()).orElse(null);
        }

        double similaridadeDoTopo() {
            return trechos.isEmpty() ? 0.0 : trechos.get(0).similaridade();
        }
    }

    /** Sugestao de {@code clinica.rag.similarity-threshold}, calculada a partir dos dados medidos. */
    record Limiar(Double menorPositivo, Double maiorNegativo, boolean separavel, Double sugerido, String explicacao) {
    }

    record Relatorio(String rotulo, int topK, List<ResultadoPergunta> resultados) {

        List<ResultadoPergunta> positivas() {
            return resultados.stream().filter(r -> r.pergunta().tipo() == Tipo.POSITIVA).toList();
        }

        private List<ResultadoPergunta> doTipo(Tipo tipo) {
            return resultados.stream().filter(r -> r.pergunta().tipo() == tipo).toList();
        }

        double recallPorPergunta(int k) {
            List<ResultadoPergunta> positivas = positivas();
            return positivas.isEmpty() ? 0.0
                    : (double) positivas.stream().filter(r -> r.recuperouTudoAte(k)).count() / positivas.size();
        }

        double recallPorEvidencia(int k) {
            List<ResultadoEvidencia> todas = positivas().stream().flatMap(r -> r.evidencias().stream()).toList();
            return todas.isEmpty() ? 0.0
                    : (double) todas.stream().filter(e -> e.posicao() != null && e.posicao() <= k).count() / todas.size();
        }

        double mrr() {
            List<ResultadoEvidencia> todas = positivas().stream().flatMap(r -> r.evidencias().stream()).toList();
            return todas.isEmpty() ? 0.0
                    : todas.stream().mapToDouble(e -> e.posicao() == null ? 0.0 : 1.0 / e.posicao()).sum() / todas.size();
        }

        /** Violacoes de privacidade em QUALQUER pergunta. Deve ser sempre vazia, com qualquer modelo. */
        List<String> violacoesDeEscopo() {
            List<String> todas = new ArrayList<>();
            resultados.forEach(r -> r.violacoesDeEscopo().forEach(v -> todas.add(r.pergunta().id() + ": " + v)));
            return todas;
        }

        Limiar sugerirLimiar() {
            List<Double> positivos = positivas().stream().filter(ResultadoPergunta::recuperouTudo)
                    .map(ResultadoPergunta::similaridadeDaEvidenciaMaisFraca).toList();
            List<Double> negativos = doTipo(Tipo.NEGATIVA).stream().filter(ResultadoPergunta::consultaOk)
                    .map(ResultadoPergunta::similaridadeDoTopo).toList();
            if (positivos.isEmpty() || negativos.isEmpty()) {
                return new Limiar(null, null, false, null,
                        "Sem dados suficientes (e preciso ao menos uma pergunta positiva encontrada e uma negativa).");
            }
            double menorPositivo = positivos.stream().min(Comparator.naturalOrder()).orElseThrow();
            double maiorNegativo = negativos.stream().max(Comparator.naturalOrder()).orElseThrow();
            if (maiorNegativo < menorPositivo) {
                double sugerido = arredondar((maiorNegativo + menorPositivo) / 2);
                return new Limiar(menorPositivo, maiorNegativo, true, sugerido, String.format(Locale.ROOT,
                        "Separacao limpa: todo trecho correto tem similaridade >= %.3f e o melhor trecho de uma pergunta "
                                + "sem resposta chega a %.3f. Um limiar de %.2f aceita todos os corretos e recusa as perguntas sem resposta.",
                        menorPositivo, maiorNegativo, sugerido));
            }
            long cortados = positivos.stream().filter(p -> p <= maiorNegativo).count();
            double sugerido = arredondar(maiorNegativo + 0.01);
            return new Limiar(menorPositivo, maiorNegativo, false, sugerido, String.format(Locale.ROOT,
                    "SEM separacao limpa: o menor trecho correto (%.3f) esta abaixo do maior trecho de pergunta sem resposta "
                            + "(%.3f). Para recusar todas as perguntas sem resposta seria preciso um limiar de %.2f, que cortaria "
                            + "%d de %d perguntas que TEM resposta. Melhore a recuperacao (modelo, chunking, busca hibrida) antes de usar limiar.",
                    menorPositivo, maiorNegativo, sugerido, cortados, positivos.size()));
        }

        String paraMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# Avaliacao do RAG: ").append(rotulo).append("\n\n");
            md.append("Gerado em ").append(Instant.now()).append(". top-k usado na medicao: ").append(topK)
                    .append(" (padrao do servidor: ").append(K_PADRAO_DO_SERVIDOR).append(").\n\n");

            md.append("## Resumo\n\n");
            md.append("- Perguntas com resposta na base: ").append(positivas().size())
                    .append(" | sem resposta: ").append(doTipo(Tipo.NEGATIVA).size())
                    .append(" | verificacoes de escopo: ").append(doTipo(Tipo.ESCOPO).size()).append("\n");
            for (int k : ks()) {
                md.append(String.format(Locale.ROOT, "- Recall@%d: %.0f%% das perguntas (%.0f%% das evidencias)%s%n", k,
                        100 * recallPorPergunta(k), 100 * recallPorEvidencia(k),
                        k == K_PADRAO_DO_SERVIDOR ? "   <- padrao do servidor" : ""));
            }
            md.append(String.format(Locale.ROOT, "- MRR das evidencias: %.3f (1.0 = sempre em primeiro lugar)%n", mrr()));
            List<String> violacoes = violacoesDeEscopo();
            md.append("- Violacoes de escopo (privacidade): ").append(violacoes.isEmpty() ? "0" : violacoes.toString()).append("\n\n");

            md.append("## Limiar de similaridade sugerido\n\n");
            Limiar limiar = sugerirLimiar();
            md.append(limiar.explicacao()).append("\n");
            if (limiar.separavel() && limiar.sugerido() != null) {
                md.append("\nPara aplicar: `clinica.rag.similarity-threshold=").append(String.format(Locale.ROOT, "%.2f", limiar.sugerido()))
                        .append("` (confirme com mais perguntas antes de adotar).\n");
            }

            md.append("\n## Resultado por pergunta\n\n");
            md.append("| id | tipo | pergunta | resultado | posicoes | similaridade |\n|---|---|---|---|---|---|\n");
            for (ResultadoPergunta r : resultados) {
                md.append("| ").append(r.pergunta().id()).append(" | ").append(r.pergunta().tipo()).append(" | ")
                        .append(resumir(r.pergunta().pergunta(), 60)).append(" | ").append(veredito(r)).append(" | ")
                        .append(posicoes(r)).append(" | ").append(similaridade(r)).append(" |\n");
            }

            List<ResultadoPergunta> falhas = positivas().stream().filter(r -> !r.recuperouTudoAte(K_PADRAO_DO_SERVIDOR)).toList();
            if (!falhas.isEmpty()) {
                md.append("\n## Detalhe das perguntas que nao recuperaram tudo em ").append(K_PADRAO_DO_SERVIDOR).append(" trechos\n");
                for (ResultadoPergunta r : falhas) {
                    md.append("\n### ").append(r.pergunta().id()).append(": ").append(r.pergunta().pergunta()).append("\n\n");
                    if (!r.consultaOk()) {
                        md.append("A consulta foi recusada: ").append(r.erro()).append("\n");
                        continue;
                    }
                    r.evidencias().stream().filter(e -> e.posicao() == null || e.posicao() > K_PADRAO_DO_SERVIDOR).forEach(e ->
                            md.append("- Faltou: \"").append(e.evidencia().trecho()).append("\" em ").append(e.evidencia().fonte())
                                    .append(e.posicao() == null ? " (nao apareceu)" : " (apareceu so em " + e.posicao() + "o)").append("\n"));
                    md.append("- O que voltou no topo:\n");
                    for (int i = 0; i < Math.min(3, r.trechos().size()); i++) {
                        TrechoClinico t = r.trechos().get(i);
                        md.append(String.format(Locale.ROOT, "  %d. %s p.%s (%.3f): %s%n", i + 1, t.fonte(), t.pagina(),
                                t.similaridade(), resumir(t.texto().replaceAll("\\s+", " "), 110)));
                    }
                }
            }
            return md.toString();
        }

        private List<Integer> ks() {
            TreeSet<Integer> ks = new TreeSet<>(List.of(1, 3, K_PADRAO_DO_SERVIDOR));
            ks.add(topK);
            return List.copyOf(ks);
        }

        private static String veredito(ResultadoPergunta r) {
            return switch (r.pergunta().tipo()) {
                case POSITIVA -> !r.consultaOk() ? "[X] recusada"
                        : r.recuperouTudoAte(K_PADRAO_DO_SERVIDOR) ? "[OK]"
                        : r.recuperouTudo() ? "[!] so depois do " + K_PADRAO_DO_SERVIDOR + "o" : "[X] nao achou";
                case NEGATIVA -> "(sem resposta na base)";
                case ESCOPO -> r.violacoesDeEscopo().isEmpty() ? "[OK] sem vazamento" : "[X] VAZOU";
            };
        }

        private static String posicoes(ResultadoPergunta r) {
            if (r.pergunta().tipo() != Tipo.POSITIVA) {
                return "-";
            }
            return r.evidencias().stream().map(e -> e.posicao() == null ? "-" : String.valueOf(e.posicao()))
                    .reduce((a, b) -> a + ", " + b).orElse("-");
        }

        private static String similaridade(ResultadoPergunta r) {
            Double valor = switch (r.pergunta().tipo()) {
                case POSITIVA -> r.similaridadeDaEvidenciaMaisFraca();
                case NEGATIVA -> r.consultaOk() ? r.similaridadeDoTopo() : null;
                case ESCOPO -> null;
            };
            return valor == null ? "-" : String.format(Locale.ROOT, "%.3f", valor);
        }
    }

    // ---------- execucao ----------

    /**
     * @param rotulo   nome da execucao (aparece no relatorio), por exemplo o modelo de embeddings
     * @param topK     quantos trechos a consulta devolve; quanto maior, mais posicoes se consegue medir
     * @param consulta como consultar: normalmente {@code p -> tools.consultarBaseClinica(p.pergunta(), p.pacienteId())}
     */
    static Relatorio avaliar(String rotulo, int topK, List<PerguntaRag> perguntas,
                             Function<PerguntaRag, Resultado<ConsultaClinica>> consulta) {
        List<ResultadoPergunta> resultados = new ArrayList<>();
        for (PerguntaRag pergunta : perguntas) {
            resultados.add(avaliarPergunta(pergunta, consulta.apply(pergunta)));
        }
        return new Relatorio(rotulo, topK, resultados);
    }

    private static ResultadoPergunta avaliarPergunta(PerguntaRag pergunta, Resultado<ConsultaClinica> resposta) {
        if (!resposta.sucesso()) {
            List<ResultadoEvidencia> naoAchadas = pergunta.evidencias().stream()
                    .map(e -> new ResultadoEvidencia(e, null, null)).toList();
            return new ResultadoPergunta(pergunta, resposta.erro(), List.of(), naoAchadas, List.of());
        }
        List<TrechoClinico> trechos = resposta.dados().trechos();
        List<ResultadoEvidencia> evidencias = new ArrayList<>();
        for (EvidenciaEsperada esperada : pergunta.evidencias()) {
            Integer posicao = null;
            Double similaridade = null;
            String procurado = normalizar(esperada.trecho());
            for (int i = 0; i < trechos.size(); i++) {
                TrechoClinico t = trechos.get(i);
                if (t.fonte().equals(esperada.fonte()) && normalizar(t.texto()).contains(procurado)) {
                    posicao = i + 1;
                    similaridade = t.similaridade();
                    break;
                }
            }
            evidencias.add(new ResultadoEvidencia(esperada, posicao, similaridade));
        }
        return new ResultadoPergunta(pergunta, null, trechos, evidencias, violacoesDeEscopo(pergunta, trechos));
    }

    /** Regras de privacidade verificadas em TODA pergunta, nao so nas do tipo ESCOPO. */
    private static List<String> violacoesDeEscopo(PerguntaRag pergunta, List<TrechoClinico> trechos) {
        List<String> violacoes = new ArrayList<>();
        for (TrechoClinico t : trechos) {
            if (pergunta.fontesProibidas().contains(t.fonte())) {
                violacoes.add("devolveu o arquivo proibido " + t.fonte());
            }
            if ("PACIENTE".equals(t.escopo())) {
                if (pergunta.pacienteId() == null) {
                    violacoes.add("devolveu historico de paciente (" + t.fonte() + ") sem pacienteId");
                } else if (!pergunta.pacienteId().equals(t.pacienteId())) {
                    violacoes.add("devolveu historico do paciente " + t.pacienteId() + " na consulta do paciente "
                            + pergunta.pacienteId());
                }
            }
        }
        return violacoes;
    }

    // ---------- utilidades ----------

    /** Minusculas, sem acentos e com espacos/quebras de linha colapsados: compara texto de PDF com frase esperada. */
    static String normalizar(String texto) {
        return Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    static void gravar(Relatorio relatorio, Path arquivo) {
        try {
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, relatorio.paraMarkdown(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Nao foi possivel gravar o relatorio em " + arquivo, e);
        }
    }

    private static double arredondar(double valor) {
        return Math.round(valor * 100) / 100.0;
    }

    private static String resumir(String texto, int maximo) {
        String linha = texto.replace("|", "/");
        return linha.length() <= maximo ? linha : linha.substring(0, maximo - 3) + "...";
    }
}
