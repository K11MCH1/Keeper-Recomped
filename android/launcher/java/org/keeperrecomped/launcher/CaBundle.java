package org.keeperrecomped.launcher;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Enumeration;

/**
 * The engine's libcurl (OpenSSL) finds no CA certificates on Android, so online
 * matchmaking failed its TLS check. Before each launch this writes the phone's
 * system CAs to one PEM file that the engine passes to libcurl (engine argument
 * --ca-bundle).
 */
final class CaBundle {
    private CaBundle() {
    }

    /** @return the PEM file, or null when the store cannot be read */
    static String write(Context c) {
        File out = new File(c.getFilesDir(), "cacerts.pem");
        File tmp = new File(c.getFilesDir(), "cacerts.pem.tmp");
        try {
            KeyStore store = KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            StringBuilder pem = new StringBuilder();
            int count = 0;
            for (Enumeration<String> aliases = store.aliases(); aliases.hasMoreElements(); ) {
                String alias = aliases.nextElement();
                // Like the app's own connections (targetSdk 24+): system CAs only, no user-added ones.
                if (!alias.startsWith("system:"))
                    continue;
                Certificate cert = store.getCertificate(alias);
                if (cert == null)
                    continue;
                pem.append("-----BEGIN CERTIFICATE-----\n")
                        .append(Base64.encodeToString(cert.getEncoded(), Base64.DEFAULT))
                        .append("-----END CERTIFICATE-----\n");
                count++;
            }
            if (count == 0)
                return null;
            try (FileOutputStream f = new FileOutputStream(tmp)) {
                f.write(pem.toString().getBytes(StandardCharsets.US_ASCII));
            }
            if (!tmp.renameTo(out))
                return null;
            return out.getAbsolutePath();
        } catch (Exception e) {
            AppLog.e("CA bundle export failed", e);
            tmp.delete();
            return null;
        }
    }
}
