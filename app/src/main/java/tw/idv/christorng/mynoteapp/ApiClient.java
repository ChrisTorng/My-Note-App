package tw.idv.christorng.mynoteapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Native HTTP client for the existing MyNote Worker; never opens a WebView. */
final class ApiClient {
    static final String ORIGIN = "https://mynote.christorng.workers.dev";
    private static final String KEY_ALIAS = "MyNoteAppSessionKey";
    private static final Pattern COOKIE = Pattern.compile("(?:^|;\\s*)mynote_session=([A-Za-z0-9_-]*)");
    private final SharedPreferences prefs;
    private final Context context;

    ApiClient(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    String deviceId() { return prefs.getString("deviceId", ""); }
    String deviceName() { return prefs.getString("deviceName", "Android"); }
    void setDevice(JSONObject device) {
        prefs.edit().putString("deviceId", device.optString("deviceId", ""))
                .putString("deviceName", device.optString("name", "Android")).apply();
    }
    void changeName(String value) { prefs.edit().putString("deviceName", value).apply(); }
    boolean hasSession() { return !session().isEmpty(); }
    int remoteVersion() { return prefs.getInt("remoteVersion", 0); }
    void setRemoteVersion(int version) { prefs.edit().putInt("remoteVersion", version).apply(); }

    JSONObject login(String code, String name) throws Exception {
        JSONObject body = new JSONObject().put("code", code).put("name", name)
                .put("knownDeviceId", deviceId());
        JSONObject result = request("/login", "POST", body);
        setDevice(result);
        setRemoteVersion(0);
        return result;
    }

    JSONObject request(String path, String method, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ORIGIN + "/api" + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        String token = session();
        if (!token.isEmpty()) c.setRequestProperty("Cookie", "mynote_session=" + token);
        if (!"GET".equals(method)) c.setRequestProperty("Origin", ORIGIN);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        try {
            if (body != null) {
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            String setCookie = c.getHeaderField("Set-Cookie");
            if (setCookie != null) {
                Matcher match = COOKIE.matcher(setCookie);
                if (match.find() && !match.group(1).equals(token)) storeSession(match.group(1));
            }
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readLimited(stream);
            JSONObject data = text.isEmpty() ? new JSONObject() : new JSONObject(text);
            if (status >= 400) throw new ApiException(status, data.optString("error", "HTTP " + status));
            return data;
        } finally { c.disconnect(); }
    }

    private static String readLimited(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (b.size() + n > 24 * 1024 * 1024) throw new JSONException("Response too large");
                b.write(buffer, 0, n);
            }
            return b.toString(StandardCharsets.UTF_8.name());
        }
    }

    void clearSession() { prefs.edit().remove("session").apply(); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private void storeSession(String token) throws Exception {
        if (token.isEmpty()) { clearSession(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.US_ASCII));
        byte[] iv = cipher.getIV();
        prefs.edit().putString("session", Base64.encodeToString(iv, Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply();
    }

    private String session() {
        String packed = prefs.getString("session", "");
        if (packed.isEmpty()) return "";
        try {
            String[] parts = packed.split("\\.", 2);
            if (parts.length != 2) return "";
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.US_ASCII);
        } catch (Exception e) {
            clearSession();
            return "";
        }
    }

    static final class ApiException extends Exception {
        final int code;
        ApiException(int code, String message) { super(message); this.code = code; }
    }
}
