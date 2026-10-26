package googol.common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * Interface remota implementada pelo <b>Cliente</b> (callback).
 * <p>
 * Aqui os papéis invertem-se: é a Gateway que invoca um método no Cliente
 * para lhe "empurrar" estatísticas novas assim que algo muda. Assim cumpre-se
 * o requisito "atualizada apenas quando houver alterações".
 */
public interface StatsCallback extends Remote {

    /** Chamado pela Gateway sempre que as estatísticas mudam. */
    void onStatsUpdate(SystemStats stats) throws RemoteException;
}
