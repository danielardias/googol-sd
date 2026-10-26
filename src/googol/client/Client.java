package googol.client;

import googol.common.Config;
import googol.common.GatewayInterface;
import googol.common.SearchPage;
import googol.common.StatsCallback;
import googol.common.SystemStats;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Cliente RMI do Googol: liga-se <b>apenas</b> à Gateway.
 * <p>
 * Esta classe contém a lógica de comunicação; a interface de texto (menu)
 * está em {@link ClientMenu}.
 * <p>
 * <b>Tolerância a falhas:</b> todas as chamadas passam por {@link #withRetry}.
 * Se a Gateway falhar a meio de uma operação, o cliente procura-a de novo no
 * registry e repete a operação (até {@code client.retry.attempts} vezes).
 * Assim nenhuma operação fica a meio por causa de uma avaria da Gateway.
 */
public class Client {

    /** Uma chamada à Gateway (para poder ser repetida). */
    @FunctionalInterface
    interface GatewayCall<T> {
        T call(GatewayInterface gateway) throws RemoteException;
    }

    /** Objeto remoto do cliente que recebe as estatísticas (callback). */
    private static class StatsReceiver extends UnicastRemoteObject implements StatsCallback {
        private static final long serialVersionUID = 1L;
        private transient volatile Consumer<SystemStats> listener;

        StatsReceiver() throws RemoteException {
            super();
        }

        @Override
        public void onStatsUpdate(SystemStats stats) {
            Consumer<SystemStats> l = listener;
            if (l != null) {
                l.accept(stats);
            }
        }
    }

    private final String gatewayHost;
    private final int gatewayPort;
    private final String gatewayName;
    private final int retryAttempts;
    private final long retryDelayMs;
    private final long resubscribeMs;

    private volatile GatewayInterface gateway;
    private StatsReceiver receiver;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "stats-resubscribe");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> resubscribeTask;
    private volatile boolean statsWarned = false;

    public Client(Config config) {
        this.gatewayHost = config.get("gateway.host");
        this.gatewayPort = config.getInt("gateway.port");
        this.gatewayName = config.get("gateway.name");
        this.retryAttempts = config.getInt("client.retry.attempts");
        this.retryDelayMs = config.getInt("client.retry.delay.ms");
        this.resubscribeMs = config.getInt("client.stats.resubscribe.ms");
    }

    // ------------------------------------------------------------------
    // Operações (todas com retry)
    // ------------------------------------------------------------------

    /** @return {@code true} se o URL foi aceite; {@code false} se já era conhecido */
    public boolean indexURL(String url) throws RemoteException {
        return withRetry(g -> g.indexURL(url));
    }

    public SearchPage search(List<String> terms, int pageNumber) throws RemoteException {
        return withRetry(g -> g.search(terms, pageNumber));
    }

    public List<String> getBacklinks(String url) throws RemoteException {
        return withRetry(g -> g.getBacklinks(url));
    }

    /**
     * Começa a receber estatísticas por callback; {@code listener} é chamado
     * sempre que a Gateway envia estatísticas novas (só quando mudam).
     */
    public synchronized void startStats(Consumer<SystemStats> listener) throws RemoteException {
        if (receiver == null) {
            receiver = new StatsReceiver();
        }
        receiver.listener = listener;
        statsWarned = false;
        // Subscreve já e volta a confirmar a subscrição periodicamente. A Gateway
        // ignora subscrições repetidas, por isso isto só tem efeito se ela tiver
        // reiniciado (e esquecido os subscritores).
        resubscribeTask = scheduler.scheduleWithFixedDelay(this::subscribeOnce, 0, resubscribeMs, TimeUnit.MILLISECONDS);
    }

    /** Deixa de receber estatísticas. */
    public synchronized void stopStats() {
        if (resubscribeTask != null) {
            resubscribeTask.cancel(false);
            resubscribeTask = null;
        }
        if (receiver != null) {
            receiver.listener = null;
            try {
                gateway().unsubscribeStats(receiver);
            } catch (RemoteException e) {
                // A Gateway não responde: não faz mal, ela retira-nos quando o callback falhar.
            }
        }
    }

    /** Liberta os recursos RMI do cliente (antes de sair). */
    public synchronized void close() {
        stopStats();
        scheduler.shutdownNow();
        if (receiver != null) {
            try {
                UnicastRemoteObject.unexportObject(receiver, true);
            } catch (RemoteException e) {
                // já não estava exportado
            }
        }
    }

    private void subscribeOnce() {
        try {
            gateway().subscribeStats(receiver);
            if (statsWarned) {
                System.out.println("\n[Ligação à Gateway restabelecida — estatísticas retomadas]");
            }
            statsWarned = false;
        } catch (RemoteException e) {
            gateway = null;
            if (!statsWarned) {
                statsWarned = true;
                System.out.println("\n[Gateway indisponível — as estatísticas serão retomadas quando voltar]");
            }
        }
    }

    // ------------------------------------------------------------------
    // Ligação à Gateway e retry
    // ------------------------------------------------------------------

    /**
     * Executa {@code call} na Gateway. Se falhar com {@link RemoteException},
     * volta a procurar a Gateway no registry e repete, até {@code retryAttempts} vezes.
     * Exceções de aplicação (ex.: {@link IllegalArgumentException} de um URL
     * inválido) não são repetidas.
     */
    <T> T withRetry(GatewayCall<T> call) throws RemoteException {
        RemoteException last = null;
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                return call.call(gateway());
            } catch (RemoteException e) {
                last = e;
                gateway = null; // a referência pode estar morta (Gateway reiniciou)
                if (attempt < retryAttempts) {
                    System.out.println("  [" + rootMessage(e) + " — nova tentativa " + (attempt + 1) + "/"
                            + retryAttempts + " em " + retryDelayMs + " ms]");
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    private GatewayInterface gateway() throws RemoteException {
        GatewayInterface g = gateway;
        if (g == null) {
            try {
                g = (GatewayInterface) LocateRegistry.getRegistry(gatewayHost, gatewayPort).lookup(gatewayName);
            } catch (NotBoundException e) {
                throw new RemoteException("A Gateway '" + gatewayName + "' ainda não está disponível");
            }
            gateway = g;
        }
        return g;
    }

    /** Mensagem da causa original de uma exceção (a mais útil para o utilizador). */
    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message != null ? message : t.getClass().getSimpleName();
    }
}
