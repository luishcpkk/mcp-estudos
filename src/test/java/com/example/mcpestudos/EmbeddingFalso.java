package com.example.mcpestudos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * EmbeddingModel de teste: saco de palavras com hash, deterministico e sem rede. Textos com palavras em
 * comum ficam proximos. Serve para testar a fiacao do RAG (leitura, divisao, gravacao, busca), NAO a
 * qualidade semantica de um modelo real.
 */
class EmbeddingFalso implements EmbeddingModel {

    static final int DIMENSOES = 256;

    /** Quando {@code true}, simula o servico de embeddings fora do ar. */
    volatile boolean foraDoAr;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> saida = new ArrayList<>();
        int indice = 0;
        for (String texto : request.getInstructions()) {
            saida.add(new Embedding(vetor(texto), indice++));
        }
        return new EmbeddingResponse(saida);
    }

    @Override
    public float[] embed(Document documento) {
        return vetor(documento.getText());
    }

    @Override
    public int dimensions() {
        return DIMENSOES;
    }

    private float[] vetor(String texto) {
        if (foraDoAr) {
            throw new IllegalStateException("Connection refused: servico de embeddings fora do ar");
        }
        float[] v = new float[DIMENSOES];
        String limpo = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        for (String palavra : limpo.split("[^a-z0-9]+")) {
            if (palavra.length() >= 3) {
                v[Math.floorMod(palavra.hashCode(), DIMENSOES)] += 1f;
            }
        }
        double norma = 0;
        for (float x : v) {
            norma += x * x;
        }
        norma = Math.sqrt(norma);
        if (norma > 0) {
            for (int i = 0; i < v.length; i++) {
                v[i] /= (float) norma;
            }
        }
        return v;
    }
}
