package googol.common;

import java.io.Serializable;

/**
 * Um resultado de pesquisa, tal como é mostrado ao utilizador:
 * título, URL completo e citação curta.
 * <p>
 * Inclui também o número de ligações recebidas ({@code backlinkCount}),
 * que é o critério de ordenação por relevância (funcionalidade 4).
 */
public class SearchResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String url;
    private final String title;
    private final String citation;
    private final int backlinkCount;

    public SearchResult(String url, String title, String citation, int backlinkCount) {
        this.url = url;
        this.title = title;
        this.citation = citation;
        this.backlinkCount = backlinkCount;
    }

    public String getUrl() { return url; }
    public String getTitle() { return title; }
    public String getCitation() { return citation; }
    public int getBacklinkCount() { return backlinkCount; }

    @Override
    public String toString() {
        return title + "\n" + url + "\n" + citation + "\n(ligações recebidas: " + backlinkCount + ")";
    }
}
