package googol.gateway;

import googol.barrel.Barrel;
import googol.common.BarrelInterface;
import googol.common.GatewayInterface;
import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.QueueInterface;
import googol.common.SearchPage;
import googol.common.StatsCallback;
import googol.common.SystemStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Testes de integração da Gateway com <b>dois Barrels reais, via RMI</b>
 * (tudo na mesma máquina, numa porta de teste).
 * <p>
 * Imprime uma tabela "PASS/FAIL | descrição" para a tabela de testes do relatório.
 * <pre>
 *   java -cp "out;lib\*" googol.gateway.GatewayTeste
 * </pre>
 */
public class GatewayTeste {

    private static final int TEST_PORT = 1199;
    private static int passed = 0;
    private static int failed = 0;
    private static long seq = 0;

    /** Cliente de teste que guarda as estatísticas recebidas por callback. */
    static class TestCallback extends UnicastRemoteObject implements StatsCallback {
        private static final long serialVersionUID = 1L;
        final transient BlockingQueue<SystemStats> received = new LinkedBlockingQueue<>();

        TestCallback() throws RemoteException {
            super();
        }

        @Override
        public void onStatsUpdate(SystemStats stats) {
            received.add(stats);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.rmi.server.hostname", "localhost");
        Path dir = Files.createTempDirectory("gateway-teste");
        Registry registry = LocateRegistry.createRegistry(TEST_PORT);

        Gateway gw = new Gateway(dir.resolve("gateway.dat"), false);
        registry.rebind("gw-teste", gw);
        gw.startMonitor(300);

        // Do lado dos clientes/downloaders usamos SEMPRE a referência remota (stub),
        // obtida com getRegistry — tal como os programas reais. (O objeto devolvido por
        // createRegistry é local: as chamadas feitas através dele não passariam por RMI.)
        Registry remoteRegistry = LocateRegistry.getRegistry("localhost", TEST_PORT);
        GatewayInterface client = (GatewayInterface) remoteRegistry.lookup("gw-teste");
        QueueInterface queue = (QueueInterface) remoteRegistry.lookup("gw-teste");

        // ---------------- Fila de URLs ----------------
        check("indexURL aceita um URL novo", client.indexURL("http://novo.pt"));
        check("takeURL devolve o URL pedido pelo utilizador", "http://novo.pt".equals(queue.takeURL()));
        check("indexURL recusa um URL já conhecido", !client.indexURL("http://novo.pt"));

        boolean rejected = false;
        try {
            client.indexURL("ftp://invalido.pt");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        check("indexURL rejeita URLs que não são http(s)", rejected);

        queue.addURLs(List.of("http://x.pt", "http://y.pt", "http://x.pt", "http://y.pt#seccao"));
        String a = queue.takeURL();
        String b = queue.takeURL();
        check("addURLs ignora repetidos (incluindo o mesmo URL com #secção)",
                "http://x.pt".equals(a) && "http://y.pt".equals(b) && queue.takeURL() == null);

        queue.addURLs(List.of("http://automatico.pt"));
        client.indexURL("http://utilizador.pt");
        check("URL do utilizador passa à frente dos encontrados automaticamente",
                "http://utilizador.pt".equals(queue.takeURL()));
        check("Cada URL é entregue a um só Downloader (takeURL retira-o da fila)",
                "http://automatico.pt".equals(queue.takeURL()) && queue.takeURL() == null);

        // ---------------- Barrels ----------------
        Barrel b1 = Barrel.create("barrel1", dir.resolve("b1.dat"), false);
        Barrel b2 = Barrel.create("barrel2", dir.resolve("b2.dat"), false);
        queue.registerBarrel("barrel1", b1);
        queue.registerBarrel("barrel2", b2);
        check("Os dois Barrels ficam registados na Gateway", queue.getBarrels().size() == 2);

        // Simula o multicast de um Downloader: a mesma mensagem para todos os Barrels.
        // Grafo: A -> C, B -> C, B -> A   (C tem 2 ligações, A tem 1, B tem 0)
        multicast(queue, page("http://a.pt", Set.of("universidade", "coimbra"), List.of("http://c.pt")));
        multicast(queue, page("http://b.pt", Set.of("universidade", "lisboa"), List.of("http://c.pt", "http://a.pt")));
        multicast(queue, page("http://c.pt", Set.of("universidade", "coimbra"), List.of()));

        check("Pesquisa pela Gateway devolve resultados ordenados por ligações recebidas",
                urls(client.search(List.of("universidade"), 0)).equals(List.of("http://c.pt", "http://a.pt", "http://b.pt")));
        check("Backlinks pela Gateway", client.getBacklinks("http://c.pt").equals(List.of("http://a.pt", "http://b.pt")));

        for (int i = 0; i < 9; i++) {
            client.search(List.of("coimbra"), 0);
        }
        SystemStats stats = client.getStats();
        check("Balanceamento: os dois Barrels respondem a pesquisas (ambos têm tempo médio > 0)",
                stats.getBarrelAvgResponseTimes().getOrDefault("barrel1", 0.0) > 0
                        && stats.getBarrelAvgResponseTimes().getOrDefault("barrel2", 0.0) > 0);
        check("Top de pesquisas: 'coimbra' (9 vezes) é a mais comum",
                stats.getTopSearches().get(0).equals("coimbra (9)"));

        client.search(List.of("coimbra"), 1);
        check("Avançar de página não conta como pesquisa nova",
                client.getStats().getTopSearches().get(0).equals("coimbra (9)"));

        // ---------------- Estatísticas por callback ----------------
        TestCallback callback = new TestCallback();
        client.subscribeStats(callback);
        SystemStats first = callback.received.poll(3, TimeUnit.SECONDS);
        check("Cliente subscrito recebe as estatísticas por callback", first != null);

        // ---------------- Failover ----------------
        UnicastRemoteObject.unexportObject(b1, true); // simula a avaria do barrel1
        boolean allOk = true;
        for (int i = 0; i < 4; i++) { // 4 pesquisas: pelo menos uma calharia ao barrel1
            allOk &= client.search(List.of("universidade"), 0).getTotalResults() == 3;
        }
        check("Failover: com o barrel1 avariado, as pesquisas continuam a funcionar", allOk);
        check("O Barrel avariado é retirado da lista de ativos",
                queue.getBarrels().keySet().equals(Set.of("barrel2")));

        SystemStats update = null;
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            SystemStats s = callback.received.poll(200, TimeUnit.MILLISECONDS);
            if (s != null && s.getBarrelIndexSizes().keySet().equals(Set.of("barrel2"))) {
                update = s;
                break;
            }
        }
        check("O cliente é avisado por callback de que o barrel1 saiu", update != null);

        UnicastRemoteObject.unexportObject(b2, true); // agora avaria também o barrel2
        boolean error = false;
        try {
            client.search(List.of("universidade"), 0);
        } catch (RemoteException e) {
            error = true;
        }
        check("Sem nenhum Barrel ativo, a pesquisa dá um erro claro (não bloqueia)", error);

        // ---------------- Gravação do estado ----------------
        // (Gateways locais, fora do RMI, para testar só a persistência.)
        Path f2 = dir.resolve("g2.dat");
        Gateway lenta = new Gateway(f2, false, 60_000);              // gravação periódica só ao fim de 60 s
        lenta.indexURL("http://pedido-do-utilizador.pt");
        check("Pedido de indexação do utilizador fica em disco de imediato (sem esperar pela gravação periódica)",
                "http://pedido-do-utilizador.pt".equals(lerPrimeiroURL(f2)));
        lenta.shutdown();

        Path f3 = dir.resolve("g3.dat");
        Gateway rapida = new Gateway(f3, false, 200);                // gravação periódica a cada 200 ms
        rapida.addURLs(List.of("http://descoberto.pt"));
        Thread.sleep(700);
        check("URLs descobertos são gravados pela gravação periódica (sem shutdown)",
                "http://descoberto.pt".equals(lerPrimeiroURL(f3)));

        List<String> muitos = new ArrayList<>();
        for (int i = 0; i < 100_000; i++) {
            muitos.add("https://exemplo.pt/pagina/" + i);
        }
        rapida.addURLs(muitos);
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            rapida.takeURL();
        }
        double msPorTake = (System.nanoTime() - t0) / 1000 / 1e6;
        System.out.printf("  (takeURL com 100 000 URLs conhecidos: %.3f ms em média)%n", msPorTake);
        check("Desempenho: takeURL continua rápido com 100 000 URLs conhecidos (< 5 ms)", msPorTake < 5);
        rapida.shutdown();

