package googol.common;

import java.io.Serializable;
import java.util.List;

/**
 * Uma "página" de resultados de pesquisa (no máximo 10 resultados).
 * <p>
 * Os resultados são agrupados de 10 em 10. O cliente pede a página
 * {@code pageNumber} (0 = primeira) e recebe também o total de resultados,
 * para saber se pode avançar para a página seguinte.
 */
public class SearchPage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Número de resultados por página, imposto pelo enunciado. */
    public static final int PAGE_SIZE = 10;

    private final List<SearchResult> results;
    private final int pageNumber;
    private final int totalResults;

    public SearchPage(List<SearchResult> results, int pageNumber, int totalResults) {
        this.results = results;
        this.pageNumber = pageNumber;
        this.totalResults = totalResults;
    }

    public List<SearchResult> getResults() { return results; }
    public int getPageNumber() { return pageNumber; }
    public int getTotalResults() { return totalResults; }

    /** @return {@code true} se existir uma página seguinte de resultados. */
    public boolean hasNext() {
        return (pageNumber + 1) * PAGE_SIZE < totalResults;
    }
}
