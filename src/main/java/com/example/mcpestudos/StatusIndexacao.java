package com.example.mcpestudos;

/**
 * Situacao da indexacao dos PDFs clinicos.
 *
 * @param estado                  ponto atual do processo
 * @param arquivos                PDFs classificados e processados sem erro (inclui os sem texto e os em quarentena)
 * @param arquivosComFalha        PDFs que falharam (corrompidos, ilegiveis ou erro de embeddings)
 * @param arquivosSemTexto        PDFs sem texto extraivel, tipicamente escaneados (so imagem); nao sao indexados
 * @param arquivosNaoClassificados PDFs cujo nome nao segue {@code protocolo-*} nem {@code paciente-<id>-*};
 *                                nao sao lidos nem indexados (negar por padrao: nao se sabe de quem sao)
 * @param arquivosEmQuarentena    PDFs lidos mas isolados, sem indexar nada deles, porque o texto parece conter
 *                                instrucoes dirigidas ao assistente (injecao de prompt); ver {@link DetectorDeInjecao}
 * @param trechos                 trechos gravados no VectorStore
 */
public record StatusIndexacao(Estado estado, int arquivos, int arquivosComFalha, int arquivosSemTexto,
                              int arquivosNaoClassificados, int arquivosEmQuarentena, int trechos) {

    public enum Estado {
        NAO_INICIADA, INDEXANDO, PRONTA, FALHOU
    }

    static StatusIndexacao naoIniciada() {
        return new StatusIndexacao(Estado.NAO_INICIADA, 0, 0, 0, 0, 0, 0);
    }
}