        // Falha passageira de gravação (como o antivírus do Windows a bloquear o ficheiro):
        // uma pasta no lugar do ficheiro faz a gravação falhar até ser retirada.
        Path f5 = dir.resolve("g5.dat");
        Gateway bloqueada = new Gateway(f5, false, 150);
        Files.createDirectories(f5.resolve("bloqueio"));
        java.io.PrintStream errOriginal = System.err;
        java.io.ByteArrayOutputStream errCapturado = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(errCapturado, true));
        bloqueada.addURLs(List.of("http://sobrevive.pt"));
        Thread.sleep(450);                                       // 2 a 3 gravações falhadas
        Files.delete(f5.resolve("bloqueio"));
        Files.delete(f5);                                        // o "antivírus" larga o ficheiro
        Thread.sleep(450);                                       // a volta seguinte grava
        System.setErr(errOriginal);
        check("Falha passageira de gravação: sem aviso no ecrã, e o estado é gravado na volta seguinte",
                errCapturado.size() == 0 && "http://sobrevive.pt".equals(lerPrimeiroURL(f5)));
        bloqueada.shutdown();

        // ---------------- Termos com pontuação ----------------
        Gateway gwTermos = new Gateway(dir.resolve("g4.dat"), false);
        Barrel b3 = Barrel.create("barrel3", dir.resolve("b3.dat"), false);
        gwTermos.registerBarrel("barrel3", b3);
        b3.receive(new IndexMessage("teste", ++seq, page("http://rock.pt", Set.of("rock", "n", "roll", "coimbra"), List.of())));
        check("Pesquisa com pontuação ('Coimbra!', 'Rock-N-Roll') usa a mesma divisão em palavras da indexação",
                gwTermos.search(List.of("Coimbra!"), 0).getTotalResults() == 1
                        && gwTermos.search(List.of("Rock-N-Roll"), 0).getTotalResults() == 1);
        gwTermos.shutdown();
        UnicastRemoteObject.unexportObject(b3, true);

