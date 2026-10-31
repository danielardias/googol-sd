package googol.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lê o ficheiro de configuração ({@code .properties}).
 * <p>
 * Todos os programas (Barrel, Downloader, Gateway, Cliente) usam esta classe,
 * para que IPs, portas e nomes nunca fiquem escritos no código. Assim, mudar
 * de máquina não obriga a recompilar (requisito obrigatório da checklist).
 */
public final class Config {

    /** Caminho usado quando não se indica outro na linha de comandos. */
    public static final String DEFAULT_PATH = "config/config.properties";

    private final Properties props = new Properties();

    private Config(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
    }

    /**
     * Carrega a configuração. Se algum argumento terminar em ".properties",
     * é usado esse ficheiro; caso contrário usa-se {@link #DEFAULT_PATH}.
     */
    public static Config load(String[] args) throws IOException {
        String path = DEFAULT_PATH;
        for (String arg : args) {
            if (arg.endsWith(".properties")) {
                path = arg;
            }
        }
        return new Config(Path.of(path));
    }

    /** Devolve o valor de uma chave; falha com mensagem clara se não existir. */
    public String get(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Falta a chave '" + key + "' no ficheiro de configuração");
        }
        return value.trim();
    }

    /** Igual a {@link #get(String)}, mas converte para inteiro. */
    public int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    /**
     * Igual a {@link #getInt(String)}, mas devolve {@code defaultValue} se a chave
     * não existir (para chaves opcionais, acrescentadas depois dos ficheiros de cada máquina).
     */
    public int getInt(String key, int defaultValue) {
        String value = props.getProperty(key);
        return value == null ? defaultValue : Integer.parseInt(value.trim());
    }
}
