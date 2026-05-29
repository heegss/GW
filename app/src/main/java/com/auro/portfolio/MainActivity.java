package com.auro.portfolio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends Activity {

    private WebView webView;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setStatusBarColor(Color.parseColor("#080808"));
        getWindow().setNavigationBarColor(Color.parseColor("#080808"));

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);

        webView.setBackgroundColor(Color.parseColor("#080808"));
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "NativeBridge");
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    private class Bridge {

        // Called from JS: NativeBridge.fetchUrl(url)
        // Returns HTML string directly — no CORS issues in native Android
        @JavascriptInterface
        public String fetchUrl(String url) {
            try {
                if (!url.contains("igold.bg") && !url.contains("tavex.bg")) {
                    return error("Само igold.bg и tavex.bg са разрешени");
                }

                String referer = url.contains("igold.bg") ? "https://igold.bg/" : "https://tavex.bg/";

                Request req = new Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "bg-BG,bg;q=0.9,en;q=0.8")
                        .header("Referer", referer)
                        .build();

                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) return error("HTTP " + resp.code());
                    String html = resp.body() != null ? resp.body().string() : "";
                    if (html.length() < 200) return error("Празен отговор");

                    // Parse prices directly in Java for reliability
                    // Clean HTML tags
                    String clean = html.replaceAll("&nbsp;", " ").replaceAll("<[^>]+>", " ");

                    double sellEUR = 0, buyEUR = 0, sellBGN = 0, buyBGN = 0;

                    if (url.contains("igold.bg")) {
                        // Format: "Продаваме 7936.00 лв. / 4057.61 €"
                        Matcher mSell = Pattern.compile(
                            "(?i)продав\\S*\\s+([\\d\\s]+[.,]\\d{2})\\s*лв[^/]*/\\s*([\\d\\s]+[.,]\\d{2})\\s*€"
                        ).matcher(clean);
                        Matcher mBuy = Pattern.compile(
                            "(?i)купув\\S*\\s+([\\d\\s]+[.,]\\d{2})\\s*лв[^/]*/\\s*([\\d\\s]+[.,]\\d{2})\\s*€"
                        ).matcher(clean);

                        if (mSell.find()) {
                            sellBGN = parseNum(mSell.group(1));
                            sellEUR = parseNum(mSell.group(2));
                        }
                        if (mBuy.find()) {
                            buyBGN = parseNum(mBuy.group(1));
                            buyEUR = parseNum(mBuy.group(2));
                        }

                        // Fallback: meta tag
                        if (sellEUR <= 0) {
                            Matcher mMeta = Pattern.compile(
                                "product:price:amount[^>]+content=\"([\\d.]+)\""
                            ).matcher(html);
                            if (mMeta.find()) {
                                sellBGN = Double.parseDouble(mMeta.group(1));
                                sellEUR = Math.round(sellBGN / 1.95583 * 100.0) / 100.0;
                            }
                        }
                    } else {
                        // tavex: "Продаваме X,XX €" / "Купуваме X,XX €"
                        Matcher mSell = Pattern.compile(
                            "(?i)продав\\S*[^0-9]{0,30}([\\d\\s]+[.,]\\d{2})\\s*[€E]"
                        ).matcher(clean);
                        Matcher mBuy = Pattern.compile(
                            "(?i)купув\\S*[^0-9]{0,30}([\\d\\s]+[.,]\\d{2})\\s*[€E]"
                        ).matcher(clean);

                        if (mSell.find()) sellEUR = parseNum(mSell.group(1));
                        if (mBuy.find())  buyEUR  = parseNum(mBuy.group(1));
                        sellBGN = Math.round(sellEUR * 1.95583 * 100.0) / 100.0;
                        buyBGN  = Math.round(buyEUR  * 1.95583 * 100.0) / 100.0;
                    }

                    JSONObject j = new JSONObject();
                    j.put("sellEUR", sellEUR);
                    j.put("buyEUR",  buyEUR);
                    j.put("sellBGN", sellBGN);
                    j.put("buyBGN",  buyBGN);
                    j.put("ok", sellEUR > 0 || buyEUR > 0);
                    return j.toString();
                }
            } catch (Exception e) {
                return error(e.getMessage());
            }
        }

        @JavascriptInterface
        public boolean isAndroid() { return true; }

        private double parseNum(String s) {
            try {
                s = s.replaceAll("\\s", "");
                if (s.contains(",") && s.contains(".")) s = s.replace(".", "").replace(",", ".");
                else if (s.contains(",")) { int ci = s.lastIndexOf(','); s = (s.length()-ci==3) ? s.replace(",",".") : s.replace(",",""); }
                return Double.parseDouble(s);
            } catch (Exception e) { return 0; }
        }

        private String error(String msg) {
            try { JSONObject j = new JSONObject(); j.put("error", msg); j.put("ok", false); return j.toString(); }
            catch (Exception e) { return "{\"error\":\"err\",\"ok\":false}"; }
        }
    }
}
