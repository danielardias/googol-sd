package googol.downloader;

import googol.common.Config;
import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.QueueInterface;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Downloader (Web crawler) — obtém páginas Web e envia-as aos Storage Barrels.
 * <p>
 * Cada Downloader corre <b>várias threads em paralelo</b> ({@code downloader.threads});
 * podem também correr vários Downloaders em máquinas diferentes. Cada thread repete:
 * <ol>
 *   <li>pede um URL à fila da Gateway ({@code takeURL}, que entrega cada URL a um só Downloader);</li>
 *   <li>descarrega e analisa a página com o jsoup ({@link PageParser});</li>
 *   <li>envia a página a todos os Barrels por <b>reliable multicast</b> ({@link ReliableMulticast});</li>
 *   <li>junta as ligações encontradas à fila (indexação iterativa).</li>
 * </ol>
 * <b>Como correr:</b>
 * <pre>
 *   java -cp "out;lib\*" googol.downloader.Downloader [ficheiro.properties]
 * </pre>
 */
public class Downloader {

    private static final String USER_AGENT = "GoogolBot/1.0 (projeto academico SD - UC)";

    /**
     * Identificador desta execução: id da configuração + instante de arranque
     * (ex.: "downloader1@1793040000000"). O instante é essencial: os números de
     * sequência recomeçam em 1 quando o Downloader reinicia, e sem ele os Barrels
     * julgariam que as mensagens novas eram duplicados das antigas.
     */
    private final String sessionId;
    private final String logPrefix;
    private final String gatewayHost;
    private final int gatewayPort;
    private final String gatewayName;
    private final int citationWords;
    private final int timeoutMs;
    private final long retryDelayMs;

    private final AtomicLong seqNumber = new AtomicLong();
    private final ReliableMulticast multicast;
    private final List<Thread> threads = new ArrayList<>();
    private volatile QueueInterface gateway;
    private volatile boolean running = true;
    private volatile boolean gatewayDownWarned = false;

    public Downloader(Config config) {
        String id = config.get("downloader.id");
        this.sessionId = id + "@" + System.currentTimeMillis();
        this.logPrefix = "[" + id + "] ";
        this.gatewayHost = config.get("gateway.host");
        this.gatewayPort = config.getInt("gateway.port");
        this.gatewayName = config.get("gateway.name");
        this.citationWords = config.getInt("downloader.citation.words");
        this.timeoutMs = config.getInt("downloader.timeout.ms");
        this.retryDelayMs = config.getInt("downloader.retry.delay.ms");
        List<String> barrelIds = Arrays.asList(config.get("downloader.barrels").split("\\s*,\\s*"));
        this.multicast = new ReliableMulticast(() -> gateway().getBarrels(), barrelIds);
    }

    /** Arranca {@code workers} threads de download e a thread de retransmissão do multicast. */
    public void start(int workers) {
        for (int i = 1; i <= workers; i++) {
            threads.add(new Thread(this::workerLoop, "downloader-worker-" + i));
        }
        threads.add(new Thread(this::retryLoop, "multicast-retry"));
        for (Thread t : threads) {
            t.start();
        }
        System.out.println(logPrefix + "Pronto com " + workers + " threads (sessão " + sessionId + ").");
    }

    /** Pára todas as threads (usado nos testes). */
    public void stop() {
        running = false;
        for (Thread t : threads) {
            t.interrupt();
        }
    }

    /** Número de mensagens que ainda não chegaram a um Barrel (usado nos testes). */
    public int pendingFor(String barrelId) {
        return multicast.pendingCount(barrelId);
    }

    private void workerLoop() {
        while (running) {
            try {
                String url = gateway().takeURL();
                gatewayDownWarned = false;
                if (url == null) {
                    Thread.sleep(500); // fila vazia: espera um pouco
                    continue;
                }
                process(url);
            } catch (RemoteException | NotBoundException e) {
                gateway = null; // força nova procura da Gateway (pode ter reiniciado)
                if (!gatewayDownWarned) {
                    gatewayDownWarned = true;
                    System.out.println(logPrefix + "Gateway indisponível. A tentar novamente...");
                }
                sleepQuietly(retryDelayMs);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Nunca deixar uma thread de download morrer por um erro inesperado numa página.
                System.out.println(logPrefix + "Erro inesperado (" + e.getClass().getSimpleName() + "): "
                        + e.getMessage() + ". A continuar.");
            }
        }
    }

    /** Descarrega, analisa, envia aos Barrels e junta as ligações à fila. */
    private void process(String url) throws RemoteException, NotBoundException {
        PageData page;
        try {
            Document doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(timeoutMs).get();
            page = PageParser.parse(url, doc, citationWords);
        } catch (IOException | RuntimeException e) {
            // Página inexistente, sem resposta, que não é HTML (PDF, imagem...) ou mal formada: ignora.
            System.out.println(logPrefix + "Ignorado " + url + " (" + e.getClass().getSimpleName() + ")");
            return;
        }
        multicast.send(new IndexMessage(sessionId, seqNumber.incrementAndGet(), page));
        gateway().addURLs(page.getLinks());
        System.out.println(logPrefix + "Indexado " + url + " (" + page.getWords().size() + " palavras, "
                + page.getLinks().size() + " ligações)" + multicast.pendingSummary());
    }

    private void retryLoop() {
        while (running) {
            if (!sleepQuietly(retryDelayMs)) {
                return;
            }
            multicast.retryPending();
        }
    }

    /** Procura a Gateway no registry RMI (só quando ainda não a conhece). */
    private QueueInterface gateway() throws RemoteException, NotBoundException {
        QueueInterface g = gateway;
        if (g == null) {
            g = (QueueInterface) LocateRegistry.getRegistry(gatewayHost, gatewayPort).lookup(gatewayName);
            gateway = g;
        }
        return g;
    }

    /** @return {@code false} se a thread foi interrompida */
    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static void main(String[] args) {
        try {
            Config config = Config.load(args);
            Downloader downloader = new Downloader(config);
            downloader.start(config.getInt("downloader.threads"));
        } catch (Exception e) {
            System.err.println("[Downloader] Erro ao arrancar: " + e.getMessage());
            System.exit(1);
        }
    }
}
