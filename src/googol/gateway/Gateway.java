package googol.gateway;

import googol.common.BarrelInterface;
import googol.common.Config;
import googol.common.GatewayInterface;
import googol.common.Persistence;
import googol.common.QueueInterface;
import googol.common.SearchPage;
import googol.common.StatsCallback;
import googol.common.SystemStats;
import googol.common.Tokenizer;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RPC/RMI Gateway — a porta de entrada do Googol.
 * <p>
 * Tem três papéis:
 * <ol>
 *   <li><b>Para os Clientes</b> ({@link GatewayInterface}): reencaminha pesquisas
 *       e pedidos de backlinks para um Storage Barrel ativo (round-robin), com
 *       <b>failover</b> — se o Barrel escolhido falhar, tenta o seguinte.</li>
 *   <li><b>Para os Downloaders</b> ({@link QueueInterface}): guarda a fila de URLs.</li>
 *   <li><b>Para os Barrels</b>: mantém a lista de Barrels ativos (os Barrels
 *       registam-se aqui periodicamente).</li>
 * </ol>
 * A Gateway <b>não guarda o índice</b>. Guarda em disco apenas a fila de URLs,
 * os URLs já conhecidos e a contagem de pesquisas, para recuperar se avariar.
 * <p>
 * <b>Como correr:</b>
 * <pre>
 *   java -cp "out;lib\*" googol.gateway.Gateway [ficheiro.properties] [--reset]
 * </pre>
 */
public class Gateway extends UnicastRemoteObject implements GatewayInterface, QueueInterface {
    private static final long serialVersionUID = 1L;

    /** Uma chamada a um Barrel (usada pelo mecanismo de failover). */
    @FunctionalInterface
    private interface BarrelCall<T> {
        T call(BarrelInterface barrel) throws RemoteException;
    }

    /** Estado da Gateway que é gravado em disco. */
    private static class State implements Serializable {
        private static final long serialVersionUID = 1L;
        /** Fila de URLs por visitar. */
        final ArrayDeque<String> queue = new ArrayDeque<>();
        /** Todos os URLs que já entraram na fila (evita visitar a mesma página duas vezes). */
        final Set<String> known = new HashSet<>();
        /** Pesquisa (termos separados por espaço) -> número de vezes que foi feita. */
        final Map<String, Integer> searchCounts = new HashMap<>();
    }

    /** Acumulador do tempo de resposta de um Barrel. */
    private static class ResponseTime {
        private long totalNanos;
        private long count;

        synchronized void add(long nanos) {
            totalNanos += nanos;
            count++;
        }

        /** Média em décimas de segundo (1 décima = 100 000 000 nanossegundos). */
        synchronized double averageDeciseconds() {
            return count == 0 ? 0.0 : (double) totalNanos / count / 100_000_000.0;
        }
    }

    private final transient Path stateFile;
    private final transient State state;

    /** Barrels ativos: id -> referência remota. */
    private final transient Map<String, BarrelInterface> barrels = new ConcurrentHashMap<>();
    private final transient Map<String, ResponseTime> responseTimes = new ConcurrentHashMap<>();
    private final transient AtomicInteger roundRobin = new AtomicInteger();

    /** Clientes registados para receber estatísticas por callback. */
    private final transient CopyOnWriteArrayList<StatsCallback> subscribers = new CopyOnWriteArrayList<>();
    /** Últimas estatísticas enviadas (só usada pela thread do {@link #notifier}). */
    private transient SystemStats lastStats;
    /**
     * Thread única que calcula e envia as estatísticas. Assim, as pesquisas
     * dos clientes não ficam à espera dos callbacks.
     */
    private final transient ExecutorService notifier = Executors.newSingleThreadExecutor();
    private final transient ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

    /** Intervalo de gravação do estado usado quando não se indica outro. */
    public static final long DEFAULT_SAVE_INTERVAL_MS = 1000;

