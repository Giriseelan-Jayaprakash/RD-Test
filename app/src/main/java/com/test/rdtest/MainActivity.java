package com.test.rdtest;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple tester for IDEMIA MSO 1300 E3 L1 RD Service (package com.idemia.l1rdservice).
 * Calls DEVICE INFO and CAPTURE exactly as described in the IDEMIA
 * "RD Services Solution for Android - L1 Integration Document", sections 6.1 - 6.4.
 */
public class MainActivity extends Activity {

    private static final String RD_PACKAGE = "com.idemia.l1rdservice";
    private static final String ACTION_INFO = "in.gov.uidai.rdservice.fp.INFO";
    private static final String ACTION_CAPTURE = "in.gov.uidai.rdservice.fp.CAPTURE";
    private static final int REQ_INFO = 101;
    private static final int REQ_CAPTURE = 102;

    private Spinner envSpinner, fTypeSpinner;
    private EditText fCountEt, timeoutEt;
    private TextView statsTv, summaryTv, rawTv;

    private long captureStartMs;
    private int infoOk, infoFail, capOk, capFail;
    private final StringBuilder log = new StringBuilder();
    private String lastRaw = "";

    private static final Map<String, String> ERRORS = new HashMap<>();
    static {
        ERRORS.put("100", "Invalid PidOptions input");
        ERRORS.put("110", "Invalid value for fType");
        ERRORS.put("120", "Invalid value for fCount");
        ERRORS.put("130", "Invalid value for iType");
        ERRORS.put("140", "Invalid value for iCount");
        ERRORS.put("150", "Invalid value for pidVer");
        ERRORS.put("160", "Invalid value for timeout");
        ERRORS.put("170", "Invalid value for posh");
        ERRORS.put("180", "Face matching is not supported");
        ERRORS.put("190", "Invalid value for format");
        ERRORS.put("200", "Invalid Demo structure");
        ERRORS.put("210", "Protobuf format not supported");
        ERRORS.put("240", "UIDAI certificate from management server is invalid");
        ERRORS.put("510", "Device FW does not support this feature");
        ERRORS.put("700", "Capture timed out");
        ERRORS.put("710", "Being used by another application");
        ERRORS.put("720", "Device not ready");
        ERRORS.put("730", "Capture failed");
        ERRORS.put("740", "Device needs to be re-initialized (environment changed)");
        ERRORS.put("750", "RD Service does not support fingerprints");
        ERRORS.put("760", "RD Service does not support Iris");
        ERRORS.put("770", "Invalid URL");
        ERRORS.put("991", "RD Service security compromised");
        ERRORS.put("992", "RD Service security compromised");
        ERRORS.put("999", "Internal error (check date/time auto, env value, refresh RD Service)");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
    }

    // ---------------------------------------------------------------- UI

    private View buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("IDEMIA L1 RD Service Tester");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Package: " + RD_PACKAGE);
        sub.setPadding(0, 0, 0, dp(12));
        root.addView(sub);

        root.addView(label("Environment (must match RD Service setting)"));
        envSpinner = spinner(new String[]{"PP - Pre-Production", "P - Production", "S - Staging"});
        root.addView(envSpinner);

        root.addView(label("Finger type (fType)"));
        fTypeSpinner = spinner(new String[]{"2 - FMR + FIR", "0 - FMR", "1 - FIR"});
        root.addView(fTypeSpinner);

        root.addView(label("Finger count (fCount)"));
        fCountEt = numberField("1");
        root.addView(fCountEt);

        root.addView(label("Capture timeout (ms)"));
        timeoutEt = numberField("10000");
        root.addView(timeoutEt);

        LinearLayout row1 = row();
        row1.addView(button("Device Info", v -> callDeviceInfo()));
        row1.addView(button("Capture", v -> callCapture()));
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(button("Copy result", v -> copyResult()));
        row2.addView(button("Save log", v -> saveLog()));
        row2.addView(button("Reset", v -> resetAll()));
        root.addView(row2);

