package com.example.mcpestudos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuracao da base de conhecimento clinico (RAG), prefixo {@code clinica.rag}.
 *
 * @param docsLocation        onde ficam os PDFs: qualquer padrao de recurso do Spring, por exemplo
 *                            {@code classpath:docs/*.pdf} ou {@code file:/caminho/da/pasta/*.pdf}
 * @param chunkSize           tamanho alvo de cada trecho, em tokens (TokenTextSplitter)
 * @param topK                quantos trechos a busca por similaridade devolve, no maximo
 * @param similarityThreshold similaridade minima (0 a 1) para um trecho ser devolvido; 0 aceita todos
 */
@ConfigurationProperties("clinica.rag")
public record RagProperties(
        @DefaultValue("classpath:docs/*.pdf") String docsLocation,
        @DefaultValue("500") int chunkSize,
        @DefaultValue("4") int topK,
        @DefaultValue("0.0") double similarityThreshold) {

    public RagProperties {
        if (chunkSize < 1 || topK < 1 || similarityThreshold < 0 || similarityThreshold > 1) {
            throw new IllegalArgumentException("clinica.rag invalido: chunk-size e top-k devem ser >= 1 "
                    + "e similarity-threshold deve estar entre 0 e 1");
        }
    }
}
