package com.bank.vam.collections;

import com.bank.vam.exception.BusinessException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reading inbound ISO 20022 messages by element name, whatever their namespace or prefix. No DTDs, no entities. */
final class IsoXml {

    private IsoXml() {
    }

    static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BusinessException("Not a readable ISO 20022 message: " + e.getMessage());
        }
    }

    /** Every element named {@code tag} under {@code root}, at any depth. */
    static List<Element> all(Node root, String tag) {
        NodeList nl = root instanceof Document d ? d.getElementsByTagNameNS("*", tag) : ((Element) root).getElementsByTagNameNS("*", tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    /** The first {@code tag} under {@code root}'s text, or null. */
    static String text(Element root, String tag) {
        List<Element> found = all(root, tag);
        return found.isEmpty() ? null : found.get(0).getTextContent().trim();
    }

    /** A direct child {@code tag}'s text, or null. */
    static String direct(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && tag.equals(e.getLocalName())) return e.getTextContent().trim();
        }
        return null;
    }

    /** The text at {@code tags[0]/.../tags[n]} (each the first of its name, at any depth), or null. */
    static String path(Element root, String... tags) {
        Element cur = root;
        for (int i = 0; i < tags.length - 1; i++) {
            List<Element> next = all(cur, tags[i]);
            if (next.isEmpty()) return null;
            cur = next.get(0);
        }
        return text(cur, tags[tags.length - 1]);
    }
}