        statsTv = new TextView(this);
        statsTv.setPadding(0, dp(12), 0, dp(4));
        statsTv.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statsTv);
        updateStats();

        summaryTv = new TextView(this);
        summaryTv.setTextSize(15);
        summaryTv.setTextIsSelectable(true);
        summaryTv.setPadding(0, dp(8), 0, dp(8));
        summaryTv.setText("Tap Device Info first. Status must be READY before Capture.");
        root.addView(summaryTv);

        root.addView(label("Raw response"));
        rawTv = new TextView(this);
        rawTv.setTypeface(Typeface.MONOSPACE);
        rawTv.setTextSize(11);
        rawTv.setTextIsSelectable(true);
        root.addView(rawTv);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);

        // Keep content clear of status/navigation bars (Android 15 edge-to-edge)
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        return scroll;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, dp(10), 0, dp(2));
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private Spinner spinner(String[] items) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        return sp;
    }

    private EditText numberField(String value) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setText(value);
        return et;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        return r;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------- RD calls

    private void callDeviceInfo() {
        Intent intent = new Intent(ACTION_INFO);
        intent.setPackage(RD_PACKAGE);
        try {
            startActivityForResult(intent, REQ_INFO);
        } catch (ActivityNotFoundException e) {
            show("FAIL: MSO 1300 E3 L1 RD Service app is not installed.", "");
        }
    }

    private void callCapture() {
        String env = firstToken(envSpinner);
        String fType = firstToken(fTypeSpinner);
        String fCount = fCountEt.getText().toString().trim();
        String timeout = timeoutEt.getText().toString().trim();

        if ((fType.equals("1") || fType.equals("2")) && !(fCount.equals("1") || fCount.equals("2"))) {
            Toast.makeText(this, "For fType 1 or 2, fCount must be 1 or 2", Toast.LENGTH_LONG).show();
            return;
        }

        String pidOptions = "<PidOptions ver=\"1.0\">"
                + "<Opts env=\"" + env + "\" fCount=\"" + fCount + "\" fType=\"" + fType + "\""
                + " format=\"0\" pidVer=\"2.0\" timeout=\"" + timeout + "\""
                + " otp=\"\" wadh=\"\" posh=\"UNKNOWN\"/>"
                + "<Demo></Demo><CustOpts></CustOpts>"
                + "</PidOptions>";

        Intent intent = new Intent(ACTION_CAPTURE);
        intent.setPackage(RD_PACKAGE);
        intent.putExtra("PID_OPTIONS", pidOptions);
        try {
            captureStartMs = SystemClock.elapsedRealtime();
            startActivityForResult(intent, REQ_CAPTURE);
        } catch (ActivityNotFoundException e) {
            show("FAIL: MSO 1300 E3 L1 RD Service app is not installed.", "");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Bundle b = (data != null) ? data.getExtras() : null;

        if (b == null) {
            String msg = "FAIL: No data returned (resultCode=" + resultCode
                    + "). Capture may have been cancelled or the RD app closed.";
            if (requestCode == REQ_INFO) infoFail++; else capFail++;
            show(msg, "");
            return;
        }

        StringBuilder extras = new StringBuilder("--- All returned keys ---\n");
        for (String k : b.keySet()) extras.append(k).append("\n");

        if (requestCode == REQ_INFO) handleInfo(b, extras.toString());
        else if (requestCode == REQ_CAPTURE) handleCapture(b, extras.toString());
    }

    private void handleInfo(Bundle b, String extras) {
        String di = safe(b.getString("DEVICE_INFO", ""));
        String rs = safe(b.getString("RD_SERVICE_INFO", ""));

        String status = attr(rs, "status");
        String dpId = attr(di, "dpId");
        String rdsId = attr(di, "rdsId");
        String rdsVer = attr(di, "rdsVer");
        String dc = attr(di, "dc");
        String mi = attr(di, "mi");
        String mc = attr(di, "mc");

        boolean pass = "READY".equalsIgnoreCase(status)
                && !dpId.isEmpty() && !rdsId.isEmpty() && !rdsVer.isEmpty()
                && !dc.isEmpty() && !mi.isEmpty() && !mc.isEmpty();
        if (pass) infoOk++; else infoFail++;

        String summary = (pass ? "PASS" : "FAIL") + " - DEVICE INFO\n"
                + "RD status: " + orDash(status) + "\n"
                + "RD info: " + orDash(attr(rs, "info")) + "\n"
                + "dpId: " + orDash(dpId) + "\n"
                + "rdsId: " + orDash(rdsId) + "\n"
                + "rdsVer: " + orDash(rdsVer) + "\n"
                + "mi (model): " + orDash(mi) + "\n"
                + "dc present: " + yesNo(!dc.isEmpty()) + "\n"
                + "mc (certificate) present: " + yesNo(!mc.isEmpty()) + "\n"
                + "Serial no: " + orDash(param(di, "serial_number")) + "\n"
                + "Device type: " + orDash(param(di, "device_type"));

        String raw = "RD_SERVICE_INFO:\n" + rs + "\n\nDEVICE_INFO:\n" + di + "\n\n" + extras;
        show(summary, raw);
    }

    private void handleCapture(Bundle b, String extras) {
        long ms = SystemClock.elapsedRealtime() - captureStartMs;
        String pid = safe(b.getString("PID_DATA", ""));
        String dnc = safe(b.getString("DNC", ""));
        String dnr = safe(b.getString("DNR", ""));

        String summary;
        if (!dnc.isEmpty()) {
            capFail++;
            summary = "FAIL - CAPTURE\nDNC: Device not connected (" + dnc + ")";
        } else if (!dnr.isEmpty()) {
            capFail++;
            summary = "FAIL - CAPTURE\nDNR: Device not registered (" + dnr + ")";
        } else {
            String err = attr(pid, "errCode");
            boolean hasSkey = !tagContent(pid, "Skey").isEmpty();
            boolean hasHmac = !tagContent(pid, "Hmac").isEmpty();
            boolean hasData = !tagContent(pid, "Data").isEmpty();
            boolean pass = "0".equals(err) && hasSkey && hasHmac && hasData;
            if (pass) capOk++; else capFail++;

            String meaning = ERRORS.containsKey(err) ? " (" + ERRORS.get(err) + ")" : "";
            summary = (pass ? "PASS" : "FAIL") + " - CAPTURE in " + ms + " ms\n"
                    + "errCode: " + orDash(err) + meaning + "\n"
                    + "errInfo: " + orDash(attr(pid, "errInfo")) + "\n"
                    + "fCount: " + orDash(attr(pid, "fCount"))
                    + "   fType: " + orDash(attr(pid, "fType")) + "\n"
                    + "nmPoints (minutiae): " + orDash(attr(pid, "nmPoints")) + "\n"
                    + "Skey present: " + yesNo(hasSkey)
                    + "   ci: " + orDash(attr(pid, "ci")) + "\n"
                    + "Hmac present: " + yesNo(hasHmac) + "\n"
                    + "Data present: " + yesNo(hasData)
                    + " (" + tagContent(pid, "Data").length() + " chars)\n"
                    + "PID_DATA size: " + pid.length() + " chars";
        }
        String raw = "PID_DATA:\n" + pid + "\n\nDNC: " + dnc + "\nDNR: " + dnr + "\n\n" + extras;
        show(summary, raw);
    }

    // ----------------------------------------------------------- helpers

    private void show(String summary, String raw) {
        String time = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(new java.util.Date());
        summaryTv.setText(time + "\n" + summary);
        rawTv.setText(raw);
        lastRaw = raw;
        log.append("[").append(time).append("]\n").append(summary).append("\n\n");
        updateStats();
    }

    private void updateStats() {
        statsTv.setText("Device Info  OK: " + infoOk + "  Fail: " + infoFail
                + "     Capture  OK: " + capOk + "  Fail: " + capFail);
    }

    private void copyResult() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("RD result",
                summaryTv.getText() + "\n\n" + lastRaw));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void saveLog() {
        try {
            File dir = getExternalFilesDir(null);
            String name = "rd_test_log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new java.util.Date()) + ".txt";
            File f = new File(dir, name);
            try (FileWriter w = new FileWriter(f)) {
                w.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL
                        + "  Android " + Build.VERSION.RELEASE + "\n");
                w.write(statsTv.getText() + "\n\n");
                w.write(log.toString());
            }
            Toast.makeText(this, "Saved: " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetAll() {
        infoOk = infoFail = capOk = capFail = 0;
        log.setLength(0);
        lastRaw = "";
        summaryTv.setText("Counters reset.");
        rawTv.setText("");
        updateStats();
    }

    private static String firstToken(Spinner sp) {
        String s = sp.getSelectedItem().toString();
        int i = s.indexOf(' ');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String attr(String xml, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String param(String xml, String name) {
        Matcher m = Pattern.compile("name\\s*=\\s*\"" + name + "\\s*\"\\s+value\\s*=\\s*\"([^\"]*)\"")
                .matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String tagContent(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*>([^<]*)<", Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String orDash(String s) { return (s == null || s.isEmpty()) ? "-" : s; }
    private static String yesNo(boolean b) { return b ? "Yes" : "No"; }
}
