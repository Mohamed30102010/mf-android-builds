package app.mfstudio.webview;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Scanner;

/**
 * The generated shell: shows the configured site (or a bundled offline copy)
 * full screen, keeps same-site navigation inside the app, opens other links in
 * the browser, reports load failures instead of showing a blank screen, and
 * hands the hardware back button to the page history.
 *
 * Every value it uses comes from app/src/main/assets/studio.json, which the
 * studio rewrites before each build.
 */
public class MainActivity extends Activity {

    private static final String LOCAL_HOME = "file:///android_asset/www/index.html";

    private WebView web;
    private ProgressBar bar;
    private FrameLayout root;
    private View errorView;
    private TextView errorBody;

    private String startHost = "";
    private String pendingUrl = "";
    private boolean failed = false;
    private int brandColor = Color.parseColor("#7C5CFF");

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        JSONObject cfg = readConfig();
        brandColor = parseColor(cfg.optString("primaryColor", "#7C5CFF"), brandColor);

        buildViews();
        getWindow().setStatusBarColor(brandColor);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                bar.setProgress(progress);
                bar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!failed) hideError();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Uri uri = request.getUrl();
                    showError(uri != null ? uri.toString() : view.getUrl());
                }
            }
        });

        loadHome(cfg);
    }

    // ----------------------------------------------------------------- views

    private void buildViews() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setDisplayZoomControls(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(false);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setBackgroundColor(Color.WHITE);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        barParams.gravity = Gravity.TOP;
        root.addView(bar, barParams);

        errorView = buildErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private View buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        int pad = dp(28);
        box.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(getString(R.string.error_title));
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#0F172A"));
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        errorBody = new TextView(this);
        errorBody.setTextSize(13f);
        errorBody.setTextColor(Color.parseColor("#475569"));
        errorBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(12);
        box.addView(errorBody, bodyParams);

        Button retry = new Button(this);
        retry.setText(getString(R.string.error_retry));
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reload();
            }
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        retryParams.topMargin = dp(18);
        box.addView(retry, retryParams);

        Button browser = new Button(this);
        browser.setText(getString(R.string.error_browser));
        browser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!pendingUrl.isEmpty()) openExternally(Uri.parse(pendingUrl));
            }
        });
        LinearLayout.LayoutParams browserParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        browserParams.topMargin = dp(8);
        box.addView(browser, browserParams);

        return box;
    }

    // ------------------------------------------------------------------ load

    private void loadHome(JSONObject cfg) {
        failed = false;
        String url = cfg.optString("sourceUrl", "").trim();

        if (url.isEmpty()) {
            // No remote address: use the site bundled from an uploaded ZIP.
            startHost = "";
            pendingUrl = LOCAL_HOME;
            web.loadUrl(LOCAL_HOME);
            return;
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        Uri uri = Uri.parse(url);
        startHost = uri.getHost() == null ? "" : uri.getHost();
        pendingUrl = url;
        web.loadUrl(url);
    }

    private void reload() {
        hideError();
        if (pendingUrl.isEmpty()) {
            loadHome(readConfig());
        } else {
            web.loadUrl(pendingUrl);
        }
    }

    /**
     * Same-site http(s) links stay inside the app so navigation works like a
     * native app. Anything else (other domains, dialer, mail, maps) is handed
     * to the system instead of loading it in an opaque window.
     */
    private boolean handleUri(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        if (scheme.equals("http") || scheme.equals("https")) {
            String host = uri.getHost() == null ? "" : uri.getHost();
            if (startHost.isEmpty() || host.equals(startHost) || host.endsWith("." + startHost)) {
                return false;
            }
            openExternally(uri);
            return true;
        }

        if (scheme.equals("mailto") || scheme.equals("tel") || scheme.equals("sms")
                || scheme.equals("geo") || scheme.equals("market") || scheme.equals("whatsapp")
                || scheme.equals("intent")) {
            openExternally(uri);
            return true;
        }

        // Unknown or dangerous schemes (file:, javascript:, data:) never leave
        // the WebView: block them rather than handing them to another app.
        return true;
    }

    private void openExternally(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
            // No app able to handle it — stay on the current page.
        }
    }

    // ------------------------------------------------------------ error view

    private void showError(String url) {
        failed = true;
        pendingUrl = url == null ? pendingUrl : url;
        if (errorBody != null) {
            errorBody.setText(getString(R.string.error_body, pendingUrl));
        }
        if (errorView != null) errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        failed = false;
        if (errorView != null) errorView.setVisibility(View.GONE);
    }

    // --------------------------------------------------------------- helpers

    private JSONObject readConfig() {
        try (InputStream in = getAssets().open("studio.json")) {
            Scanner scanner = new Scanner(in, "UTF-8").useDelimiter("\\A");
            String raw = scanner.hasNext() ? scanner.next() : "{}";
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ life cycle

    @Override
    public void onBackPressed() {
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) {
            hideError();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
