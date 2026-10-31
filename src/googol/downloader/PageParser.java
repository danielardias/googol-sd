package googol.downloader;

import googol.common.PageData;
import googol.common.Tokenizer;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extrai de um documento HTML (já obtido pelo jsoup) a informação que vai
 * para o índice: título, citação, palavras e ligações.
 * <p>
 * Está separado do {@link Downloader} para poder ser testado sem rede
 * (os testes usam HTML escrito à mão).
 */
public final class PageParser {

    private PageParser() {
    }

    /**
     * @param url           URL da página
     * @param doc           documento HTML
     * @param citationWords número de palavras da citação
     */
    public static PageData parse(String url, Document doc, int citationWords) {
        String title = doc.title().isBlank() ? url : doc.title().trim();
        String bodyText = doc.body() != null ? doc.body().text() : doc.text();

        // Palavras: título + texto, em minúsculas, partidas em tudo o que não é
        // letra ou algarismo (assim "Coimbra!" e "coimbra," dão "coimbra").
        // \p{L} = qualquer letra (inclui acentos: "ção"), \p{N} = qualquer algarismo.
        // A mesma regra é usada pela Gateway nos termos de pesquisa (ver Tokenizer).
        Set<String> words = new HashSet<>(Tokenizer.tokens(title + " " + bodyText));

        // Citação: as primeiras palavras do texto, tal como aparecem na página.
        String[] parts = bodyText.trim().split("\\s+");
        StringBuilder citation = new StringBuilder();
        for (int i = 0; i < Math.min(citationWords, parts.length); i++) {
            if (i > 0) {
                citation.append(' ');
            }
            citation.append(parts[i]);
        }
        if (parts.length > citationWords) {
            citation.append(" ...");
        }

        // Ligações: URLs absolutos http(s), sem a parte "#secção", sem repetidos
        // e sem a própria página.
        Set<String> links = new LinkedHashSet<>();
        for (Element a : doc.select("a[href]")) {
            String link = a.attr("abs:href");
            int hash = link.indexOf('#');
            if (hash >= 0) {
                link = link.substring(0, hash);
            }
            if ((link.startsWith("http://") || link.startsWith("https://")) && !link.equals(url)) {
                links.add(link);
            }
        }

        return new PageData(url, title, citation.toString(), words, new ArrayList<>(links));
    }
}
