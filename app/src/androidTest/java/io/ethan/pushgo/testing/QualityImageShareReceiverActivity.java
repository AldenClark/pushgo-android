package io.ethan.pushgo.testing;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** A separate-UID recipient for the real Android share chooser in device tests. */
public final class QualityImageShareReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JSONObject result = new JSONObject();
        try {
            @SuppressWarnings("deprecation")
            Uri uri = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri == null) {
                throw new IllegalStateException("ACTION_SEND did not include EXTRA_STREAM");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int size = 0;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("The recipient could not open the shared image");
                }
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1; ) {
                    digest.update(buffer, 0, read);
                    size += read;
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value & 0xff));
            }
            result.put("size", size);
            result.put("sha256", hex.toString());
        } catch (Exception error) {
            try {
                result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            } catch (Exception ignored) {
                // Preserve the receiver's own process result even if JSON encoding fails.
            }
        }
        try {
            result.put("receiver_uid", Process.myUid());
            File pending = new File(getFilesDir(), "quality-image-share-result.json.pending");
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(result.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!pending.renameTo(new File(getFilesDir(), "quality-image-share-result.json"))) {
                throw new IllegalStateException("Could not publish the recipient result");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Could not write the recipient result", error);
        } finally {
            finish();
        }
    }
}
