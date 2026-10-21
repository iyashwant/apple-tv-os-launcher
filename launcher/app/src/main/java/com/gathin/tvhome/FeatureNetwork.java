package com.gathin.tvhome;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Only public calendar and city lookups; no accounts, analytics or background location. */
final class FeatureNetwork {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    static JSONObject get(String address) throws Exception {
        URL url = new URL(address);
        if (!url.getProtocol().equals("https") || !(url.getHost().equals("api.jolpi.ca")
                || url.getHost().equals("geocoding-api.open-meteo.com"))) throw new SecurityException("Unsupported data source");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8000); connection.setReadTimeout(8000);
        connection.setRequestProperty("User-Agent", "CleanYourTV");
        try {
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Data source unavailable");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 512 * 1024) throw new java.io.IOException("Response too large");
                    out.write(buffer, 0, n);
                }
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally { connection.disconnect(); }
    }
}
