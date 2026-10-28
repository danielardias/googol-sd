package googol.barrel;

import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.SearchPage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Testes da lógica do Barrel (sem RMI, sem rede).
 * <p>
 * Imprime uma tabela "descrição | PASS/FAIL" que pode ser aproveitada para a
 * tabela de testes do relatório.
 * <pre>
 *   java -cp "out;lib\*" googol.barrel.BarrelTeste
 * </pre>
 */
public class BarrelTeste {

    private static int passed = 0;
    private static int failed = 0;
    private static long seq = 0;

    public static void main(String[] args) throws Exception {
        Path file = Files.createTempDirectory("barrel-teste").resolve("barrel.dat");
        BarrelStorage s = new BarrelStorage(file, false);

        // Grafo de teste:  A -> C,  B -> C,  B -> A
        // Ligações recebidas: C tem 2, A tem 1, B tem 0.
        s.apply(msg("http://a.pt", "Página A", Set.of("universidade", "coimbra"), List.of("http://c.pt")));
        s.apply(msg("http://b.pt", "Página B", Set.of("universidade", "lisboa"), List.of("http://c.pt", "http://a.pt")));
        s.apply(msg("http://c.pt", "Página C", Set.of("universidade", "coimbra", "porto"), List.of()));

        check("Pesquisa de 1 termo devolve todas as páginas com esse termo",
                s.search(List.of("universidade"), 0).getTotalResults() == 3);

        check("Pesquisa de 2 termos devolve só as páginas com AMBOS",
                urls(s.search(List.of("universidade", "coimbra"), 0)).equals(List.of("http://c.pt", "http://a.pt")));

        check("Resultados ordenados por nº de ligações recebidas (C=2, A=1, B=0)",
                urls(s.search(List.of("universidade"), 0)).equals(List.of("http://c.pt", "http://a.pt", "http://b.pt")));

        check("Pesquisa ignora maiúsculas/minúsculas",
                s.search(List.of("UNIVERSIDADE", "Coimbra"), 0).getTotalResults() == 2);

        check("Termo inexistente devolve 0 resultados",
                s.search(List.of("universidade", "xyz"), 0).getTotalResults() == 0);

        check("Lista de backlinks de C contém A e B",
                s.getBacklinks("http://c.pt").equals(List.of("http://a.pt", "http://b.pt")));

        // Duplicado: mesma mensagem (mesmo downloader + mesmo nº de sequência)
        IndexMessage dup = msg("http://d.pt", "Página D", Set.of("duplicado"), List.of());
        boolean first = s.apply(dup);
        boolean second = s.apply(dup);
        check("Mensagem repetida (retry) é ignorada", first && !second);

        // Paginação: 25 páginas com a palavra "comum" -> páginas de 10, 10 e 5
        for (int i = 0; i < 25; i++) {
            s.apply(msg("http://p" + i + ".pt", "P" + i, Set.of("comum"), List.of()));
        }
        SearchPage p0 = s.search(List.of("comum"), 0);
        SearchPage p1 = s.search(List.of("comum"), 1);
        SearchPage p2 = s.search(List.of("comum"), 2);
        check("Resultados agrupados de 10 em 10 (10 + 10 + 5)",
                p0.getResults().size() == 10 && p1.getResults().size() == 10 && p2.getResults().size() == 5);
        check("hasNext() indica corretamente se há página seguinte",
                p0.hasNext() && p1.hasNext() && !p2.hasNext());

        // Persistência: um Barrel novo, com o mesmo ficheiro, recupera tudo (simula um crash)
        int sizeBefore = s.getIndexSize();
        BarrelStorage recovered = new BarrelStorage(file, false);
        check("Após reinício, o Barrel recupera o índice do disco",
                recovered.getIndexSize() == sizeBefore
                        && urls(recovered.search(List.of("universidade"), 0))
                        .equals(List.of("http://c.pt", "http://a.pt", "http://b.pt")));
        check("Após reinício, continua a ignorar mensagens já recebidas",
                !recovered.apply(dup));

        // Mais de 200 páginas: obriga a gravar a fotografia completa e a esvaziar o registo
        for (int i = 0; i < BarrelStorage.SNAPSHOT_EVERY + 50; i++) {
            recovered.apply(msg("http://s" + i + ".pt", "S" + i, Set.of("fotografia"), List.of()));
        }
        Path log = Path.of(file + ".log");
        BarrelStorage afterSnapshot = new BarrelStorage(file, false);
        check("Após reinício, recupera páginas da fotografia E do registo (250 páginas)",
                afterSnapshot.search(List.of("fotografia"), 0).getTotalResults() == BarrelStorage.SNAPSHOT_EVERY + 50
                        && afterSnapshot.search(List.of("universidade"), 0).getTotalResults() == 3);

        // Simula um crash a meio de uma escrita: o fim do registo fica com lixo incompleto
        afterSnapshot.apply(msg("http://antes-do-crash.pt", "X", Set.of("crash"), List.of()));
        Files.write(log, new byte[]{0, 0, 1, 0, 42, 42}, java.nio.file.StandardOpenOption.APPEND);
        BarrelStorage afterCrash = new BarrelStorage(file, false);
        check("Registo com escrita incompleta (crash): recupera o que estava completo e ignora o resto",
                afterCrash.search(List.of("crash"), 0).getTotalResults() == 1
                        && afterCrash.search(List.of("fotografia"), 0).getTotalResults() == BarrelStorage.SNAPSHOT_EVERY + 50);

        // --reset: começa limpo
        BarrelStorage clean = new BarrelStorage(file, true);
        check("Arrancar com --reset apaga os dados de teste (fotografia e registo)",
                clean.getIndexSize() == 0 && !Files.exists(log));

        System.out.println();
        System.out.println("Total: " + passed + " PASS, " + failed + " FAIL");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static IndexMessage msg(String url, String title, Set<String> words, List<String> links) {
        return new IndexMessage("teste", ++seq, new PageData(url, title, "citação de " + title, words, links));
    }

    private static List<String> urls(SearchPage page) {
        List<String> list = new ArrayList<>();
        page.getResults().forEach(r -> list.add(r.getUrl()));
        return list;
    }

    private static void check(String description, boolean ok) {
        System.out.println((ok ? "PASS" : "FAIL") + " | " + description);
        if (ok) passed++; else failed++;
    }
}
