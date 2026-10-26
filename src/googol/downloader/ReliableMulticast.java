package googol.downloader;

import googol.common.BarrelInterface;
import googol.common.IndexMessage;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Protocolo de <b>reliable multicast sobre RMI</b> (one-to-many) usado pelo
 * Downloader para enviar cada página a <b>todos</b> os Storage Barrels.
 *
 * <h2>Algoritmo</h2>
 * <ol>
 *   <li><b>Grupo de destino fixo.</b> O grupo é a lista de Barrels da
 *       configuração ({@code downloader.barrels}), mais qualquer outro Barrel
 *       que se registe na Gateway. Cada Barrel do grupo tem uma <b>fila de
 *       mensagens pendentes</b> neste Downloader.</li>
 *   <li><b>Envio.</b> Cada mensagem nova é posta no fim da fila de
 *       <b>cada</b> Barrel e tenta-se esvaziar a fila (por ordem, FIFO).</li>
 *   <li><b>ACK.</b> Uma mensagem só sai da fila quando {@code receive()}
 *       retorna sem erro — e o Barrel só retorna depois de gravar em disco.</li>
 *   <li><b>Falha.</b> Se {@code receive()} lançar {@link RemoteException}
 *       (avaria por omissão), a mensagem <b>fica</b> na fila desse Barrel; os
 *       outros Barrels não são afetados.</li>
 *   <li><b>Retransmissão.</b> Periodicamente ({@link #retryPending()}),
 *       pede-se à Gateway a lista atualizada de Barrels (um Barrel que
 *       reiniciou tem uma referência remota nova) e reenviam-se as pendentes.</li>
 *   <li><b>Duplicados.</b> Uma retransmissão pode chegar a um Barrel que já
 *       tinha a mensagem (ex.: o ACK perdeu-se). O Barrel deteta-o pelo par
 *       (id do Downloader, nº de sequência) e ignora-a.</li>
 * </ol>
 * Resultado: todos os Barrels acabam por receber todas as mensagens, pela
 * mesma ordem em que o Downloader as enviou, desde que voltem a ficar ativos.
 */
public class ReliableMulticast {

    /** Fonte da lista de Barrels ativos (na prática, a Gateway). */
    @FunctionalInterface
    public interface BarrelDirectory {
        Map<String, BarrelInterface> getBarrels() throws RemoteException, NotBoundException;
    }

    private final BarrelDirectory directory;
    /** Última referência remota conhecida de cada Barrel. */
    private final Map<String, BarrelInterface> stubs = new TreeMap<>();
    /** Mensagens ainda não confirmadas por cada Barrel do grupo. */
    private final Map<String, ArrayDeque<IndexMessage>> pending = new TreeMap<>();
    /** Barrels que falharam (para escrever o aviso só uma vez por avaria). */
    private final Set<String> failing = new HashSet<>();

    /**
     * @param directory       de onde vem a lista de Barrels ativos
     * @param expectedBarrels ids dos Barrels que fazem parte do grupo desde o início
     */
    public ReliableMulticast(BarrelDirectory directory, Collection<String> expectedBarrels) {
        this.directory = directory;
        for (String id : expectedBarrels) {
            pending.put(id, new ArrayDeque<>());
        }
    }

    /** Envia {@code message} a todos os Barrels do grupo (os que falharem ficam pendentes). */
    public synchronized void send(IndexMessage message) {
        refreshBarrels();
        for (Map.Entry<String, ArrayDeque<IndexMessage>> entry : pending.entrySet()) {
            entry.getValue().addLast(message);
            flush(entry.getKey());
        }
    }

    /** Tenta entregar as mensagens pendentes (chamado periodicamente). */
    public synchronized void retryPending() {
        if (totalPending() == 0) {
            return;
        }
        refreshBarrels();
        for (String id : pending.keySet()) {
            flush(id);
        }
    }

    /** Número de mensagens por confirmar para o Barrel {@code barrelId}. */
    public synchronized int pendingCount(String barrelId) {
        ArrayDeque<IndexMessage> queue = pending.get(barrelId);
        return queue == null ? 0 : queue.size();
    }

    /**
     * Resumo das mensagens por entregar, para mostrar no ecrã
     * (ex.: " | por entregar: barrel2=37"). Vazio se não houver nenhuma.
     */
    public synchronized String pendingSummary() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ArrayDeque<IndexMessage>> entry : pending.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                sb.append(sb.length() == 0 ? " | por entregar: " : ", ")
                        .append(entry.getKey()).append('=').append(entry.getValue().size());
            }
        }
        return sb.toString();
    }

    private int totalPending() {
        int total = 0;
        for (ArrayDeque<IndexMessage> queue : pending.values()) {
            total += queue.size();
        }
        return total;
    }

    /** Atualiza as referências dos Barrels. Se a Gateway falhar, usa as que já conhece. */
    private void refreshBarrels() {
        try {
            for (Map.Entry<String, BarrelInterface> entry : directory.getBarrels().entrySet()) {
                stubs.put(entry.getKey(), entry.getValue());
                pending.putIfAbsent(entry.getKey(), new ArrayDeque<>());
            }
        } catch (RemoteException | NotBoundException e) {
            // Gateway indisponível: continua com as referências já conhecidas.
        }
    }

    /** Entrega, por ordem, as mensagens pendentes de um Barrel até à primeira falha. */
    private void flush(String barrelId) {
        ArrayDeque<IndexMessage> queue = pending.get(barrelId);
        BarrelInterface barrel = stubs.get(barrelId);
        if (queue.isEmpty()) {
            return;
        }
        if (barrel == null) {
            warnOnce(barrelId, "ainda não está disponível");
            return;
        }
        int delivered = 0;
        while (!queue.isEmpty()) {
            try {
                barrel.receive(queue.peekFirst());
                queue.pollFirst(); // ACK recebido: pode sair da fila
                delivered++;
            } catch (RemoteException e) {
                stubs.remove(barrelId); // a referência pode estar morta; será pedida de novo à Gateway
                warnOnce(barrelId, "não respondeu");
                return;
            }
        }
        if (failing.remove(barrelId)) {
            System.out.println("[Multicast] " + barrelId + " voltou: entregues " + delivered + " mensagens pendentes.");
        }
    }

    private void warnOnce(String barrelId, String reason) {
        if (failing.add(barrelId)) {
            System.out.println("[Multicast] " + barrelId + " " + reason
                    + ". As mensagens ficam guardadas e serão reenviadas.");
        }
    }
}
