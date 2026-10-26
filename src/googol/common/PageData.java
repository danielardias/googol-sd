package googol.common;

import java.io.Serializable;
import java.util.List;
import java.util.Set;

/**
 * Informação extraída de uma página Web por um Downloader.
 * <p>
 * É o "conteúdo" que o Downloader envia aos Storage Barrels depois de
 * analisar o HTML com o jsoup. Tem de ser {@link Serializable} porque
 * viaja pela rede dentro de uma invocação RMI.
 */
public class PageData implements Serializable {
    private static final long serialVersionUID = 1L;

    /** URL completo da página. */
    private final String url;
    /** Título da página (conteúdo da tag &lt;title&gt;). */
    private final String title;
    /** Citação curta com texto da página (ex.: primeiras ~30 palavras). */
    private final String citation;
    /** Palavras encontradas no texto (já em minúsculas, sem repetições). */
    private final Set<String> words;
    /** Ligações (URLs absolutos) encontradas na página. */
    private final List<String> links;

    public PageData(String url, String title, String citation, Set<String> words, List<String> links) {
        this.url = url;
        this.title = title;
        this.citation = citation;
        this.words = words;
        this.links = links;
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getCitation() { return citation; }
    public Set<String> getWords() { return words; }
    public List<String> getLinks() { return links; }
}
