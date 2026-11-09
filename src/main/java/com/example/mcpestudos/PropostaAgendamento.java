package com.example.mcpestudos;

/**
 * Resultado de {@code prepararAgendamento}: o que SERIA agendado e o codigo para confirmar. Nada foi gravado
 * na agenda ainda.
 *
 * @param codigoConfirmacao  codigo de uso unico que {@code confirmarAgendamento} exige
 * @param resumo             frase pronta para mostrar ao usuario
 * @param dentista           nome do dentista
 * @param paciente           nome do paciente
 * @param data               AAAA-MM-DD
 * @param diaDaSemana        por extenso, em portugues
 * @param hora               HH:mm
 * @param procedimento       procedimento informado
 * @param expiraEm           data e hora (fuso da clinica) em que o codigo deixa de valer
 * @param validadeEmMinutos  quanto tempo o codigo vale, a partir de agora
 * @param proximoPasso       o que fazer em seguida (instrucao para o modelo)
 */
public record PropostaAgendamento(String codigoConfirmacao, String resumo, String dentista, String paciente,
                                  String data, String diaDaSemana, String hora, String procedimento,
                                  String expiraEm, int validadeEmMinutos, String proximoPasso) {
}