    /** O estado mudou desde a última gravação? (protegido pelo cadeado da Gateway) */
    private transient boolean dirty = false;
    /** Thread que grava o estado periodicamente (ver {@link #flushIfDirty()}). */
    private final transient ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gateway-save");
        t.setDaemon(true);
        return t;
    });

    /**
     * @param stateFile ficheiro onde a Gateway guarda o seu estado
     * @param reset     se {@code true}, apaga o estado anterior
     */
    public Gateway(Path stateFile, boolean reset) throws IOException {
        this(stateFile, reset, DEFAULT_SAVE_INTERVAL_MS);
    }

    /**
     * @param stateFile      ficheiro onde a Gateway guarda o seu estado
     * @param reset          se {@code true}, apaga o estado anterior
     * @param saveIntervalMs de quanto em quanto tempo o estado alterado é gravado
     */
    public Gateway(Path stateFile, boolean reset, long saveIntervalMs) throws IOException {
        super();
        this.stateFile = stateFile;
        if (reset) {
            Files.deleteIfExists(stateFile);
        }
        this.state = loadState();
        saver.scheduleWithFixedDelay(this::flushIfDirty, saveIntervalMs, saveIntervalMs, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Fila de URLs (QueueInterface + indexURL)
    // ------------------------------------------------------------------

    @Override
    public boolean indexURL(String url) throws RemoteException {
        String normalized = normalizeUrl(url);
        if (normalized == null) {
            throw new IllegalArgumentException("URL inválido (tem de começar por http:// ou https://): " + url);
        }
        synchronized (this) {
            if (!state.known.add(normalized)) {
                return false;
            }
            state.queue.addFirst(normalized); // pedidos do utilizador têm prioridade
            saveNow(); // um pedido do utilizador nunca se pode perder: grava já
        }
        System.out.println("[Gateway] Novo URL para indexar (pedido do utilizador): " + normalized);
        return true;
    }

    @Override
    public synchronized String takeURL() throws RemoteException {
        String url = state.queue.pollFirst();
        if (url != null) {
            markDirty();
        }
        return url;
    }

    @Override
    public synchronized void addURLs(List<String> urls) throws RemoteException {
        boolean changed = false;
        for (String url : urls) {
            String normalized = normalizeUrl(url);
            if (normalized != null && state.known.add(normalized)) {
                state.queue.addLast(normalized);
                changed = true;
            }
        }
        if (changed) {
            markDirty();
        }
    }

    /**
     * Normaliza um URL: tira espaços e a parte depois de '#' (que aponta para
     * uma secção da mesma página). Devolve {@code null} se não for http(s).
     */
    private static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        int hash = u.indexOf('#');
        if (hash >= 0) {
            u = u.substring(0, hash);
        }
        if (!(u.startsWith("http://") || u.startsWith("https://"))) {
            return null;
        }
        return u;
    }

    // ------------------------------------------------------------------
    // Registo de Barrels
    // ------------------------------------------------------------------

    @Override
    public void registerBarrel(String barrelId, BarrelInterface barrel) throws RemoteException {
        BarrelInterface previous = barrels.put(barrelId, barrel);
        if (previous == null || !previous.equals(barrel)) {
            System.out.println("[Gateway] Barrel registado: " + barrelId);
            requestStatsRefresh();
        }
    }

    @Override
    public Map<String, BarrelInterface> getBarrels() throws RemoteException {
        return new HashMap<>(barrels);
    }

    /** Retira um Barrel que falhou (só se ainda for a mesma referência). */
    private void removeBarrel(String barrelId, BarrelInterface barrel) {
        if (barrels.remove(barrelId, barrel)) {
            System.out.println("[Gateway] Barrel removido (não responde): " + barrelId);
            requestStatsRefresh();
        }
    }

    // ------------------------------------------------------------------
    // Pesquisa e backlinks (com balanceamento e failover)
    // ------------------------------------------------------------------

    @Override
    public SearchPage search(List<String> terms, int pageNumber) throws RemoteException {
        // Os termos são divididos com a mesma regra usada na indexação (Tokenizer):
        // "Coimbra!" -> "coimbra"; "rock-n-roll" -> "rock", "n", "roll".
        List<String> normalized = new ArrayList<>();
        for (String term : terms) {
            for (String t : Tokenizer.tokens(term)) {
                if (!normalized.contains(t)) {
                    normalized.add(t);
                }
            }
        }
        if (normalized.isEmpty()) {
            return new SearchPage(new ArrayList<>(), 0, 0);
        }
        // Só conta para as "pesquisas mais comuns" quando se pede a 1.ª página
        // (avançar nas páginas não é uma pesquisa nova).
        if (pageNumber == 0) {
            synchronized (this) {
                state.searchCounts.merge(String.join(" ", normalized), 1, Integer::sum);
                markDirty();
            }
        }
        SearchPage result = callBarrel(barrel -> barrel.search(normalized, pageNumber), true);
        requestStatsRefresh();
        return result;
    }

    @Override
    public List<String> getBacklinks(String url) throws RemoteException {
        return callBarrel(barrel -> barrel.getBacklinks(url), false);
    }

    /**
     * Executa {@code call} num Barrel ativo, escolhido por <b>round-robin</b>
     * (cada pedido começa no Barrel seguinte ao do pedido anterior).
     * <p>
     * <b>Failover:</b> se o Barrel lançar {@link RemoteException} (avariou),
     * é retirado da lista e o mesmo pedido é feito ao Barrel seguinte. O
     * cliente só recebe um erro se <b>todos</b> os Barrels falharem.
     *
     * @param measure se {@code true}, mede o tempo de resposta (para as estatísticas)
     */
    private <T> T callBarrel(BarrelCall<T> call, boolean measure) throws RemoteException {
        List<Map.Entry<String, BarrelInterface>> active = new ArrayList<>(barrels.entrySet());
        active.sort(Map.Entry.comparingByKey());
        if (active.isEmpty()) {
            throw new RemoteException("Nenhum Storage Barrel disponível de momento.");
        }
        int start = Math.floorMod(roundRobin.getAndIncrement(), active.size());
        for (int i = 0; i < active.size(); i++) {
            Map.Entry<String, BarrelInterface> entry = active.get((start + i) % active.size());
            long t0 = System.nanoTime();
            try {
                T result = call.call(entry.getValue());
                if (measure) {
                    responseTimes.computeIfAbsent(entry.getKey(), k -> new ResponseTime()).add(System.nanoTime() - t0);
                }
                return result;
            } catch (RemoteException e) {
                System.out.println("[Gateway] " + entry.getKey() + " falhou ("
                        + e.getClass().getSimpleName() + "). A tentar outro Barrel...");
                removeBarrel(entry.getKey(), entry.getValue());
            }
        }
        throw new RemoteException("Todos os Storage Barrels falharam.");
    }

    // ------------------------------------------------------------------
    // Estatísticas
    // ------------------------------------------------------------------

    @Override
    public SystemStats getStats() throws RemoteException {
        return buildStats();
    }

    /**
     * Regista um cliente para receber estatísticas. É <b>idempotente</b>: subscrever
     * duas vezes não duplica nada. Isto permite ao cliente voltar a subscrever
     * periodicamente — se a Gateway tiver reiniciado (e perdido a lista de
     * subscritores), a subscrição é refeita sozinha.
     */
    @Override
    public void subscribeStats(StatsCallback callback) throws RemoteException {
        if (subscribers.addIfAbsent(callback)) {
            // Novo subscritor: envia-lhe logo as estatísticas atuais.
            notifier.submit(() -> {
                SystemStats stats = lastStats != null ? lastStats : buildStats();
                sendStats(callback, stats);
            });
        }
    }

    @Override
    public void unsubscribeStats(StatsCallback callback) throws RemoteException {
        subscribers.remove(callback);
    }

    /** Pede (sem esperar) que as estatísticas sejam recalculadas e, se mudaram, enviadas. */
    private void requestStatsRefresh() {
        if (!notifier.isShutdown()) {
            notifier.submit(this::refreshAndPush);
        }
    }

    /**
     * Recalcula as estatísticas e envia-as aos subscritores <b>apenas se mudaram</b>
     * desde o último envio (requisito: sem refrescamento periódico).
     */
    private void refreshAndPush() {
        SystemStats stats = buildStats();
        if (stats.equals(lastStats)) {
            return;
        }
        lastStats = stats;
        for (StatsCallback callback : subscribers) {
            sendStats(callback, stats);
        }
    }

    private void sendStats(StatsCallback callback, SystemStats stats) {
        try {
            callback.onStatsUpdate(stats);
        } catch (RemoteException e) {
            subscribers.remove(callback); // o cliente já não existe
        }
    }

    /**
     * Constrói as estatísticas. Pergunta o tamanho do índice a cada Barrel;
     * os que não respondem são retirados da lista de ativos.
     */
    private SystemStats buildStats() {
        List<String> top;
        synchronized (this) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(state.searchCounts.entrySet());
            entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()));
            top = new ArrayList<>();
            for (int i = 0; i < Math.min(10, entries.size()); i++) {
                top.add(entries.get(i).getKey() + " (" + entries.get(i).getValue() + ")");
            }
        }
        Map<String, Integer> sizes = new TreeMap<>();
        Map<String, Double> times = new TreeMap<>();
        for (Map.Entry<String, BarrelInterface> entry : new ArrayList<>(barrels.entrySet())) {
            try {
                sizes.put(entry.getKey(), entry.getValue().getIndexSize());
                ResponseTime rt = responseTimes.get(entry.getKey());
                times.put(entry.getKey(), rt == null ? 0.0 : rt.averageDeciseconds());
            } catch (RemoteException e) {
                removeBarrel(entry.getKey(), entry.getValue());
            }
        }
        return new SystemStats(top, sizes, times);
    }

    /**
     * Arranca a verificação periódica dos Barrels (deteta Barrels que avariaram
     * e mudanças no tamanho dos índices). Os clientes só são avisados se algo mudar.
     */
    public void startMonitor(long intervalMs) {
        monitor.scheduleWithFixedDelay(this::requestStatsRefresh, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /** Grava o estado pendente, para as threads internas e retira a Gateway do RMI (usado nos testes). */
    public void shutdown() {
        saver.shutdownNow();
        flushIfDirty();
        monitor.shutdownNow();
        notifier.shutdownNow();
        try {
            UnicastRemoteObject.unexportObject(this, true);
        } catch (RemoteException e) {
            // já não estava exportada
        }
    }

    // ------------------------------------------------------------------
    // Persistência
    // ------------------------------------------------------------------

    /*
     * Gravação do estado.
     *
     * Gravar o ficheiro inteiro a cada takeURL/addURLs tornava a Gateway cada vez
     * mais lenta à medida que a fila crescia (180 ms por takeURL com 100 000 URLs
     * conhecidos, sempre com o cadeado da Gateway fechado). Por isso:
     *  - takeURL, addURLs e as pesquisas só MARCAM o estado como alterado;
     *  - uma thread grava-o de saveIntervalMs em saveIntervalMs, se tiver mudado;
     *  - um pedido de indexação do UTILIZADOR é gravado de imediato (nunca se perde).
     * Num crash perde-se no máximo o último intervalo: URLs já entregues voltam a
     * estar na fila (são indexados outra vez, sem efeito no índice, que é feito de
     * conjuntos) e alguns links descobertos nesse intervalo não chegam à fila.
     */

    /** Marca o estado como alterado (gravado pela thread periódica). Chamado com o cadeado da Gateway. */
    private void markDirty() {
        dirty = true;
    }

    /** Grava o estado já. Chamado com o cadeado da Gateway. */
    private void saveNow() {
        try {
            Persistence.save(state, stateFile);
            dirty = false;
        } catch (IOException e) {
            System.err.println("[Gateway] Aviso: não foi possível gravar o estado (" + e.getMessage() + ")");
        }
    }

    /** Grava o estado se tiver mudado desde a última gravação (thread periódica). */
    private synchronized void flushIfDirty() {
        if (dirty) {
            saveNow();
        }
    }

    private State loadState() {
        try {
            State loaded = Persistence.load(stateFile);
            return loaded != null ? loaded : new State();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Gateway] Aviso: não foi possível ler " + stateFile + " (" + e.getMessage()
                    + "). A começar com estado vazio.");
            return new State();
        }
    }

    /** Número de URLs na fila (para a mensagem de arranque e para os testes). */
    synchronized int queueSize() {
        return state.queue.size();
    }

    // ------------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        try {
            Config config = Config.load(args);
            boolean reset = Arrays.asList(args).contains("--reset");
            System.setProperty("java.rmi.server.hostname", config.get("rmi.hostname"));

            Gateway gateway = new Gateway(Path.of(config.get("gateway.state.file")), reset,
                    config.getInt("gateway.save.interval.ms", (int) DEFAULT_SAVE_INTERVAL_MS));
            int port = config.getInt("gateway.port");
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind(config.get("gateway.name"), gateway);
            gateway.startMonitor(config.getInt("gateway.monitor.interval.ms"));

            System.out.println("[Gateway] Pronta no porto " + port + " com o nome '" + config.get("gateway.name")
                    + "'. URLs na fila: " + gateway.queueSize() + (reset ? " (estado apagado com --reset)" : ""));
        } catch (Exception e) {
            System.err.println("[Gateway] Erro ao arrancar: " + e.getMessage());
            System.exit(1);
        }
    }
}
