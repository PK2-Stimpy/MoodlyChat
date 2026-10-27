import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line tool to encrypt/decrypt files using SecureEncryption.
 *
 * Usage:
 *   java FileCryptoCLI encrypt <input> <output> <password>
 *   java FileCryptoCLI decrypt <input> <output> <password>
 */
public class FileCryptoCLI {
    public static void main(String[] args) {
        if (args.length != 4) {
            System.out.println("Usage:");
            System.out.println("  java FileCryptoCLI encrypt <input> <output> <password>");
            System.out.println("  java FileCryptoCLI decrypt <input> <output> <password>");
            System.exit(1);
        }

        String mode = args[0].toLowerCase();
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);
        String password = args[3];

        try {
            byte[] data = Files.readAllBytes(input);
            byte[] result;

            if (mode.equals("encrypt")) {
                result = SecureEncryption.encrypt(data, password);
                System.out.println("Encrypted " + data.length + " bytes -> " + result.length + " bytes");
            } else if (mode.equals("decrypt")) {
                result = SecureEncryption.decrypt(data, password);
                System.out.println("Decrypted " + data.length + " bytes -> " + result.length + " bytes");
            } else {
                System.out.println("Unknown mode: " + mode);
                System.exit(1);
                return;
            }

            Files.write(output, result);
            System.out.println("Wrote " + output);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(2);
        }
    }
}
