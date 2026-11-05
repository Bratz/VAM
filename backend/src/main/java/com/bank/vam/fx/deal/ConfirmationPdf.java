package com.bank.vam.fx.deal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A one-page PDF of monospaced text, written by hand: a trade confirmation is a page of plain lines,
 * which does not justify a PDF library. Characters outside Latin-1 become '?', and long lines wrap.
 */
final class ConfirmationPdf {

    private static final int WRAP = 88;
    private static final int MAX_LINES = 68;

    private ConfirmationPdf() {
    }

    static byte[] render(String text) {
        List<String> lines = wrap(text);

        StringBuilder content = new StringBuilder("BT\n/F1 9 Tf\n12 TL\n50 790 Td\n");
        for (String line : lines) {
            content.append('(').append(escape(line)).append(") Tj\nT*\n");
        }
        content.append("ET");
        byte[] stream = content.toString().getBytes(StandardCharsets.ISO_8859_1);

        List<byte[]> objects = new ArrayList<>();
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
        objects.add("<< /Type /Pages /Kids [3 0 R] /Count 1 >>".getBytes(StandardCharsets.ISO_8859_1));
        objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
                + "/Resources << /Font << /F1 5 0 R >> >> >>").getBytes(StandardCharsets.ISO_8859_1));
        objects.add(concat(("<< /Length " + stream.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1),
                stream, "\nendstream".getBytes(StandardCharsets.ISO_8859_1)));
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>".getBytes(StandardCharsets.ISO_8859_1));

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

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String line = raw;
            while (line.length() > WRAP) {
                lines.add(line.substring(0, WRAP));
                line = "  " + line.substring(WRAP);
            }
            lines.add(line);
        }
        return lines.size() > MAX_LINES ? lines.subList(0, MAX_LINES) : lines;
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
