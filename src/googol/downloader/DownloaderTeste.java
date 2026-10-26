package googol.downloader;

import com.sun.net.httpserver.HttpServer;

import googol.barrel.Barrel;
import googol.common.BarrelInterface;
import googol.common.Config;
import googol.common.GatewayInterface;
import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.QueueInterface;
import googol.common.SearchPage;
import googol.gateway.Gateway;

import org.jsoup.Jsoup;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Testes do Downloader:
 * <ol>
 *   <li><b>PageParser</b> — extração de título, palavras, citação e ligações (sem rede);</li>
 *   <li><b>ReliableMulticast</b> — entrega, falhas e retransmissão (com Barrels simulados);</li>
 *   <li><b>Sistema completo</b> — servidor Web local + Gateway + 2 Barrels + Downloader,
 *       tudo via RMI, incluindo a avaria e recuperação de um Barrel.</li>
 * </ol>
 * <pre>
 *   java -cp "out;lib\*" googol.downloader.DownloaderTeste
 * </pre>
 */
public class DownloaderTeste {

    private static final int TEST_PORT = 1197;
    private static int passed = 0;
    private static int failed = 0;

    /** Barrel simulado (sem RMI): pode ser "desligado" para simular uma avaria. */
    static class FakeBarrel implements BarrelInterface {
        boolean up = true;
        final List<Long> received = new ArrayList<>();

        @Override
        public void receive(IndexMessage message) throws RemoteException {
            if (!up) {
                throw new RemoteException("avariado");
            }
            received.add(message.getSeqNumber());
        }

        @Override public SearchPage search(List<String> terms, int pageNumber) { return null; }
        @Override public List<String> getBacklinks(String url) { return null; }
        @Override public int getIndexSize() { return received.size(); }
        @Override public boolean ping() { return true; }
    }

