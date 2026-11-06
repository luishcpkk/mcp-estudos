package com.example.mcpestudos;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class McpEstudosApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpEstudosApplication.class, args);
    }

    @Bean
    ToolCallbackProvider agendamentoToolProvider(AgendamentoTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}