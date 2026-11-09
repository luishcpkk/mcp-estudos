package com.example.mcpestudos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Uma entrada do conjunto de avaliacao do RAG ({@code avaliacao-rag/perguntas.json}).
 *
 * @param id             identificador curto (P = protocolo, C = paciente, X = cruzada, N = negativa, S = escopo)
 * @param tipo           o que a pergunta mede, veja {@link Tipo}
 * @param pergunta       o texto enviado a {@code consultarBaseClinica}
 * @param pacienteId     paciente informado na chamada; {@code null} = so protocolos
 * @param evidencias     trechos que DEVEM voltar (so em {@link Tipo#POSITIVA})
 * @param fontesProibidas arquivos que NAO podem aparecer (so em {@link Tipo#ESCOPO})
 */
record PerguntaRag(String id, Tipo tipo, String pergunta, Long pacienteId,
                   List<EvidenciaEsperada> evidencias, List<String> fontesProibidas) {

    /**
     * <ul>
     *   <li>{@code POSITIVA}: a base tem a resposta; mede se os trechos certos voltam e em que posicao.</li>
     *   <li>{@code NEGATIVA}: a base NAO tem a resposta; mede a similaridade do melhor trecho, para calibrar o limiar.</li>
     *   <li>{@code ESCOPO}: regra de privacidade; um arquivo proibido nunca pode voltar (falha dura, independe do modelo).</li>
     * </ul>
     */
    enum Tipo {
        POSITIVA, NEGATIVA, ESCOPO
    }

    /**
     * Um trecho que a resposta precisa conter: {@code trecho} e uma frase curta e distintiva que existe no PDF
     * {@code fonte}. A comparacao ignora maiusculas, acentos e quebras de linha.
     */
    record EvidenciaEsperada(String fonte, String trecho) {
    }

    PerguntaRag {
        evidencias = evidencias == null ? List.of() : List.copyOf(evidencias);
        fontesProibidas = fontesProibidas == null ? List.of() : List.copyOf(fontesProibidas);
    }

    static List<PerguntaRag> carregar() {
        try (InputStream in = PerguntaRag.class.getResourceAsStream("/avaliacao-rag/perguntas.json")) {
            if (in == null) {
                throw new IllegalStateException("avaliacao-rag/perguntas.json nao encontrado no classpath de teste");
            }
            return JsonMapper.builder().build().readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<List<PerguntaRag>>() {
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Nao foi possivel ler avaliacao-rag/perguntas.json", e);
        }
    }
}
