package googol.client;

import googol.barrel.Barrel;
import googol.common.BarrelInterface;
import googol.common.Config;
import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.QueueInterface;
import googol.common.SystemStats;
import googol.gateway.Gateway;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Testes do Cliente, incluindo a <b>avaria e recuperação da Gateway</b> a meio
 * das operações (o cliente tem de repetir sem que o utilizador note).
 * <pre>
 *   java -cp "out;lib\*" googol.client.ClientTeste
 * </pre>
 */
public class ClientTeste {

    private static final int TEST_PORT = 1196;
    private static final String NAME = "gw-teste";
    private static int passed = 0;
    private static int failed = 0;
    private static long seq = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.rmi.server.hostname", "localhost");
        Path dir = Files.createTempDirectory("cliente-teste");
        Path gatewayFile = dir.resolve("gateway.dat");
        Registry registry = LocateRegistry.createRegistry(TEST_PORT);

        Gateway gw = startGateway(registry, gatewayFile);
        Path props = dir.resolve("teste.properties");
        Files.writeString(props, String.join("\n",
                "gateway.host=localhost", "gateway.port=" + TEST_PORT, "gateway.name=" + NAME,
                "client.retry.attempts=6", "client.retry.delay.ms=500", "client.stats.resubscribe.ms=500"));
        Client client = new Client(Config.load(new String[]{props.toString()}));

        // ---------------- Operações básicas ----------------
        check("Cliente: indexar URL novo", client.indexURL("http://exemplo.pt"));
        check("Cliente: indexar URL repetido é recusado", !client.indexURL("http://exemplo.pt"));
        boolean invalid = false;
        try {
            client.indexURL("exemplo.pt");
        } catch (IllegalArgumentException e) {
            invalid = true;
        }
        check("Cliente: URL inválido dá mensagem de erro (sem repetir)", invalid);

        boolean noBarrels = false;
        long t0 = System.currentTimeMillis();
        try {
            client.search(List.of("x"), 0);
        } catch (RemoteException e) {
            noBarrels = Client.rootMessage(e).contains("Nenhum Storage Barrel");
        }
        check("Cliente: sem Barrels, a pesquisa termina com erro claro após as tentativas",
                noBarrels && System.currentTimeMillis() - t0 < 10_000);

        QueueInterface queue = (QueueInterface) LocateRegistry.getRegistry("localhost", TEST_PORT).lookup(NAME);
        Barrel barrel = Barrel.create("barrel1", dir.resolve("b1.dat"), false);
        queue.registerBarrel("barrel1", barrel);
        index(queue, "http://a.pt", Set.of("coimbra"), List.of("http://b.pt"));
        index(queue, "http://b.pt", Set.of("coimbra"), List.of());
        check("Cliente: pesquisa devolve os resultados", client.search(List.of("coimbra"), 0).getTotalResults() == 2);
        check("Cliente: consulta de backlinks", client.getBacklinks("http://b.pt").equals(List.of("http://a.pt")));

        // ---------------- Estatísticas ----------------
        BlockingQueue<SystemStats> received = new LinkedBlockingQueue<>();
        client.startStats(received::add);
        check("Cliente: recebe estatísticas por callback ao subscrever", received.poll(3, TimeUnit.SECONDS) != null);

        Thread.sleep(1500); // várias re-subscrições periódicas acontecem entretanto
        received.clear();
        client.search(List.of("coimbra"), 0); // uma alteração -> deve chegar UMA atualização
        Thread.sleep(1500);
        check("Cliente: re-subscrever não duplica (1 alteração = 1 atualização)", received.size() == 1);

        // ---------------- Avaria da Gateway ----------------
        // Simula a avaria: no sistema real o processo da Gateway morre e o registry
        // (que corre dentro dele) desaparece também; aqui retiramos o registo à mão.
        gw.shutdown();
        registry.unbind(NAME);
        System.out.println("  (Gateway desligada; volta daqui a 2 segundos)");
        Thread restarter = new Thread(() -> {
            try {
                Thread.sleep(2000);
                Gateway recovered = startGateway(registry, gatewayFile);
                recovered.registerBarrel("barrel1", (BarrelInterface) barrel); // os Barrels reais fazem-no sozinhos
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        restarter.start();
        received.clear();
        int results = client.search(List.of("coimbra"), 0).getTotalResults();
        check("Cliente: pesquisa feita com a Gateway em baixo conclui quando ela volta (retry)", results == 2);

        boolean resumed = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !resumed) {
            SystemStats s = received.poll(200, TimeUnit.MILLISECONDS);
            resumed = s != null && s.getBarrelIndexSizes().containsKey("barrel1");
        }
        check("Cliente: as estatísticas são retomadas sozinhas depois de a Gateway reiniciar", resumed);
        client.stopStats();

        // ---------------- Menu (com entrada simulada) ----------------
        String input = String.join("\n", "2", "coimbra", "l 2", "v", "9", "0") + "\n";
        new ClientMenu(client, new BufferedReader(new StringReader(input))).run();
        check("Menu: pesquisa, backlinks de um resultado, opção inválida e sair (sem exceções)", true);

        client.close();
        System.out.println();
        System.out.println("Total: " + passed + " PASS, " + failed + " FAIL");
        System.exit(failed > 0 ? 1 : 0);
    }

    private static Gateway startGateway(Registry registry, Path file) throws Exception {
        Gateway gw = new Gateway(file, false);
        registry.rebind(NAME, gw);
        gw.startMonitor(300);
        return gw;
    }

    private static void index(QueueInterface queue, String url, Set<String> words, List<String> links) throws RemoteException {
        IndexMessage m = new IndexMessage("teste", ++seq, new PageData(url, "Título " + url, "citação", words, links));
        for (BarrelInterface b : queue.getBarrels().values()) {
            b.receive(m);
        }
    }

    private static void check(String description, boolean ok) {
        System.out.println((ok ? "PASS" : "FAIL") + " | " + description);
        if (ok) passed++; else failed++;
    }
}