        // ---------------- Recuperação da Gateway ----------------
        queue.addURLs(List.of("http://pendente.pt"));
        gw.shutdown(); // simula a avaria da Gateway
        Gateway recovered = new Gateway(dir.resolve("gateway.dat"), false);
        check("Após reinício, a Gateway recupera a fila de URLs", "http://pendente.pt".equals(recovered.takeURL()));
        check("Após reinício, a Gateway recupera a contagem de pesquisas",
                recovered.getStats().getTopSearches().get(0).startsWith("coimbra"));
        recovered.shutdown();

        System.out.println();
        System.out.println("Total: " + passed + " PASS, " + failed + " FAIL");
        System.exit(failed > 0 ? 1 : 0);
    }

    /**
     * Lê, sem alterar nada, o primeiro URL da fila gravada em {@code file}: abre uma
     * Gateway que nunca grava (intervalo de 1 hora) e retira-a do RMI sem a desligar
     * com shutdown(), que gravaria o estado. Assim nunca há duas Gateways a gravar o
     * mesmo ficheiro (no Windows, a segunda é bloqueada pelo sistema de ficheiros).
     */
    private static String lerPrimeiroURL(Path file) throws Exception {
        Gateway leitora = new Gateway(file, false, 3_600_000);
        String url = leitora.takeURL();
        UnicastRemoteObject.unexportObject(leitora, true);
        return url;
    }

    private static PageData page(String url, Set<String> words, List<String> links) {
        return new PageData(url, "Título " + url, "citação de " + url, words, links);
    }

    private static void multicast(QueueInterface queue, PageData page) throws RemoteException {
        IndexMessage message = new IndexMessage("teste", ++seq, page);
        for (BarrelInterface barrel : queue.getBarrels().values()) {
            barrel.receive(message);
        }
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
