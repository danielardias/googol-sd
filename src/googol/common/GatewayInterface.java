package googol.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface remota da Gateway <b>vista pelos Clientes</b>.
 * <p>
 * O Cliente só comunica com a Gateway (nunca diretamente com Barrels ou
 * Downloaders). A Gateway não guarda o índice: reencaminha cada pedido
 * para um Storage Barrel ativo e, se esse falhar, tenta outro (failover).
 */
public interface GatewayInterface extends Remote {

    /**
     * Funcionalidade 1 — pede a indexação de um URL introduzido pelo utilizador.
     * O URL é colocado no <b>início</b> da fila (tem prioridade sobre os URLs
     * encontrados automaticamente) e será visitado por um Downloader.
     *
     * @return {@code true} se o URL foi aceite; {@code false} se já era conhecido
     *         (já indexado ou já na fila)
     * @throws IllegalArgumentException se o URL não começar por http:// ou https://
     */
    boolean indexURL(String url) throws RemoteException;

    /**
     * Funcionalidades 3 e 4, com resultados de 10 em 10 — pesquisa páginas que
     * contenham <b>todos</b> os termos, ordenadas por número de ligações recebidas.
     *
     * @param terms      termos da pesquisa
     * @param pageNumber página de resultados pedida (0 = primeira)
     */
    SearchPage search(List<String> terms, int pageNumber) throws RemoteException;

    /** Funcionalidade 5 — lista de páginas que têm ligação para {@code url}. */
    List<String> getBacklinks(String url) throws RemoteException;

    /** Funcionalidade 6 — devolve as estatísticas atuais (pedido pontual). */
    SystemStats getStats() throws RemoteException;

    /**
     * Funcionalidade 6 — o Cliente regista-se para receber as estatísticas
     * por <b>callback</b> sempre que estas mudarem (sem refrescamento periódico).
     */
    void subscribeStats(StatsCallback callback) throws RemoteException;

    /** Cancela o registo feito com {@link #subscribeStats}. */
    void unsubscribeStats(StatsCallback callback) throws RemoteException;
}
