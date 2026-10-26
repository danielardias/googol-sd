package googol.common;

import java.io.Serializable;

/**
 * Mensagem do protocolo de <b>reliable multicast</b> enviada por um
 * Downloader a todos os Storage Barrels.
 * <p>
 * O par ({@code downloaderId}, {@code seqNumber}) identifica a mensagem de
 * forma única. Isto permite:
 * <ul>
 *   <li>ao Downloader <b>reenviar</b> (retry) a mesma mensagem a um Barrel
 *       que falhou, sem medo de a duplicar;</li>
 *   <li>ao Barrel <b>ignorar duplicados</b> (se já processou aquele
 *       número de sequência daquele Downloader, não volta a indexar).</li>
 * </ul>
 */
public class IndexMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Identificador do Downloader que enviou (ex.: "downloader1"). */
    private final String downloaderId;
    /** Número de sequência, crescente por Downloader (1, 2, 3, ...). */
    private final long seqNumber;
    /** Conteúdo da página indexada. */
    private final PageData page;

    public IndexMessage(String downloaderId, long seqNumber, PageData page) {
        this.downloaderId = downloaderId;
        this.seqNumber = seqNumber;
        this.page = page;
    }

    public String getDownloaderId() { return downloaderId; }
    public long getSeqNumber() { return seqNumber; }
    public PageData getPage() { return page; }
}
