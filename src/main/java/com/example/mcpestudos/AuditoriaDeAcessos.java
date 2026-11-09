package com.example.mcpestudos;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Registro de quem olhou o historico de um paciente (tabela {@code acessos_ao_historico}).
 *
 * <p><b>Falha fechada.</b> Quem devolve dado de historico precisa registrar o acesso ANTES e so devolver se
 * {@link #registrar} der {@code true}. Se o registro falhar, o dado nao sai: nao existe acesso ao historico sem rastro.
 * O contrario (devolver mesmo sem conseguir registrar) deixaria justamente os acessos que mais interessam, os feitos
 * quando algo esta errado, sem prova.
 *
 * <p><b>So o fato, nunca o conteudo.</b> O registro guarda paciente, ferramenta, instante e quantos itens voltaram.
 * Nao guarda a pergunta (pode conter dado de saude) nem o texto devolvido.
 *
 * <p><b>Quem acessou</b> nao e registrado: este servidor roda no computador de um unico usuario, sem login, entao nao
 * ha identidade para gravar. Num servico com varios usuarios, a identidade viria da autenticacao do transporte.
 */
@Component
public class AuditoriaDeAcessos {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaDeAcessos.class);

    /** Mensagem para o usuario quando o acesso nao pode ser registrado (e por isso o historico nao foi devolvido). */
    static final String FALHA_NO_REGISTRO = "Nao foi possivel registrar o acesso ao historico do paciente e, por "
            + "seguranca, o historico nao foi devolvido. Tente de novo; se persistir, avise o responsavel pelo sistema.";

    /**
     * Sempre com segundos ({@code 10:30:00-03:00}). O {@code toString()} do {@code OffsetDateTime} omite os segundos
     * quando sao zero ({@code 10:30-03:00}), e o registro ficaria com dois formatos.
     */
    private static final DateTimeFormatter INSTANTE = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditoriaDeAcessos(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Registra um acesso ao historico do paciente.
     *
     * @param trechos quantos itens do historico vao ser devolvidos (0 se a busca nao achou nada)
     * @return {@code true} se o acesso ficou registrado; {@code false} se nao foi possivel (o chamador NAO deve
     *         devolver o historico). Nunca lanca: o motivo vai para o log, sem dado do paciente.
     */
    public boolean registrar(long pacienteId, String ferramenta, int trechos) {
        try {
            jdbc.sql("INSERT INTO acessos_ao_historico (paciente_id, ferramenta, acessado_em, trechos) "
                            + "VALUES (:p, :f, :em, :t)")
                    .param("p", pacienteId)
                    .param("f", ferramenta)
                    .param("em", INSTANTE.format(OffsetDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)))
                    .param("t", trechos)
                    .update();
            return true;
        } catch (RuntimeException e) {
            log.error("Nao foi possivel registrar o acesso ao historico do paciente {} por {}: {}", pacienteId,
                    ferramenta, e.toString());
            return false;
        }
    }

    /** Os acessos mais recentes ao historico do paciente, do mais novo para o mais antigo. */
    public List<AcessoAoHistorico> ultimos(long pacienteId, int limite) {
        return jdbc.sql("SELECT id, paciente_id, ferramenta, acessado_em, trechos FROM acessos_ao_historico "
                        + "WHERE paciente_id = :p ORDER BY id DESC LIMIT :limite")
                .param("p", pacienteId)
                .param("limite", limite)
                .query((rs, n) -> new AcessoAoHistorico(rs.getLong("id"), rs.getLong("paciente_id"),
                        rs.getString("ferramenta"), rs.getString("acessado_em"), rs.getInt("trechos")))
                .list();
    }

    /** Quantos acessos ao historico do paciente existem no total. */
    public int total(long pacienteId) {
        return jdbc.sql("SELECT COUNT(*) FROM acessos_ao_historico WHERE paciente_id = :p")
                .param("p", pacienteId).query(Integer.class).single();
    }
}
