package googol.common;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

/**
 * Interface remota da Gateway <b>vista pelos Downloaders e pelos Barrels</b>.
 * <p>
 * Decisão de arquitetura: a fila de URLs fica <b>dentro da Gateway</b>
 * (o enunciado permite-o). Assim, na defesa, a máquina #1 corre apenas
 * downloader + barrel + gateway, como pede a checklist, sem um 4.º programa.
 * <p>
 * A Gateway funciona também como "lista telefónica" dos Barrels: cada Barrel
 * regista-se aqui ao arrancar, e os Downloaders pedem a lista para saberem
 * a quem enviar o multicast.
 */
public interface QueueInterface extends Remote {

    /**
     * Retira o próximo URL da fila. A operação é atómica, por isso cada URL
     * é entregue a <b>um só</b> Downloader (requisito da checklist).
     *
     * @return o próximo URL, ou {@code null} se a fila estiver vazia
     */
    String takeURL() throws RemoteException;

    /** Junta à fila os URLs encontrados numa página (os já visitados são ignorados). */
    void addURLs(List<String> urls) throws RemoteException;

    /** Um Barrel anuncia-se à Gateway ao arrancar (ou ao recuperar de um crash). */
    void registerBarrel(String barrelId, BarrelInterface barrel) throws RemoteException;

    /** Devolve os Barrels registados (id -> referência remota). */
    Map<String, BarrelInterface> getBarrels() throws RemoteException;
}
