package googol.common;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Estatísticas do sistema (funcionalidade 6), calculadas pela Gateway.
 * <ul>
 *   <li>as 10 pesquisas mais comuns;</li>
 *   <li>os Barrels ativos e o tamanho do índice de cada um;</li>
 *   <li>o tempo médio de resposta a pesquisas de cada Barrel, medido pela
 *       Gateway, em <b>décimas de segundo</b>.</li>
 * </ul>
 */
public class SystemStats implements Serializable {
    private static final long serialVersionUID = 1L;

    /** As 10 pesquisas mais frequentes, da mais para a menos comum. */
    private final List<String> topSearches;
    /** id do Barrel -> número de palavras no índice. */
    private final Map<String, Integer> barrelIndexSizes;
    /** id do Barrel -> tempo médio de resposta (décimas de segundo). */
    private final Map<String, Double> barrelAvgResponseTimes;

    public SystemStats(List<String> topSearches,
                       Map<String, Integer> barrelIndexSizes,
                       Map<String, Double> barrelAvgResponseTimes) {
        this.topSearches = topSearches;
        this.barrelIndexSizes = barrelIndexSizes;
        this.barrelAvgResponseTimes = barrelAvgResponseTimes;
    }

    public List<String> getTopSearches() { return topSearches; }
    public Map<String, Integer> getBarrelIndexSizes() { return barrelIndexSizes; }
    public Map<String, Double> getBarrelAvgResponseTimes() { return barrelAvgResponseTimes; }

    /**
     * Duas estatísticas são iguais se todos os valores forem iguais. A Gateway
     * usa isto para só enviar estatísticas aos clientes quando algo mudou.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SystemStats)) return false;
        SystemStats s = (SystemStats) other;
        return Objects.equals(topSearches, s.topSearches)
                && Objects.equals(barrelIndexSizes, s.barrelIndexSizes)
                && Objects.equals(barrelAvgResponseTimes, s.barrelAvgResponseTimes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(topSearches, barrelIndexSizes, barrelAvgResponseTimes);
    }
}
