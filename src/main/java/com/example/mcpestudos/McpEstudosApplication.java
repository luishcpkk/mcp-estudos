package com.example.mcpestudos;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpEstudosApplication {

    /** Fuso da clinica: todas as regras de "hoje" e "agora" partem deste relogio. */
    static final ZoneId FUSO_CLINICA = ZoneId.of("America/Sao_Paulo");

    public static void main(String[] args) {
        SpringApplication.run(McpEstudosApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.system(FUSO_CLINICA);
    }

    /** Expoe como ferramentas MCP os metodos @Tool de agendamento e de consulta a base clinica. */
    @Bean
    ToolCallbackProvider clinicaToolProvider(AgendamentoTools agendamento, ConsultorClinicoTools consultor) {
        return MethodToolCallbackProvider.builder().toolObjects(agendamento, consultor).build();
    }
}