    public static void main(String[] args) throws Exception {
        testPageParser();
        testReliableMulticast();
        testFullSystem();

        System.out.println();
        System.out.println("Total: " + passed + " PASS, " + failed + " FAIL");
        System.exit(failed > 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------
    // 1. PageParser
    // ------------------------------------------------------------------

    private static void testPageParser() {
        String html = "<html><head><title> Universidade de Coimbra </title></head><body>"
                + "<p>A Universidade, de COIMBRA! Fundada em 1290. Educação e ciência.</p>"
                + "<a href='/outra'>x</a> <a href='http://ext.pt/p#seccao'>y</a>"
                + "<a href='mailto:a@b.pt'>z</a> <a href='/outra'>repetido</a>"
                + "<a href='http://site.pt/pasta/'>a própria página</a>"
                + "</body></html>";
        PageData p = PageParser.parse("http://site.pt/pasta/", Jsoup.parse(html, "http://site.pt/pasta/"), 5);

        check("Parser: título extraído (sem espaços a mais)", p.getTitle().equals("Universidade de Coimbra"));
        check("Parser: palavras em minúsculas e sem pontuação",
                p.getWords().containsAll(Set.of("universidade", "coimbra", "fundada", "1290")));
        check("Parser: palavras com acentos mantidas", p.getWords().containsAll(Set.of("educação", "ciência")));
        check("Parser: citação com as primeiras N palavras",
                p.getCitation().equals("A Universidade, de COIMBRA! Fundada ..."));
        check("Parser: ligações absolutas, só http(s), sem #secção, sem repetidos e sem a própria página",
                p.getLinks().equals(List.of("http://site.pt/outra", "http://ext.pt/p")));
    }

    // ------------------------------------------------------------------
    // 2. ReliableMulticast
    // ------------------------------------------------------------------

    private static void testReliableMulticast() {
        FakeBarrel b1 = new FakeBarrel();
        FakeBarrel b2 = new FakeBarrel();
        Map<String, BarrelInterface> active = new HashMap<>();
        active.put("barrel1", b1);
        boolean[] gatewayUp = {true};

        ReliableMulticast rm = new ReliableMulticast(() -> {
            if (!gatewayUp[0]) {
                throw new RemoteException("gateway em baixo");
            }
            return new HashMap<>(active);
        }, List.of("barrel1", "barrel2"));

        // barrel2 ainda não se registou: a mensagem 1 fica guardada para ele
        rm.send(msg(1));
        check("Multicast: Barrel ativo recebe a mensagem", b1.received.equals(List.of(1L)));
        check("Multicast: Barrel do grupo ainda não registado fica com a mensagem pendente",
                rm.pendingCount("barrel2") == 1);

        active.put("barrel2", b2);
        rm.retryPending();
        check("Multicast: quando o Barrel aparece, recebe as mensagens anteriores",
                b2.received.equals(List.of(1L)) && rm.pendingCount("barrel2") == 0);

        b2.up = false; // avaria do barrel2
        rm.send(msg(2));
        rm.send(msg(3));
        check("Multicast: avaria de um Barrel não afeta o outro", b1.received.equals(List.of(1L, 2L, 3L)));
        check("Multicast: mensagens para o Barrel avariado ficam pendentes", rm.pendingCount("barrel2") == 2);

        rm.retryPending();
        check("Multicast: enquanto o Barrel está em baixo, as mensagens não se perdem", rm.pendingCount("barrel2") == 2);

        b2.up = true; // o barrel2 recupera
        rm.retryPending();
        check("Multicast: após recuperar, o Barrel recebe as pendentes PELA ORDEM de envio",
                b2.received.equals(List.of(1L, 2L, 3L)) && rm.pendingCount("barrel2") == 0);

        gatewayUp[0] = false; // a Gateway avaria
        rm.send(msg(4));
        check("Multicast: com a Gateway em baixo, continua a enviar aos Barrels já conhecidos",
                b1.received.contains(4L) && b2.received.contains(4L));
    }

    private static IndexMessage msg(long seq) {
        return new IndexMessage("teste", seq, new PageData("http://p" + seq + ".pt", "t", "c", Set.of("w"), List.of()));
    }

    // ------------------------------------------------------------------
    // 3. Sistema completo
    // ------------------------------------------------------------------

    private static void testFullSystem() throws Exception {
        System.setProperty("java.rmi.server.hostname", "localhost");
        Path dir = Files.createTempDirectory("sistema-teste");

        // Mini servidor Web com 4 páginas.  Ligações: A -> B, A -> C, B -> C
        // (C recebe 2 ligações, B recebe 1, A recebe 0). D é independente.
        HttpServer web = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String base = "http://localhost:" + web.getAddress().getPort();
        servePage(web, "/a", "Página A", "universidade coimbra", "<a href='/b'>b</a><a href='/c'>c</a>");
        servePage(web, "/b", "Página B", "universidade lisboa", "<a href='/c'>c</a>");
        servePage(web, "/c", "Página C", "universidade porto", "");
        servePage(web, "/d", "Página D", "novidade", "");
        web.start();

        Registry registry = LocateRegistry.createRegistry(TEST_PORT);
        Gateway gw = new Gateway(dir.resolve("gateway.dat"), false);
        registry.rebind("gw-teste", gw);
        gw.startMonitor(300);
        Registry remote = LocateRegistry.getRegistry("localhost", TEST_PORT);
        GatewayInterface client = (GatewayInterface) remote.lookup("gw-teste");
        QueueInterface queue = (QueueInterface) remote.lookup("gw-teste");

        Barrel b1 = Barrel.create("barrel1", dir.resolve("b1.dat"), false);
        Barrel b2 = Barrel.create("barrel2", dir.resolve("b2.dat"), false);
        queue.registerBarrel("barrel1", b1);
        queue.registerBarrel("barrel2", b2);

        Path props = dir.resolve("teste.properties");
        Files.writeString(props, String.join("\n",
                "gateway.host=localhost", "gateway.port=" + TEST_PORT, "gateway.name=gw-teste",
                "downloader.id=downloaderT", "downloader.citation.words=10", "downloader.retry.delay.ms=300",
                "downloader.timeout.ms=3000", "downloader.threads=2", "downloader.barrels=barrel1,barrel2"));
        Downloader downloader = new Downloader(Config.load(new String[]{props.toString()}));
        downloader.start(2);

        client.indexURL(base + "/a");
        boolean indexed = waitUntil(10_000, () -> client.search(List.of("universidade"), 0).getTotalResults() == 3);
        check("Sistema: indexa o URL pedido e, iterativamente, as páginas ligadas (A, B e C)", indexed);
        check("Sistema: resultados ordenados por ligações recebidas (C, B, A)",
                urls(client.search(List.of("universidade"), 0)).equals(List.of(base + "/c", base + "/b", base + "/a")));
        check("Sistema: os dois Barrels têm exatamente a mesma informação",
                barrel(queue, "barrel1").getIndexSize() == barrel(queue, "barrel2").getIndexSize()
                        && barrel(queue, "barrel1").getIndexSize() > 0);

        // Avaria do barrel2, indexação de uma página nova, e recuperação do barrel2
        UnicastRemoteObject.unexportObject(b2, true);
        client.indexURL(base + "/d");
        boolean dIndexed = waitUntil(10_000, () -> client.search(List.of("novidade"), 0).getTotalResults() == 1);
        check("Sistema: com o barrel2 avariado, a página nova é indexada e pesquisável", dIndexed);
        check("Sistema: o Downloader guarda a página para o barrel2 avariado",
                waitUntil(3_000, () -> downloader.pendingFor("barrel2") >= 1));

        Barrel b2restarted = Barrel.create("barrel2", dir.resolve("b2.dat"), false); // reinicia (lê o disco)
        queue.registerBarrel("barrel2", b2restarted);
        boolean recovered = waitUntil(10_000,
                () -> barrel(queue, "barrel2").search(List.of("novidade"), 0).getTotalResults() == 1);
        check("Sistema: o barrel2 reiniciado recupera o que tinha (disco) e recebe o que perdeu (multicast)",
                recovered && barrel(queue, "barrel2").search(List.of("universidade"), 0).getTotalResults() == 3);
        check("Sistema: depois da recuperação, os dois Barrels voltam a ter a mesma informação",
                barrel(queue, "barrel1").getIndexSize() == barrel(queue, "barrel2").getIndexSize()
                        && downloader.pendingFor("barrel2") == 0);

        downloader.stop();
        gw.shutdown();
        web.stop(0);
    }

    private static void servePage(HttpServer server, String path, String title, String text, String links) {
        String html = "<html><head><title>" + title + "</title></head><body><p>" + text + "</p>" + links + "</body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static BarrelInterface barrel(QueueInterface queue, String id) throws RemoteException {
        return queue.getBarrels().get(id);
    }

    @FunctionalInterface
    private interface Condition {
        boolean test() throws RemoteException, NotBoundException;
    }

    /** Espera até {@code timeoutMs} que a condição seja verdadeira. */
    private static boolean waitUntil(long timeoutMs, Condition condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.test()) {
                    return true;
                }
            } catch (RemoteException | NotBoundException | NullPointerException e) {
                // ainda não está pronto
            }
            Thread.sleep(200);
        }
        return false;
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
