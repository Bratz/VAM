package com.bank.vam.fx.deal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A PDF of monospaced text, written by hand: a trade confirmation is a page of plain lines and a report is a text
 * table, which does not justify a PDF library. A form feed ('\f') starts a new page, as does running out of lines.
 * Characters outside Latin-1 become '?', and long lines wrap. Reports use landscape for their wider tables.
 */
final class ConfirmationPdf {

    private ConfirmationPdf() {
    }

    /** Portrait, 9 pt: a confirmation. */
    static byte[] render(String text) {
        return render(text, false);
    }

    static byte[] render(String text, boolean landscape) {
        int width = landscape ? 842 : 595;
        int height = landscape ? 595 : 842;
        int size = landscape ? 8 : 9;
        int leading = size + 3;
        // Portrait keeps the confirmation's long-standing 88 by 68; landscape fits the page at 8 pt.
        int wrap = landscape ? (int) ((width - 100) / (size * 0.6)) : 88;
        int perPage = landscape ? (height - 100) / leading : 68;
        List<List<String>> pages = paginate(text, wrap, perPage);

        List<byte[]> objects = new ArrayList<>();
        objects.add(latin1("<< /Type /Catalog /Pages 2 0 R >>"));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            kids.append(4 + 2 * i).append(" 0 R ");
        }
        objects.add(latin1("<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pages.size() + " >>"));
        objects.add(latin1("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>"));
        for (int i = 0; i < pages.size(); i++) {
            StringBuilder content = new StringBuilder("BT\n/F1 " + size + " Tf\n" + leading + " TL\n50 " + (height - 52) + " Td\n");
            for (String line : pages.get(i)) {
                content.append('(').append(escape(line)).append(") Tj\nT*\n");
            }
            content.append("ET");
            byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);
            objects.add(latin1("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height + "] /Contents "
                    + (5 + 2 * i) + " 0 R /Resources << /Font << /F1 3 0 R >> >> >>"));
            objects.add(concat(latin1("<< /Length " + stream.length + " >>\nstream\n"), stream, latin1("\nendstream")));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n");
            out.writeBytes(objects.get(i));
            write(out, "\nendobj\n");
        }
        int xref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            write(out, String.format("%010d 00000 n \n", offset));
        }
        write(out, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static List<List<String>> paginate(String text, int wrap, int perPage) {
        List<List<String>> pages = new ArrayList<>();
        for (String page : text.split("\f", -1)) {
            List<String> lines = new ArrayList<>();
            for (String raw : page.split("\n", -1)) {
                String line = raw;
                while (line.length() > wrap) {
                    lines.add(line.substring(0, wrap));
                    line = "  " + line.substring(wrap);
                }
                lines.add(line);
            }
            for (int i = 0; i < lines.size(); i += perPage) {
                pages.add(lines.subList(i, Math.min(lines.size(), i + perPage)));
            }
        }
        return pages.isEmpty() ? List.of(List.of("")) : pages;
    }

    private static String escape(String line) {
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == '(' || c == ')' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 32 || c > 255) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
