package com.custoking.ims.schoolcoreservice.persistence;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class FeeDocumentRenderer {

    public byte[] render(String content) {
        String safe = escapePdfText(content);
        String stream = "BT /F1 12 Tf 36 740 Td (" + safe + ") Tj ET\n";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>stream\n"
                        + stream + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>(objects.size());
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj").append(objects.get(i)).append("endobj\n");
        }
        int xrefOffset = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append('\n')
                .append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append(String.format(Locale.ENGLISH, "%010d 00000 n \n", offset));
        }
        pdf.append("trailer<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
                .append("startxref\n").append(xrefOffset).append("\n%%EOF");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private String escapePdfText(String content) {
        if (content == null || content.isBlank()) return "";
        StringBuilder escaped = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '(' || ch == ')' || ch == '\\') {
                escaped.append('\\').append(ch);
            } else if (ch >= 32 && ch <= 126) {
                escaped.append(ch);
            } else {
                escaped.append(' ');
            }
        }
        return escaped.toString();
    }
}
