package com.bank.vam.statement.parse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Flattens an :86: field into remittance text and references. Three shapes:
 * <ul>
 *   <li>German structured: {@code 166?00GUTSCHRIFT?20EREF+...?21SVWZ+...?32Name} — posting text ?00, purpose ?20–?29
 *       and ?60–?63 (SEPA keywords EREF+ KREF+ SVWZ+ ...), counterparty name ?32–?33;</li>
 *   <li>SWIFT-style codes: {@code /EREF/abc/REMI/USTD//text/ORDP//NAME/...};</li>
 *   <li>free text (EREF+ / KREF+ / SVWZ+ keywords still recognised).</li>
 * </ul>
 * Structured shapes join their lines with nothing (banks wrap them mid-word at fixed width); free text with a space.
 */
final class Mt86 {

    record Info(String remittance, String endToEndId, String customerReference) {
    }

    private static final Pattern GERMAN = Pattern.compile("^(\\d{3})?\\?\\d{2}");
    private static final Pattern SUBFIELD = Pattern.compile("\\?(\\d{2})");
    private static final Pattern KEYWORD = Pattern.compile("(EREF|KREF|MREF|CRED|DEBT|SVWZ|ABWA|ABWE|COAM|OAMT|BREF|RREF|PURP)\\+");
    private static final Pattern SWIFT_EREF = Pattern.compile("/EREF/([^/]+)");
    private static final Pattern SWIFT_REMI = Pattern.compile("/REMI/(?:USTD//)?(.*?)(?=/[A-Z]{4}/|$)");

    private Mt86() {
    }

    static Info parse(String raw) {
        String joined = raw.replace("\n", "");
        String posting = null, purpose, text, name = null;
        Map<String, String> kw;
        if (GERMAN.matcher(joined).lookingAt()) {
            Map<Integer, String> sub = new TreeMap<>();
            Matcher m = SUBFIELD.matcher(joined);
            int code = -1, from = 0;
            while (true) {
                boolean found = m.find();
                if (code >= 0) sub.merge(code, joined.substring(from, found ? m.start() : joined.length()), String::concat);
                if (!found) break;
                code = Integer.parseInt(m.group(1));
                from = m.end();
            }
            posting = sub.get(0);
            purpose = sub.entrySet().stream().filter(e -> e.getKey() >= 20 && e.getKey() <= 29 || e.getKey() >= 60 && e.getKey() <= 63)
                    .map(Map.Entry::getValue).collect(Collectors.joining());
            name = Stream.of(sub.get(32), sub.get(33)).filter(Objects::nonNull).collect(Collectors.joining());
            kw = keywords(purpose);
            text = kw.getOrDefault("SVWZ", purpose);
        } else {
            purpose = joined.startsWith("/") ? joined : raw.replace('\n', ' ');
            kw = keywords(purpose);
            Matcher remi = SWIFT_REMI.matcher(purpose);
            text = remi.find() ? remi.group(1) : purpose;
        }
        String e2e = kw.get("EREF");
        Matcher swift = SWIFT_EREF.matcher(purpose);
        if (e2e == null && swift.find()) e2e = swift.group(1);
        String remittance = Stream.of(posting, text, name).map(MtParser::blankToNull).filter(Objects::nonNull)
                .collect(Collectors.joining(" "));
        return new Info(MtParser.blankToNull(remittance), reference(e2e), reference(kw.get("KREF")));
    }

    /** SEPA keyword → its value, up to the next keyword. */
    private static Map<String, String> keywords(String purpose) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = KEYWORD.matcher(purpose);
        String key = null;
        int from = 0;
        while (true) {
            boolean found = m.find();
            if (key != null) out.putIfAbsent(key, purpose.substring(from, found ? m.start() : purpose.length()).strip());
            if (!found) return out;
            key = m.group(1);
            from = m.end();
        }
    }

    private static String reference(String s) {
        s = MtParser.blankToNull(s);
        return s == null || s.equals("NOTPROVIDED") || s.equals("NONREF") ? null : s;
    }
}
