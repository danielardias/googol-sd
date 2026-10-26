package googol.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface remota de um <b>Index Storage Barrel</b>.
 * <p>
 * Os Barrels são réplicas: todos devem ter exatamente a mesma informação.
 * Recebem dados dos Downloaders (via {@link #receive}) e respondem às
 * pesquisas que a Gateway lhes reencaminha.
 */
public interface BarrelInterface extends Remote {

    /**
     * Recebe uma página indexada por um Downloader (reliable multicast).
     * <p>
     * Se a mensagem ({@code downloaderId}, {@code seqNumber}) já tiver sido
     * processada, é ignorada (evita duplicados quando há retries).
     * Retornar normalmente funciona como <b>ACK</b>; uma
     * {@link RemoteException} significa que o Downloader tem de reenviar.
     */
    void receive(IndexMessage message) throws RemoteException;

    /** Pesquisa páginas com todos os termos, ordenadas por ligações recebidas. */
    SearchPage search(List<String> terms, int pageNumber) throws RemoteException;

    /** Lista de páginas com ligação para {@code url}. */
    List<String> getBacklinks(String url) throws RemoteException;

    /** Número de palavras no índice (para as estatísticas). */
    int getIndexSize() throws RemoteException;

    /** Usado pela Gateway para verificar se o Barrel está vivo. */
    boolean ping() throws RemoteException;
}
