package googol.barrel;

import googol.common.IndexMessage;
import googol.common.PageData;
import googol.common.Persistence;
import googol.common.SearchPage;
import googol.common.SearchResult;

import java.io.IOException;
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

    private final Path file;
    private State state;

    /**
     * @param file  ficheiro onde o estado é guardado
     * @param reset se {@code true}, apaga os dados existentes (começar "limpo")
     */
    BarrelStorage(Path file, boolean reset) throws IOException {
        this.file = file;
        if (reset) {
            Files.deleteIfExists(file);
        }
        this.state = load();
    }

    /**
     * Aplica uma mensagem do multicast ao índice e grava o resultado em disco.
     * <p>
     * Só retorna depois de gravar. Por isso, quando o Downloader recebe a
     * resposta (o "ACK"), a página já está no disco: se o Barrel avariar logo
     * a seguir, não perde a página.
     *
     * @return {@code true} se a mensagem era nova; {@code false} se era um duplicado
     * @throws IOException se não for possível gravar (o Downloader deve reenviar)
     */
    synchronized boolean apply(IndexMessage message) throws IOException {
        Set<Long> seen = state.processed.computeIfAbsent(message.getDownloaderId(), k -> new HashSet<>());
        if (seen.contains(message.getSeqNumber())) {
            return false; // duplicado (retry de uma mensagem já recebida)
        }

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
        seen.add(message.getSeqNumber());

        try {
            save();
        } catch (IOException e) {
            // Não marcar como processada: assim o retry do Downloader volta a ser aceite.
            // (As alterações ao índice podem ficar em memória; não há problema, porque
            // são inserções em conjuntos e repeti-las não muda o resultado.)
            seen.remove(message.getSeqNumber());
            throw e;
        }
        return true;
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

    /** Grava o estado em disco (de forma segura contra crashes, ver {@link Persistence}). */
    private void save() throws IOException {
        Persistence.save(state, file);
    }

    /** Lê o estado do disco; se não existir (ou estiver corrompido), começa vazio. */
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
