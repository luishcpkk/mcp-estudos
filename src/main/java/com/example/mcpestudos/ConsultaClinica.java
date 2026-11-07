package com.example.mcpestudos;

import java.util.List;

/** Resposta de {@code consultarBaseClinica}: os trechos encontrados e um aviso de uso. */
public record ConsultaClinica(String duvida, List<TrechoClinico> trechos, String aviso) {
}
