package googol.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Gravação e leitura de objetos em ficheiro (usado pelos Barrels e pela Gateway).
 * <p>
 * A gravação é <b>segura contra crashes</b>: o objeto é escrito primeiro num
 * ficheiro temporário ({@code .tmp}) e só depois substitui o ficheiro
 * verdadeiro, de uma só vez. Se o programa avariar a meio da escrita, o
 * ficheiro antigo continua intacto — nunca fica meio escrito.
 */
public final class Persistence {

    private Persistence() {
    }

    /** Grava {@code object} em {@code file} (cria a pasta, se não existir). */
    public static void save(Serializable object, Path file) throws IOException {
        saveBytes(serialize(object), file);
    }

    /** Converte {@code object} em bytes (para poder ser gravado mais tarde, fora de um cadeado). */
    public static byte[] serialize(Serializable object) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(object);
        }
        return bytes.toByteArray();
    }

    /** Grava bytes já serializados em {@code file}, de forma segura contra crashes. */
    public static void saveBytes(byte[] data, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = Path.of(file.toString() + ".tmp");
        Files.write(tmp, data);
        // No Windows, o antivírus ou o indexador de pesquisa podem ter o ficheiro aberto
        // durante um instante e a troca falha: espera-se um pouco e tenta-se de novo.
        for (int tentativa = 1; ; tentativa++) {
            try {
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                if (tentativa >= MOVE_ATTEMPTS) {
                    throw e;
                }
                try {
                    Thread.sleep(50L * tentativa);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Número máximo de tentativas de substituir o ficheiro (ver {@link #save}). */
    private static final int MOVE_ATTEMPTS = 5;

    /**
     * Lê o objeto guardado em {@code file}.
     *
     * @return o objeto lido, ou {@code null} se o ficheiro não existir
     */
    @SuppressWarnings("unchecked")
    public static <T> T load(Path file) throws IOException, ClassNotFoundException {
        if (!Files.exists(file)) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(file))) {
            return (T) in.readObject();
        }
    }
}
