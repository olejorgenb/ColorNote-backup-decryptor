import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Security;
import java.security.spec.InvalidKeySpecException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class ColorNoteBackupDecrypt {
	private SecretKeyFactory keyFactory;
	private PBEKeySpec pbeKeySpec;
	private SecretKeySpec keySpec;
	private Cipher chiper;

	public static void main(String[] args) throws Throwable {
		Security.addProvider(new BouncyCastleProvider());

		ColorNoteBackupDecrypt main = new ColorNoteBackupDecrypt();

		String defaultPassword = "0000";
		String password = defaultPassword;
		
		if (args.length > 0) {
			password = args[0];
		}

		// If 0 doesn't work, try with 28
		int offset = 0;

		if (args.length > 1) {
			offset = Integer.parseInt(args[1]);
		}

		main.init(password);
		main.decrypt(System.in, offset, System.out);
	}

	public void init(String password) throws UnsupportedEncodingException {
		byte[] salt = "ColorNote Fixed Salt".getBytes("UTF-8");
		String provider = "BC";
		String algorithm = "PBEWITHMD5AND128BITAES-CBC-OPENSSL";
		int iterationCount = 20;

		try {
			this.keyFactory = SecretKeyFactory.getInstance(algorithm, provider);
			this.pbeKeySpec = new PBEKeySpec(password.toCharArray(), salt, iterationCount);
			try {
				this.keySpec = new SecretKeySpec(
						this.keyFactory.generateSecret(this.pbeKeySpec).getEncoded(), algorithm);
			} catch (InvalidKeySpecException e) {
				e.printStackTrace();
			}
		} catch (NoSuchAlgorithmException | NoSuchProviderException e) {
			e.printStackTrace();
		}

		try {
			this.chiper = Cipher.getInstance(algorithm, provider);
		} catch (NoSuchAlgorithmException | NoSuchProviderException | NoSuchPaddingException e) {
			e.printStackTrace();
		}

		try {
			this.chiper.init(Cipher.DECRYPT_MODE, this.keySpec);
		} catch (InvalidKeyException e) {
			e.printStackTrace();
		}
	}

	public void decrypt(InputStream rawInput, int offset, OutputStream out) throws IOException {
		byte[] buffer = new byte[10240];

		int i = 0;
		while (i < offset) {
			rawInput.read();
			i++;
		}

		try (CipherInputStream input = new CipherInputStream(rawInput, this.chiper)) {
			while (true) {
				int bytesRead = input.read(buffer);
				if (bytesRead >= 0) {
					out.write(buffer, 0, bytesRead);
				} else {
					input.close();
					return;
				}
			}
		}
	}
}
