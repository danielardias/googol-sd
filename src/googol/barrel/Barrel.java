package googol.barrel;

import googol.common.BarrelInterface;
import googol.common.Config;
import googol.common.IndexMessage;
import googol.common.QueueInterface;
import googol.common.SearchPage;

import java.io.IOException;
import java.nio.file.Path;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.List;

/**
 * Index Storage Barrel — o servidor (replicado) que guarda o índice.
 * <p>
 * Esta classe trata da parte <b>RMI</b>: recebe as invocações remotas e
 * delega a lógica em {@link BarrelStorage}.
 * <p>
 * <b>Como correr:</b>
 * <pre>
 *   java -cp "out;lib\*" googol.barrel.Barrel [ficheiro.properties] [--reset]
 * </pre>
 * {@code --reset} apaga os dados guardados (para começar a demonstração limpa).
 * <p>
 * <b>Registo na Gateway:</b> o Barrel anuncia-se à Gateway e repete o anúncio
 * periodicamente. Assim, se a Gateway avariar e reiniciar, volta a conhecer
 * os Barrels sozinha, sem ser preciso reiniciar nada.
 */
public class Barrel extends UnicastRemoteObject implements BarrelInterface {
    private static final long serialVersionUID = 1L;

    private final String id;
    private final transient BarrelStorage storage;

    protected Barrel(String id, BarrelStorage storage) throws RemoteException {
        super();
        this.id = id;
        this.storage = storage;
    }

    /**
     * Cria (e exporta para RMI) um Barrel que guarda os dados em {@code dataFile}.
     *
     * @param reset se {@code true}, apaga os dados existentes
     */
    public static Barrel create(String id, Path dataFile, boolean reset) throws IOException {
        return new Barrel(id, new BarrelStorage(dataFile, reset));
    }

    @Override
    public void receive(IndexMessage message) throws RemoteException {
        try {
            boolean isNew = storage.apply(message);
            if (isNew) {
                System.out.println("[" + id + "] Indexado: " + message.getPage().getUrl()
                        + " (" + message.getDownloaderId() + " #" + message.getSeqNumber() + ")");
            }
        } catch (IOException e) {
            // Sem ACK: o Downloader vai reenviar a mensagem.
            throw new RemoteException("Barrel " + id + " não conseguiu gravar o índice em disco", e);
        }
    }

    @Override
    public SearchPage search(List<String> terms, int pageNumber) throws RemoteException {
        return storage.search(terms, pageNumber);
    }

    @Override
    public List<String> getBacklinks(String url) throws RemoteException {
        return storage.getBacklinks(url);
    }

    @Override
    public int getIndexSize() throws RemoteException {
        return storage.getIndexSize();
    }

    @Override
    public boolean ping() throws RemoteException {
        return true;
    }

    public static void main(String[] args) {
        try {
            Config config = Config.load(args);
            boolean reset = Arrays.asList(args).contains("--reset");

            // Tem de ser definido ANTES de criar o objeto remoto: é este IP que vai
            // dentro da referência remota que os outros programas recebem.
            System.setProperty("java.rmi.server.hostname", config.get("rmi.hostname"));

            String id = config.get("barrel.id");
            Barrel barrel = create(id, Path.of(config.get("barrel.data.file")), reset);
            System.out.println("[" + id + "] Pronto. " + barrel.storage.getPageCount() + " páginas e "
                    + barrel.storage.getIndexSize() + " palavras carregadas do disco."
                    + (reset ? " (dados apagados com --reset)" : ""));

            registerLoop(barrel, id, config);
        } catch (Exception e) {
            System.err.println("[Barrel] Erro ao arrancar: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Regista o Barrel na Gateway e repete o registo a cada
     * {@code barrel.register.interval.ms} milissegundos (nunca termina).
     */
    private static void registerLoop(Barrel barrel, String id, Config config) throws InterruptedException {
        String host = config.get("gateway.host");
        int port = config.getInt("gateway.port");
        String name = config.get("gateway.name");
        long interval = config.getInt("barrel.register.interval.ms");
        boolean registered = false;

        while (true) {
            try {
                Registry registry = LocateRegistry.getRegistry(host, port);
                QueueInterface gateway = (QueueInterface) registry.lookup(name);
                gateway.registerBarrel(id, barrel);
                if (!registered) {
                    System.out.println("[" + id + "] Registado na Gateway (" + host + ":" + port + ").");
                    registered = true;
                }
            } catch (RemoteException | NotBoundException e) {
                if (registered) {
                    System.out.println("[" + id + "] Perdi a ligação à Gateway. A tentar novamente...");
                } else {
                    System.out.println("[" + id + "] Gateway indisponível. Nova tentativa em " + interval + " ms...");
                }
                registered = false;
            }
            Thread.sleep(interval);
        }
    }
}
