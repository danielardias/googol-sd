package googol.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Divide texto em palavras, da <b>mesma forma</b> na indexação (Downloader) e
 * na pesquisa (Gateway).
 * <p>
 * Se as duas partes dividissem o texto de maneiras diferentes, uma pesquisa
 * por "Coimbra!" ou "rock-n-roll" não encontraria páginas que contêm essas
 * palavras. Regra: minúsculas, e tudo o que não é letra ou algarismo separa
 * palavras ({@code \p{L}} = qualquer letra, incluindo acentuadas;
 * {@code \p{N}} = qualquer algarismo).
 */
public final class Tokenizer {

    private Tokenizer() {
    }

    /** @return as palavras de {@code text}, em minúsculas, pela ordem em que aparecem (pode ter repetidas) */
    public static List<String> tokens(String text) {
        List<String> words = new ArrayList<>();
        if (text == null) {
            return words;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                words.add(token);
            }
        }
        return words;
    }
}
