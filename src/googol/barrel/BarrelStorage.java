package googol.barrel;

import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.Persistence;
import googol.common.SearchPage;
import googol.common.SearchResult;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lógica de armazenamento de um Storage Barrel: o índice invertido, os
 * backlinks, a pesquisa e a persistência em disco.
 * <p>
 * Esta classe <b>não sabe nada de RMI</b>: a classe {@link Barrel} trata da
 * parte remota e delega aqui. Separar as duas coisas permite testar a lógica
 * sem rede (ver {@link BarrelTeste}).
 * <p>
 * <b>Concorrência:</b> todos os métodos públicos são {@code synchronized},
 * porque o RMI executa cada invocação remota numa thread própria (vários
 * Downloaders e a Gateway podem chamar o Barrel ao mesmo tempo).
 */
class BarrelStorage {

    /** Informação mostrada nos resultados de pesquisa para cada página. */
    private static class PageInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        final String title;
        final String citation;

        PageInfo(String title, String citation) {
            this.title = title;
            this.citation = citation;
        }
    }

    /** Todo o estado do Barrel; é este objeto que é gravado em disco. */
    private static class State implements Serializable {
        private static final long serialVersionUID = 1L;
        /** Índice invertido: palavra -> URLs das páginas onde ocorre. */
        final Map<String, Set<String>> index = new HashMap<>();
        /** Backlinks: URL -> URLs das páginas que têm ligação para ele. */
        final Map<String, Set<String>> backlinks = new HashMap<>();
        /** URL -> título e citação. */
        final Map<String, PageInfo> pages = new HashMap<>();
        /** Mensagens já processadas: id do Downloader -> números de sequência. */
        final Map<String, Set<Long>> processed = new HashMap<>();
    }

    /** Número de páginas recebidas entre duas gravações completas do índice. */
    static final int SNAPSHOT_EVERY = 200;
    /** Tamanho máximo aceitável de uma mensagem no registo (protege contra registos danificados). */
    private static final int MAX_MESSAGE_BYTES = 50 * 1024 * 1024;

    /** Fotografia completa do índice (gravada de {@value #SNAPSHOT_EVERY} em {@value #SNAPSHOT_EVERY} páginas). */
    private final Path file;
    /** Registo das páginas recebidas desde a última fotografia (uma escrita pequena por página). */
    private final Path logFile;
    private State state;
    private int messagesSinceSnapshot = 0;

    /**
     * @param file  ficheiro onde o estado é guardado (o registo fica em {@code file + ".log"})
     * @param reset se {@code true}, apaga os dados existentes (começar "limpo")
     */
    BarrelStorage(Path file, boolean reset) throws IOException {
        this.file = file;
        this.logFile = Path.of(file.toString() + ".log");
        if (reset) {
            Files.deleteIfExists(file);
            Files.deleteIfExists(logFile);
        }
        this.state = load();
        int replayed = replayLog();
        if (replayed > 0) {
            snapshot(); // junta o que estava no registo à fotografia e esvazia o registo
        }
    }

    /**
     * Aplica uma mensagem do multicast ao índice e grava-a em disco.
     * <p>
     * <b>Persistência com registo (log):</b> em vez de gravar o índice inteiro
     * a cada página (cada vez mais lento à medida que o índice cresce), a
     * mensagem é <b>acrescentada ao fim</b> do ficheiro de registo — uma escrita
     * pequena e de duração constante. De {@value #SNAPSHOT_EVERY} em
     * {@value #SNAPSHOT_EVERY} páginas grava-se o índice completo e o registo
     * é esvaziado.
     * <p>
     * Só retorna depois de a mensagem estar no disco. Por isso, quando o
     * Downloader recebe a resposta (o "ACK"), a página já não se perde, mesmo
     * que o Barrel avarie logo a seguir.
     *
     * @return {@code true} se a mensagem era nova; {@code false} se era um duplicado
     * @throws IOException se não for possível gravar (o Downloader deve reenviar)
     */
    synchronized boolean apply(IndexMessage message) throws IOException {
        if (isDuplicate(message)) {
            return false; // duplicado (retry de uma mensagem já recebida)
        }
        appendToLog(message); // 1.º grava no disco; se falhar, nada muda e o Downloader reenvia
        applyInMemory(message);
        messagesSinceSnapshot++;
        if (messagesSinceSnapshot >= SNAPSHOT_EVERY) {
            snapshot();
        }
        return true;
    }

    private boolean isDuplicate(IndexMessage message) {
        Set<Long> seen = state.processed.get(message.getDownloaderId());
        return seen != null && seen.contains(message.getSeqNumber());
    }

    /** Atualiza o índice, os backlinks e a lista de mensagens processadas (só em memória). */
    private void applyInMemory(IndexMessage message) {
        PageData page = message.getPage();
        String url = page.getUrl();
        state.pages.put(url, new PageInfo(page.getTitle(), page.getCitation()));
        for (String word : page.getWords()) {
            state.index.computeIfAbsent(word.toLowerCase(), k -> new HashSet<>()).add(url);
        }
        for (String link : page.getLinks()) {
            if (!link.equals(url)) { // uma página a apontar para si própria não conta
                state.backlinks.computeIfAbsent(link, k -> new HashSet<>()).add(url);
            }
        }
        state.processed.computeIfAbsent(message.getDownloaderId(), k -> new HashSet<>()).add(message.getSeqNumber());
    }

    /**
     * Acrescenta a mensagem ao fim do registo: primeiro o tamanho (4 bytes),
     * depois a mensagem serializada. O {@code sync()} obriga o sistema operativo
     * a escrever mesmo no disco antes de continuar.
     */
    private void appendToLog(IndexMessage message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(message);
        }
        Path parent = logFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (FileOutputStream fos = new FileOutputStream(logFile.toFile(), true);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
            out.writeInt(bytes.size());
            bytes.writeTo(out);
            out.flush();
            fos.getFD().sync();
        }
    }

    /**
     * Ao arrancar: volta a aplicar as mensagens do registo (as que chegaram depois
     * da última fotografia). Se o Barrel avariou a meio de uma escrita, o último
     * registo está incompleto e é ignorado — essa mensagem nunca foi confirmada
     * ao Downloader, por isso ele vai reenviá-la.
     *
     * @return número de mensagens aplicadas
     */
    private int replayLog() {
        if (!Files.exists(logFile)) {
            return 0;
        }
        int count = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(logFile)))) {
            while (true) {
                int size = in.readInt();
                if (size <= 0 || size > MAX_MESSAGE_BYTES) {
                    throw new IOException("tamanho de registo inválido: " + size);
                }
                byte[] data = new byte[size];
                in.readFully(data);
                try (ObjectInputStream obj = new ObjectInputStream(new ByteArrayInputStream(data))) {
                    IndexMessage message = (IndexMessage) obj.readObject();
                    if (!isDuplicate(message)) {
                        applyInMemory(message);
                        count++;
                    }
                }
            }
        } catch (EOFException e) {
            // fim do registo (ou último registo incompleto): normal
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Barrel] Aviso: registo " + logFile + " danificado a partir de certo ponto ("
                    + e.getClass().getSimpleName() + "). Recuperadas " + count + " mensagens.");
        }
        return count;
    }

    /**
     * Grava a fotografia completa do índice e esvazia o registo.
     * <p>
     * Se o Barrel avariar entre as duas operações, ao arrancar volta a aplicar
     * um registo cujas mensagens já estão na fotografia; como são detetadas como
     * duplicadas, são ignoradas — o resultado fica correto na mesma.
     */
    private void snapshot() throws IOException {
        Persistence.save(state, file);
        Files.deleteIfExists(logFile);
        messagesSinceSnapshot = 0;
    }

    /**
     * Pesquisa as páginas que contêm <b>todos</b> os termos, ordenadas por número
     * de ligações recebidas (mais ligações primeiro; em caso de empate, por URL).
     *
     * @param pageNumber página de resultados (0 = primeira), com 10 resultados cada
     */
    synchronized SearchPage search(List<String> terms, int pageNumber) {
        List<String> normalized = new ArrayList<>();
        for (String term : terms) {
            String t = term.toLowerCase().trim();
            if (!t.isEmpty() && !normalized.contains(t)) {
                normalized.add(t);
            }
        }
        if (normalized.isEmpty() || pageNumber < 0) {
            return new SearchPage(new ArrayList<>(), Math.max(pageNumber, 0), 0);
        }

        // Interseção: começa com as páginas do 1.º termo e vai retirando as que
        // não contêm cada um dos termos seguintes.
        Set<String> matches = null;
        for (String term : normalized) {
            Set<String> urls = state.index.getOrDefault(term, Set.of());
            if (matches == null) {
                matches = new HashSet<>(urls);
            } else {
                matches.retainAll(urls);
            }
            if (matches.isEmpty()) {
                break;
            }
        }

        List<String> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparingInt((String u) -> backlinkCount(u)).reversed()
                .thenComparing(Comparator.<String>naturalOrder()));

        int from = pageNumber * SearchPage.PAGE_SIZE;
        int to = Math.min(from + SearchPage.PAGE_SIZE, sorted.size());
        List<SearchResult> results = new ArrayList<>();
        for (int i = from; i < to; i++) {
            String url = sorted.get(i);
            PageInfo info = state.pages.get(url);
            results.add(new SearchResult(url, info.title, info.citation, backlinkCount(url)));
        }
        return new SearchPage(results, pageNumber, sorted.size());
    }

    /** Lista (ordenada) das páginas que têm ligação para {@code url}. */
    synchronized List<String> getBacklinks(String url) {
        List<String> list = new ArrayList<>(state.backlinks.getOrDefault(url, Set.of()));
        list.sort(null);
        return list;
    }

    /** Número de palavras distintas no índice. */
    synchronized int getIndexSize() {
        return state.index.size();
    }

    /** Número de páginas indexadas. */
    synchronized int getPageCount() {
        return state.pages.size();
    }

    private int backlinkCount(String url) {
        return state.backlinks.getOrDefault(url, Set.of()).size();
    }

    /** Lê a fotografia do disco; se não existir (ou estiver corrompida), começa vazio. */
    private State load() {
        try {
            State loaded = Persistence.load(file);
            return loaded != null ? loaded : new State();
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[Barrel] Aviso: não foi possível ler " + file + " (" + e.getMessage()
                    + "). A começar com índice vazio.");
            return new State();
        }
    }
}
