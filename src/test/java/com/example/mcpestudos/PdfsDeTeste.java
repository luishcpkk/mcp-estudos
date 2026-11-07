package com.example.mcpestudos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Gera PDFs sinteticos para os testes. Todo o conteudo e ficticio: nao e orientacao clinica real. */
final class PdfsDeTeste {

    private PdfsDeTeste() {
    }

    /** Cria um PDF com uma pagina por texto informado. */
    static void criar(Path arquivo, String... paginas) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (String texto : paginas) {
                PDPage pagina = new PDPage();
                doc.addPage(pagina);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pagina)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    cs.setLeading(14);
                    cs.newLineAtOffset(50, 750);
                    for (String linha : quebrar(texto, 85)) {
                        cs.showText(linha);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(arquivo.toFile());
        }
    }

    /** Cria um PDF de uma pagina sem texto, como um documento escaneado. */
    static void criarEmBranco(Path arquivo) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(arquivo.toFile());
        }
    }

    private static List<String> quebrar(String texto, int largura) {
        List<String> linhas = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        for (String palavra : texto.split(" ")) {
            if (atual.length() + palavra.length() + 1 > largura) {
                linhas.add(atual.toString());
                atual.setLength(0);
            }
            if (atual.length() > 0) {
                atual.append(' ');
            }
            atual.append(palavra);
        }
        linhas.add(atual.toString());
        return linhas;
    }
}
