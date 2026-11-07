package com.example.mcpestudos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class RagConfigTest {

    @Configuration
    static class EmbeddingDeTeste {
        @Bean
        EmbeddingModel embeddingModel() {
            return new EmbeddingFalso();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EmbeddingDeTeste.class, RagConfig.class);

    @Test
    void criaOVectorStoreEmMemoriaEAplicaOsPadroes() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(VectorStore.class)).isInstanceOf(SimpleVectorStore.class);
            RagProperties props = ctx.getBean(RagProperties.class);
            assertThat(props.docsLocation()).isEqualTo("classpath:docs/*.pdf");
            assertThat(props.chunkSize()).isEqualTo(500);
            assertThat(props.topK()).isEqualTo(4);
            assertThat(props.similarityThreshold()).isZero();
        });
    }

    @Test
    void propriedadesSaoConfiguraveis() {
        runner.withPropertyValues("clinica.rag.docs-location=file:/tmp/docs/*.pdf", "clinica.rag.top-k=7",
                "clinica.rag.chunk-size=300", "clinica.rag.similarity-threshold=0.6").run(ctx -> {
            RagProperties props = ctx.getBean(RagProperties.class);
            assertThat(props.docsLocation()).isEqualTo("file:/tmp/docs/*.pdf");
            assertThat(props.topK()).isEqualTo(7);
            assertThat(props.chunkSize()).isEqualTo(300);
            assertThat(props.similarityThreshold()).isEqualTo(0.6);
        });
    }

    @Test
    void valoresInvalidosImpedemAInicializacao() {
        runner.withPropertyValues("clinica.rag.top-k=0").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("clinica.rag.similarity-threshold=1.5").run(ctx -> assertThat(ctx).hasFailed());
    }
}
