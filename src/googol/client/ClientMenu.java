package googol.client;

import googol.common.Config;
import googol.common.SearchPage;
import googol.common.SearchResult;
import googol.common.SystemStats;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Interface de texto do Cliente Googol (menu na consola).
 * <p>
 * Limita-se a ler o que o utilizador escreve, chamar o {@link Client} e mostrar
 * os resultados.
 * <pre>
 *   java -cp "out;lib\*" googol.client.ClientMenu [ficheiro.properties]
 * </pre>
 */
public class ClientMenu {

    private final Client client;
    private final BufferedReader in;

    ClientMenu(Client client, BufferedReader in) {
        this.client = client;
        this.in = in;
    }

    public static void main(String[] args) {
        try {
            Config config = Config.load(args);
            // IP desta máquina: a Gateway usa-o para enviar as estatísticas (callback).
            System.setProperty("java.rmi.server.hostname", config.get("rmi.hostname"));
            Console console = System.console();
            BufferedReader in = new BufferedReader(console != null ? console.reader() : new InputStreamReader(System.in));
            Client client = new Client(config);
            new ClientMenu(client, in).run();
            client.close();
            System.exit(0);
        } catch (Exception e) {
            System.err.println("[Cliente] Erro: " + e.getMessage());
            System.exit(1);
        }
    }

    void run() throws IOException {
        System.out.println("=== Googol: motor de pesquisa ===");
        while (true) {
            System.out.println();
            System.out.println("1 - Indexar novo URL");
            System.out.println("2 - Pesquisar");
            System.out.println("3 - Páginas com ligação para um URL");
            System.out.println("4 - Estatísticas (tempo real)");
            System.out.println("0 - Sair");
            String option = prompt("Opção: ");
            if (option == null || option.equals("0")) {
                return;
            }
            try {
                switch (option) {
                    case "1" -> indexURL();
                    case "2" -> search();
                    case "3" -> backlinks(prompt("URL: "));
                    case "4" -> stats();
                    default -> System.out.println("Opção inválida.");
                }
            } catch (RemoteException e) {
                System.out.println("Não foi possível concluir a operação: " + Client.rootMessage(e)
                        + ". Tente novamente mais tarde.");
            } catch (RuntimeException e) {
                // Nenhum erro inesperado pode fechar o cliente: mostra-se e volta-se ao menu.
                System.out.println("Erro inesperado: " + Client.rootMessage(e) + ". Volte a tentar.");
            }
        }
    }

    private void indexURL() throws IOException {
        String url = prompt("URL a indexar: ");
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            if (client.indexURL(url.trim())) {
                System.out.println("URL adicionado à fila. Será indexado em breve.");
            } else {
                System.out.println("Este URL já foi indexado ou já está na fila.");
            }
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }

    private void search() throws IOException {
        String line = prompt("Termos de pesquisa: ");
        if (line == null || line.isBlank()) {
            return;
        }
        List<String> terms = Arrays.asList(line.trim().split("\\s+"));
        int pageNumber = 0;
        while (true) {
            SearchPage page = client.search(terms, pageNumber);
            showPage(page);
            if (page.getTotalResults() == 0) {
                return;
            }
            System.out.println();
            System.out.println("[s] seguinte   [a] anterior   [l N] ligações para o resultado N   [v] voltar ao menu");
            String cmd = prompt("> ");
            if (cmd == null || cmd.equalsIgnoreCase("v")) {
                return;
            } else if (cmd.equalsIgnoreCase("s")) {
                if (page.hasNext()) pageNumber++; else System.out.println("Já está na última página.");
            } else if (cmd.equalsIgnoreCase("a")) {
                if (pageNumber > 0) pageNumber--; else System.out.println("Já está na primeira página.");
            } else if (cmd.toLowerCase().startsWith("l ")) {
                showBacklinksOfResult(page, cmd.substring(2).trim());
            } else {
                System.out.println("Comando inválido.");
            }
        }
    }

    private void showPage(SearchPage page) {
        int total = page.getTotalResults();
        if (total == 0) {
            System.out.println("Nenhuma página contém todos os termos.");
            return;
        }
        int first = page.getPageNumber() * SearchPage.PAGE_SIZE;
        int pages = (total + SearchPage.PAGE_SIZE - 1) / SearchPage.PAGE_SIZE;
        System.out.println();
        System.out.println("--- " + total + " resultados (página " + (page.getPageNumber() + 1) + " de " + pages + ") ---");
        for (int i = 0; i < page.getResults().size(); i++) {
            SearchResult r = page.getResults().get(i);
            System.out.println();
            System.out.println((first + i + 1) + ". " + r.getTitle());
            System.out.println("   " + r.getUrl());
            System.out.println("   " + r.getCitation());
            System.out.println("   (ligações recebidas: " + r.getBacklinkCount() + ")");
        }
    }

    private void showBacklinksOfResult(SearchPage page, String number) throws RemoteException {
        int first = page.getPageNumber() * SearchPage.PAGE_SIZE;
        try {
            int index = Integer.parseInt(number) - 1 - first;
            backlinks(page.getResults().get(index).getUrl());
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            System.out.println("Número de resultado inválido (escolha um dos que estão no ecrã).");
        }
    }

    private void backlinks(String url) throws RemoteException {
        if (url == null || url.isBlank()) {
            return;
        }
        List<String> links = client.getBacklinks(url.trim());
        System.out.println();
        if (links.isEmpty()) {
            System.out.println("Não se conhecem páginas com ligação para " + url.trim());
            return;
        }
        System.out.println(links.size() + " páginas com ligação para " + url.trim() + ":");
        for (String link : links) {
            System.out.println("  " + link);
        }
    }

    /** Mostra as estatísticas sempre que a Gateway as envia, até o utilizador carregar em Enter. */
    private void stats() throws IOException {
        System.out.println("A receber estatísticas em tempo real (atualizam sozinhas quando algo muda).");
        System.out.println("Carregue em Enter para voltar ao menu.");
        client.startStats(ClientMenu::printStats);
        in.readLine();
        client.stopStats();
    }

    private static synchronized void printStats(SystemStats stats) {
        System.out.println();
        System.out.println("=========== Estatísticas ===========");
        System.out.println("10 pesquisas mais comuns:");
        if (stats.getTopSearches().isEmpty()) {
            System.out.println("  (ainda não houve pesquisas)");
        }
        for (int i = 0; i < stats.getTopSearches().size(); i++) {
            System.out.println("  " + (i + 1) + ". " + stats.getTopSearches().get(i));
        }
        System.out.println("Barrels ativos (palavras no índice | tempo médio de resposta):");
        if (stats.getBarrelIndexSizes().isEmpty()) {
            System.out.println("  (nenhum Barrel ativo)");
        }
        for (Map.Entry<String, Integer> e : stats.getBarrelIndexSizes().entrySet()) {
            double avg = stats.getBarrelAvgResponseTimes().getOrDefault(e.getKey(), 0.0);
            System.out.printf("  %s: %d palavras | %.3f décimas de segundo%n", e.getKey(), e.getValue(), avg);
        }
        System.out.println("====================================");
    }

    private String prompt(String text) throws IOException {
        System.out.print(text);
        System.out.flush();
        String line = in.readLine();
        return line == null ? null : line.trim();
    }
}
