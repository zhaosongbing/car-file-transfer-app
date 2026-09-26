package com.zsb.carfiletransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;

import com.zsb.carfiletransfer.miuix.MiuixButton;
import com.zsb.carfiletransfer.miuix.MiuixCard;
import com.zsb.carfiletransfer.miuix.MiuixDialog;
import com.zsb.carfiletransfer.miuix.MiuixDivider;
import com.zsb.carfiletransfer.miuix.MiuixListItem;
import com.zsb.carfiletransfer.miuix.MiuixQrView;
import com.zsb.carfiletransfer.miuix.MiuixSwitch;
import com.zsb.carfiletransfer.miuix.MiuixTabRow;
import com.zsb.carfiletransfer.miuix.MiuixText;
import com.zsb.carfiletransfer.miuix.MiuixTextField;
import com.zsb.carfiletransfer.miuix.MiuixTheme;
import com.zsb.carfiletransfer.miuix.MiuixTopAppBar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.List;

/**
 * Car-unit side of the transfer app. The whole UI is built from the MIUIX
 * component set; the WebView is gone.
 *
 * Tabs: 传输 (QR + link state) / 文件 (received files) / 设置 (ADB + hotspot).
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int PORT = 8899;
    private static final int REQ_PERM = 1001;
    private static final long ADB_POLL_MS = 3000L;

    private FileRepository repo;
    private SoftApManager softAp;
    private HttpFileServer server;
    private SoftApManager.ApConfig apConfig;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean serviceOn = false;
    private boolean hotspotOn = false;
    /** -1 = not chosen yet, 0 = phone joins the car hotspot, 1 = same Wi-Fi. */
    private int connectMode = -1;
    /** 0 = hotspot join code, 1 = transfer URL. */
    private int qrMode = 0;
    private String lastError = "";

    // ---- widgets ----
    private MiuixButton btnAdbChip;
    private MiuixTabRow tabRow;
    private final View[] pages = new View[3];

    private MiuixQrView qrView;
    private MiuixText qrPlaceholder;
    private MiuixText qrTitle;
    private MiuixText qrSub;
    private MiuixText qrPayload;
    private MiuixButton segHotspot;
    private MiuixButton segUrl;
    private MiuixButton btnConnect;
    private MiuixButton btnStop;

    private MiuixText txtServer;
    private MiuixText txtAddr;
    private MiuixText txtAp;

    private LinearLayout fileList;
    private MiuixText fileSummary;
    private MiuixButton btnClean;

    private MiuixText adbDetail;
    private MiuixText apDetail;
    private MiuixText aboutDetail;
    private MiuixSwitch swAutoHotspot;

    private final Runnable adbPoll = new Runnable() {
        public void run() {
            refreshAdb();
            ui.postDelayed(this, ADB_POLL_MS);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repo = new FileRepository(this);
        softAp = new SoftApManager(this);
        prefs = getSharedPreferences("carft", MODE_PRIVATE);
        apConfig = softAp.readSystemConfig();
        if (apConfig == null) apConfig = loadSavedAp();
        setContentView(buildUi());
        requestRuntimePermissions();
        refreshAll();
        showConnectDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(adbPoll);
        ui.post(adbPoll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(adbPoll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    private void requestRuntimePermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.NEARBY_WIFI_DEVICES};
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        requestPermissions(perms, REQ_PERM);
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MiuixTheme.colors().background);
        int pad = MiuixTheme.dp(this, MiuixTheme.SPACE_SCREEN);
        root.setPadding(pad, pad, pad, pad);

        MiuixTopAppBar bar = new MiuixTopAppBar(this, "文件互传", "车机 · " + deviceName());
        btnAdbChip = new MiuixButton(this, "ADB", MiuixButton.Size.SMALL,
                MiuixButton.Color.NEUTRAL);
        btnAdbChip.setFilled(false);
        btnAdbChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refreshAdb();
            }
        });
        bar.addAction(btnAdbChip);
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tabRow = new MiuixTabRow(this, new String[]{"传输", "文件", "设置"}, 0);
        tabRow.setOnTabSelectedListener(new MiuixTabRow.OnTabSelectedListener() {
            public void onTabSelected(int index, String title) {
                showPage(index);
            }
        });
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabLp.topMargin = MiuixTheme.dp(this, 8f);
        root.addView(tabRow, tabLp);

        FrameLayout host = new FrameLayout(this);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hostLp.topMargin = MiuixTheme.dp(this, 8f);
        host.setLayoutParams(hostLp);

        View p0 = wrap(buildTransferPage());
        View p1 = wrap(buildFilesPage());
        View p2 = wrap(buildSettingsPage());
        pages[0] = p0;
        pages[1] = p1;
        pages[2] = p2;
        host.addView(p0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(p1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.addView(p2, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p1.setVisibility(View.GONE);
        p2.setVisibility(View.GONE);
        root.addView(host, hostLp);

        return root;
    }

    private ScrollView wrap(View content) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private void showPage(int index) {
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        if (index == 1) renderFiles();
    }

    // ---- page 1: transfer ----

    private View buildTransferPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        MiuixCard qrCard = new MiuixCard(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        // segmented control: hotspot code vs transfer address
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setGravity(Gravity.CENTER);
        segHotspot = segButton("热点连接码");
        segUrl = segButton("传输地址码");
        seg.addView(segHotspot);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                MiuixTheme.dp(this, 8f), ViewGroup.LayoutParams.WRAP_CONTENT);
        seg.addView(new View(this), gap);
        seg.addView(segUrl);
        qrCard.addView(seg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // QR plate with an "not connected" placeholder on top
        FrameLayout qrBox = new FrameLayout(this);
        int qrSize = MiuixTheme.dp(this, 260f);
        qrView = new MiuixQrView(this, 260f);
        qrBox.addView(qrView, new FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER));
        qrPlaceholder = new MiuixText(this, "未连接", MiuixText.Role.SUBTITLE,
                MiuixText.Tone.TERTIARY);
        qrPlaceholder.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(qrSize, qrSize, Gravity.CENTER);
        qrBox.addView(qrPlaceholder, plp);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, qrSize);
        boxLp.topMargin = MiuixTheme.dp(this, 16f);
        qrCard.addView(qrBox, boxLp);

        qrTitle = new MiuixText(this, "等待连接", MiuixText.Role.SUBTITLE);
        qrTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = MiuixTheme.dp(this, 14f);
        qrCard.addView(qrTitle, tLp);

        qrSub = new MiuixText(this, "选择连接方式后将生成二维码",
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        qrSub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = MiuixTheme.dp(this, 4f);
        qrCard.addView(qrSub, sLp);

        qrPayload = new MiuixText(this, "", MiuixText.Role.BODY_SMALL, MiuixText.Tone.BRAND);
        qrPayload.setGravity(Gravity.CENTER);
        qrPayload.setSingleLine(false);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = MiuixTheme.dp(this, 10f);
        qrCard.addView(qrPayload, pLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        btnConnect = new MiuixButton(this, "选择连接方式", MiuixButton.Size.MEDIUM,
                MiuixButton.Color.PRIMARY);
        btnStop = new MiuixButton(this, "停止传输", MiuixButton.Size.MEDIUM,
                MiuixButton.Color.NEUTRAL);
        btnStop.setEnabled(false);
        actions.addView(btnConnect);
        LinearLayout.LayoutParams g2 = new LinearLayout.LayoutParams(
                MiuixTheme.dp(this, 12f), ViewGroup.LayoutParams.WRAP_CONTENT);
        actions.addView(new View(this), g2);
        actions.addView(btnStop);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aLp.topMargin = MiuixTheme.dp(this, 18f);
        qrCard.addView(actions, aLp);

        btnConnect.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showConnectDialog();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                stopAll();
                refreshAll();
            }
        });
        segHotspot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                qrMode = 0;
                refreshQr();
            }
        });
        segUrl.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                qrMode = 1;
                refreshQr();
            }
        });

        page.addView(qrCard, cardLp);

        // status card
        MiuixCard statusCard = new MiuixCard(this);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.topMargin = MiuixTheme.dp(this, MiuixTheme.SPACE_CARD);
        statusCard.addView(sectionTitle("连接状态"));
        statusCard.addView(new MiuixDivider(this));
        txtServer = kvValue();
        statusCard.addView(kvRow("传输服务", txtServer));
        txtAddr = kvValue();
        statusCard.addView(kvRow("传输地址", txtAddr));
        txtAp = kvValue();
        statusCard.addView(kvRow("车机热点", txtAp));
        page.addView(statusCard, scLp);

        return page;
    }

    private MiuixButton segButton(String text) {
        MiuixButton b = new MiuixButton(this, text, MiuixButton.Size.SMALL,
                MiuixButton.Color.NEUTRAL);
        b.setFilled(false);
        return b;
    }

    private MiuixText sectionTitle(String text) {
        MiuixText t = new MiuixText(this, text, MiuixText.Role.SUBTITLE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = MiuixTheme.dp(this, 10f);
        t.setLayoutParams(lp);
        return t;
    }

    private MiuixText kvValue() {
        MiuixText v = new MiuixText(this, "—", MiuixText.Role.BODY_SMALL);
        v.setGravity(Gravity.END);
        return v;
    }

    private View kvRow(String label, MiuixText value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int vpad = MiuixTheme.dp(this, 10f);
        row.setPadding(0, vpad, 0, vpad);

        MiuixText l = new MiuixText(this, label, MiuixText.Role.BODY_SMALL,
                MiuixText.Tone.TERTIARY);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    // ---- page 2: files ----

    private View buildFilesPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        MiuixCard card = new MiuixCard(this);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        fileSummary = new MiuixText(this, "暂无文件", MiuixText.Role.BODY_SMALL,
                MiuixText.Tone.TERTIARY);
        head.addView(fileSummary, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnClean = new MiuixButton(this, "一键清理", MiuixButton.Size.SMALL,
                MiuixButton.Color.DANGER);
        btnClean.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmClean();
            }
        });
        head.addView(btnClean, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(new MiuixDivider(this));

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams flLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flLp.topMargin = MiuixTheme.dp(this, 8f);
        card.addView(fileList, flLp);

        page.addView(card, cardLp);
        return page;
    }

    // ---- page 3: settings ----

    private View buildSettingsPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int cardGap = MiuixTheme.dp(this, MiuixTheme.SPACE_CARD);

        // ADB
        MiuixCard adbCard = new MiuixCard(this);
        adbCard.addView(sectionTitle("ADB 调试"));
        adbCard.addView(new MiuixDivider(this));
        adbDetail = new MiuixText(this, "读取中…", MiuixText.Role.BODY_SMALL,
                MiuixText.Tone.SECONDARY);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = MiuixTheme.dp(this, 10f);
        adbCard.addView(adbDetail, dLp);

        LinearLayout adbActions = new LinearLayout(this);
        adbActions.setOrientation(LinearLayout.HORIZONTAL);
        adbActions.setGravity(Gravity.END);
        MiuixButton btnRefresh = new MiuixButton(this, "刷新", MiuixButton.Size.SMALL,
                MiuixButton.Color.NEUTRAL);
        btnRefresh.setFilled(false);
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refreshAdb();
            }
        });
        MiuixButton btnTcp = new MiuixButton(this, "开启网络调试 5555", MiuixButton.Size.SMALL,
                MiuixButton.Color.PRIMARY);
        btnTcp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                enableAdbTcp();
            }
        });
        adbActions.addView(btnRefresh);
        adbActions.addView(new View(this), new LinearLayout.LayoutParams(
                MiuixTheme.dp(this, 8f), ViewGroup.LayoutParams.WRAP_CONTENT));
        adbActions.addView(btnTcp);
        LinearLayout.LayoutParams aaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aaLp.topMargin = MiuixTheme.dp(this, 14f);
        adbCard.addView(adbActions, aaLp);
        page.addView(adbCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // hotspot
        MiuixCard apCard = new MiuixCard(this);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apLp.topMargin = cardGap;
        apCard.addView(sectionTitle("车机热点"));
        apCard.addView(new MiuixDivider(this));
        apDetail = new MiuixText(this, "读取中…", MiuixText.Role.BODY_SMALL,
                MiuixText.Tone.SECONDARY);
        LinearLayout.LayoutParams apdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apdLp.topMargin = MiuixTheme.dp(this, 10f);
        apCard.addView(apDetail, apdLp);

        LinearLayout apRow = new LinearLayout(this);
        apRow.setOrientation(LinearLayout.HORIZONTAL);
        apRow.setGravity(Gravity.CENTER_VERTICAL);
        MiuixText swLabel = new MiuixText(this, "选择连接方式时自动开启本车热点",
                MiuixText.Role.BODY_SMALL);
        apRow.addView(swLabel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        swAutoHotspot = new MiuixSwitch(this);
        swAutoHotspot.setChecked(true, false);
        apRow.addView(swAutoHotspot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.topMargin = MiuixTheme.dp(this, 12f);
        apCard.addView(apRow, swLp);

        LinearLayout apActions = new LinearLayout(this);
        apActions.setOrientation(LinearLayout.HORIZONTAL);
        MiuixButton btnReread = new MiuixButton(this, "重新读取", MiuixButton.Size.SMALL,
                MiuixButton.Color.NEUTRAL);
        btnReread.setFilled(false);
        btnReread.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                SoftApManager.ApConfig sys = softAp.readSystemConfig();
                apConfig = sys != null ? sys : loadSavedAp();
                if (apConfig == null) toast("未读取到车机热点配置，可手动指定");
                refreshApDetail();
                refreshStatus();
                refreshQr();
            }
        });
        MiuixButton btnManual = new MiuixButton(this, "手动指定账号密码", MiuixButton.Size.SMALL,
                MiuixButton.Color.NEUTRAL);
        btnManual.setFilled(false);
        btnManual.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showApEditor();
            }
        });
        apActions.addView(btnReread, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        apActions.addView(new View(this), new LinearLayout.LayoutParams(
                MiuixTheme.dp(this, 8f), ViewGroup.LayoutParams.WRAP_CONTENT));
        apActions.addView(btnManual, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = MiuixTheme.dp(this, 14f);
        apCard.addView(apActions, brLp);
        page.addView(apCard, apLp);

        // about
        MiuixCard aboutCard = new MiuixCard(this);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.topMargin = cardGap;
        aboutCard.addView(sectionTitle("关于"));
        aboutCard.addView(new MiuixDivider(this));
        aboutDetail = new MiuixText(this, "", MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        LinearLayout.LayoutParams abdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abdLp.topMargin = MiuixTheme.dp(this, 10f);
        aboutCard.addView(aboutDetail, abdLp);
        page.addView(aboutCard, abLp);

        return page;
    }

    // ------------------------------------------------------------------ refresh

    private void refreshAll() {
        refreshStatus();
        refreshQr();
        refreshApDetail();
        refreshAdb();
        aboutDetail.setText("设备 " + deviceName() + "\nAndroid " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")\n接收目录 "
                + repo.getDir().getAbsolutePath());
    }

    private void refreshStatus() {
        String ip = SoftApManager.getPreferredIp();
        txtServer.setText(serviceOn ? "运行中 · 端口 " + PORT : "未启动");
        txtServer.setTone(serviceOn ? MiuixText.Tone.SUCCESS : MiuixText.Tone.TERTIARY);
        txtAddr.setText(ip == null ? "—" : ("http://" + ip + ":" + PORT));
        txtAp.setText(apConfig == null || !apConfig.isValid()
                ? (hotspotOn ? "已开启（临时热点）" : "未读取到配置")
                : apConfig.ssid + (hotspotOn ? " · 已开启" : ""));
        btnStop.setEnabled(serviceOn || hotspotOn);
        btnConnect.setText(serviceOn ? "重新选择连接方式" : "选择连接方式");
    }

    private void refreshApDetail() {
        if (apConfig == null || !apConfig.isValid()) {
            apDetail.setText("未读取到车机热点配置。选择「手机连接车机热点」时，"
                    + "将尝试开启本车热点并使用其账号密码生成二维码。");
            apDetail.setTone(MiuixText.Tone.WARNING);
            return;
        }
        String pwd = apConfig.isOpen() ? "（无密码）" : apConfig.passphrase;
        apDetail.setText("名称 " + apConfig.ssid + "\n密码 " + pwd
                + "\n来源 " + (apConfig.fromSystem ? "车机原有配置" : "本次临时生成"));
        apDetail.setTone(MiuixText.Tone.SECONDARY);
    }

    private void refreshQr() {
        boolean chosen = connectMode >= 0 && serviceOn;
        segHotspot.setFilled(qrMode == 0);
        segUrl.setFilled(qrMode == 1);

        if (!chosen) {
            qrView.setContent(null);
            qrView.setVisibility(View.INVISIBLE);
            qrPlaceholder.setVisibility(View.VISIBLE);
            qrTitle.setText("未连接");
            qrSub.setText("选择连接方式后生成二维码");
            qrPayload.setText(lastError.length() > 0 ? lastError : "");
            return;
        }
        qrView.setVisibility(View.VISIBLE);
        qrPlaceholder.setVisibility(View.GONE);

        String payload;
        if (qrMode == 0) {
            payload = hotspotPayload();
            if (payload == null) {
                qrView.setContent(null);
                qrTitle.setText("热点信息不可用");
                qrSub.setText("改用「传输地址码」：手机与车机连同一网络后扫码传输");
                qrPayload.setText(transferUrl());
                qrMode = 1;
                segHotspot.setFilled(false);
                segUrl.setFilled(true);
                return;
            }
            qrTitle.setText("扫码连接车机热点");
            qrSub.setText("用手机相机扫码即可加入 " + (apConfig == null ? "" : apConfig.ssid)
                    + "，连上后再扫「传输地址码」发送文件");
            qrPayload.setText("名称 " + apConfig.ssid + "　密码 "
                    + (apConfig.isOpen() ? "（无密码）" : apConfig.passphrase));
        } else {
            payload = transferUrl();
            qrTitle.setText("扫码发送文件");
            qrSub.setText("手机与车机需处于同一网络（车机热点或同一 Wi-Fi）");
            qrPayload.setText(payload == null ? "未获取到网络地址" : payload);
        }
        if (payload == null) {
            qrView.setContent(null);
            return;
        }
        qrView.setContent(payload);
        if (qrView.getError() != null) {
            qrSub.setText("二维码生成失败：" + qrView.getError());
        }
    }

    private String hotspotPayload() {
        if (apConfig == null || !apConfig.isValid()) return null;
        return SoftApManager.wifiQrPayload(apConfig);
    }

    private String transferUrl() {
        String ip = SoftApManager.getPreferredIp();
        return ip == null ? null : ("http://" + ip + ":" + PORT);
    }

    private void refreshAdb() {
        new Thread(new Runnable() {
            public void run() {
                final AdbManager.AdbStatus st = AdbManager.query();
                ui.post(new Runnable() {
                    public void run() {
                        boolean connected = st.isConnected();
                        btnAdbChip.setText(connected ? "ADB 已连接"
                                : (st.listening ? "ADB 待连接" : "ADB 未连接"));
                        StringBuilder sb = new StringBuilder();
                        sb.append(st.daemonRunning ? "adbd 运行中" : "adbd 未运行");
                        sb.append(" · TCP 端口 ").append(st.tcpPort);
                        sb.append(st.tcpEnabled ? "（已启用）" : "（未启用）");
                        if (st.listening) sb.append(" · 已监听");
                        if (connected) {
                            sb.append("\n已连接客户端：").append(TextUtils.join(", ", st.clients));
                        } else {
                            sb.append("\n暂无客户端连接");
                        }
                        adbDetail.setText(sb.toString());
                        adbDetail.setTone(connected ? MiuixText.Tone.SUCCESS
                                : MiuixText.Tone.SECONDARY);
                    }
                });
            }
        }).start();
    }

    private void enableAdbTcp() {
        new Thread(new Runnable() {
            public void run() {
                final AdbManager.InstallResult r = AdbManager.enableTcp(5555);
                ui.post(new Runnable() {
                    public void run() {
                        toast(r.summary);
                        refreshAdb();
                    }
                });
            }
        }).start();
    }

    private void renderFiles() {
        fileList.removeAllViews();
        final JSONArray arr = repo.listJson();
        if (arr.length() == 0) {
            MiuixText empty = new MiuixText(this, "还没有收到文件", MiuixText.Role.BODY_SMALL,
                    MiuixText.Tone.TERTIARY);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = MiuixTheme.dp(this, 24f);
            lp.bottomMargin = MiuixTheme.dp(this, 24f);
            fileList.addView(empty, lp);
            fileSummary.setText("暂无文件");
            btnClean.setEnabled(false);
            return;
        }
        btnClean.setEnabled(true);
        long total = repo.totalSize();
        fileSummary.setText("共 " + arr.length() + " 个文件 · " + fmtSize(total));

        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final String name = o.optString("name");
            long size = o.optLong("size");
            String time = o.optString("time");
            String type = FileRepository.typeOf(name);

            MiuixListItem item = new MiuixListItem(this);
            item.setTitle(name);
            item.setSummary(fmtSize(size) + " · " + time);
            item.setBadge(type, badgeColor(type));

            LinearLayout trailing = new LinearLayout(this);
            trailing.setOrientation(LinearLayout.HORIZONTAL);
            trailing.setGravity(Gravity.CENTER_VERTICAL);

            MiuixButton open = new MiuixButton(this, "打开", MiuixButton.Size.SMALL,
                    MiuixButton.Color.NEUTRAL);
            open.setFilled(false);
            open.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    openFile(name);
                }
            });
            trailing.addView(open);

            if ("APK".equals(type)) {
                MiuixButton install = new MiuixButton(this, "安装", MiuixButton.Size.SMALL,
                        MiuixButton.Color.PRIMARY);
                install.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        installApk(name);
                    }
                });
                trailing.addView(install, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            item.setTrailingView(trailing);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = MiuixTheme.dp(this, 8f);
            fileList.addView(item, lp);
        }
    }

    private static int badgeColor(String type) {
        if ("APK".equals(type)) return 0xFF0F7FFF;
        if ("ZIP".equals(type)) return 0xFFFF9500;
        if ("PDF".equals(type)) return 0xFFF44336;
        if ("PNG".equals(type)) return 0xFF34C759;
        if ("MP4".equals(type)) return 0xFFAF52DE;
        if ("DOC".equals(type)) return 0xFF5AC8FA;
        return 0xFF8A8A8E;
    }

    // ------------------------------------------------------------------ dialogs

    private void showConnectDialog() {
        final int[] sel = new int[]{connectMode >= 0 ? connectMode : 0};
        final String[] titles = {"手机连接车机热点", "车机连接手机热点"};
        final String[] subs = {
                "车机开启热点，手机扫码或手动加入后传输（推荐）",
                "车机与手机连入同一 Wi-Fi，用传输地址收发文件"
        };

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout[] rows = new LinearLayout[2];
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            int p = MiuixTheme.dp(this, 12f);
            row.setPadding(p, p, p, p);
            MiuixText t = new MiuixText(this, titles[i], MiuixText.Role.BODY);
            MiuixText s = new MiuixText(this, subs[i], MiuixText.Role.CAPTION,
                    MiuixText.Tone.TERTIARY);
            row.addView(t);
            row.addView(s);
            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    sel[0] = idx;
                    paintMode(rows, sel[0]);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = MiuixTheme.dp(this, 8f);
            rows[i] = row;
            box.addView(row, lp);
        }
        paintMode(rows, sel[0]);

        new MiuixDialog.Builder(this)
                .setTitle("选择连接方式")
                .setMessage("手机与车机如何建立连接？")
                .setContent(box)
                .setNegative("取消", null)
                .setPositive("确认", new MiuixDialog.OnActionListener() {
                    public void onAction(MiuixDialog dialog) {
                        dialog.dismiss();
                        startForMode(sel[0]);
                    }
                })
                .show();
    }

    private void paintMode(LinearLayout[] rows, int selected) {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null) continue;
            boolean on = i == selected;
            int bg = on ? MiuixTheme.colors().primaryContainer
                    : MiuixTheme.colors().surfaceVariant;
            int r = MiuixTheme.dp(this, MiuixTheme.RADIUS_FIELD);
            rows[i].setBackground(MiuixTheme.rounded(bg, r));
        }
    }

    /**
     * Most car ROMs block the hidden soft-AP getters, so the driver can pin the
     * credentials the car unit already uses. They are kept across restarts.
     */
    private void showApEditor() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        String curSsid = apConfig != null && apConfig.ssid != null ? apConfig.ssid : "";
        String curPass = apConfig != null && apConfig.passphrase != null
                ? apConfig.passphrase : "";
        final MiuixTextField fSsid = new MiuixTextField(this, "热点名称（SSID）", "例如 CarUnit-8F2A");
        fSsid.setText(curSsid);
        final MiuixTextField fPass = new MiuixTextField(this, "密码（无密码请留空）", "例如 88888888");
        fPass.setText(curPass);

        box.addView(fSsid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = MiuixTheme.dp(this, 12f);
        box.addView(fPass, pLp);

        new MiuixDialog.Builder(this)
                .setTitle("车机热点账号密码")
                .setMessage("填写车机原本的热点名称与密码，二维码将直接使用该账号密码，"
                        + "手机扫码即可加入。")
                .setContent(box)
                .setNegative("取消", null)
                .setPositive("保存", new MiuixDialog.OnActionListener() {
                    public void onAction(MiuixDialog dialog) {
                        String s = fSsid.getText();
                        String p = fPass.getText();
                        if (s.length() == 0) {
                            toast("热点名称不能为空");
                            return;
                        }
                        dialog.dismiss();
                        SoftApManager.ApConfig c = new SoftApManager.ApConfig();
                        c.ssid = s;
                        c.passphrase = p;
                        c.security = p.length() == 0 ? "nopass" : "WPA";
                        c.fromSystem = true;
                        apConfig = c;
                        saveAp(s, p);
                        refreshApDetail();
                        refreshStatus();
                        refreshQr();
                        toast("已保存热点配置");
                    }
                })
                .show();
    }

    private SoftApManager.ApConfig loadSavedAp() {
        String ssid = prefs.getString("ap_ssid", "");
        String pass = prefs.getString("ap_pass", "");
        if (ssid == null || ssid.length() == 0) return null;
        SoftApManager.ApConfig c = new SoftApManager.ApConfig();
        c.ssid = ssid;
        c.passphrase = pass == null ? "" : pass;
        c.security = c.passphrase.length() == 0 ? "nopass" : "WPA";
        c.fromSystem = true;
        return c;
    }

    private void saveAp(String ssid, String pass) {
        prefs.edit().putString("ap_ssid", ssid).putString("ap_pass", pass).apply();
    }

    private void confirmClean() {
        new MiuixDialog.Builder(this)
                .setTitle("清理已接收文件")
                .setMessage("将删除全部已接收文件，此操作不可恢复。")
                .setNegative("取消", null)
                .setPositive("确认清理", new MiuixDialog.OnActionListener() {
                    public void onAction(MiuixDialog dialog) {
                        dialog.dismiss();
                        final int n = repo.deleteAll();
                        renderFiles();
                        toast(n > 0 ? ("已清理 " + n + " 个文件") : "没有可清理的文件");
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ actions

    private void startForMode(int mode) {
        connectMode = mode;
        qrMode = mode == 0 ? 0 : 1;
        lastError = "";
        startServer();
        if (mode == 0 && swAutoHotspot.isChecked()) {
            startHotspot();
        }
        refreshAll();
    }

    private void startServer() {
        if (server != null && server.isRunning()) {
            serviceOn = true;
            return;
        }
        final String page = readAsset("upload.html");
        server = new HttpFileServer(PORT, repo, page, deviceName());
        server.addListener(new HttpFileServer.ReceiveListener() {
            public void onFileReceived(final String name, final long size) {
                ui.post(new Runnable() {
                    public void run() {
                        toast("已接收 " + name);
                        renderFiles();
                        refreshStatus();
                    }
                });
            }
        });
        new Thread(new Runnable() {
            public void run() {
                try {
                    server.start();
                    serviceOn = true;
                    lastError = "";
                } catch (Exception e) {
                    serviceOn = false;
                    lastError = "服务启动失败：" + e.getMessage();
                    Log.e(TAG, "start server", e);
                }
                ui.post(new Runnable() {
                    public void run() {
                        refreshStatus();
                        refreshQr();
                    }
                });
            }
        }).start();
    }

    private void startHotspot() {
        if (hotspotOn) {
            refreshStatus();
            refreshQr();
            return;
        }
        softAp.startLocalOnly(new SoftApManager.Callback() {
            public void onStarted(final SoftApManager.ApConfig config) {
                hotspotOn = true;
                if (config != null && config.isValid()) apConfig = config;
                ui.post(new Runnable() {
                    public void run() {
                        refreshStatus();
                        refreshApDetail();
                        refreshQr();
                    }
                });
                ui.postDelayed(new Runnable() {
                    public void run() {
                        refreshStatus();
                        refreshQr();
                    }
                }, 1500L);
            }

            public void onFailed(final String reason) {
                hotspotOn = false;
                lastError = reason + "（仍可在同一 Wi-Fi 下使用传输地址）";
                ui.post(new Runnable() {
                    public void run() {
                        refreshStatus();
                        refreshQr();
                        toast(reason);
                    }
                });
            }
        });
    }

    private synchronized void stopAll() {
        if (server != null) server.stop();
        server = null;
        serviceOn = false;
        softAp.stopLocalOnly();
        hotspotOn = false;
        connectMode = -1;
    }

    private void openFile(final String name) {
        final File f = repo.get(name);
        if (f == null) {
            toast("文件不存在");
            return;
        }
        try {
            Uri uri = LocalFileProvider.uriFor(f.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, FileRepository.mimeOf(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "打开文件"));
        } catch (Exception e) {
            toast("没有可打开此文件的应用");
        }
    }

    /** Install through adb / shell first; fall back to the system installer. */
    private void installApk(final String name) {
        final File f = repo.get(name);
        if (f == null) {
            toast("文件不存在");
            return;
        }
        toast("正在通过 ADB 安装…");
        new Thread(new Runnable() {
            public void run() {
                final AdbManager.InstallResult r = AdbManager.install(f);
                ui.post(new Runnable() {
                    public void run() {
                        if (r.success) {
                            toast(r.summary);
                            return;
                        }
                        showInstallFailure(r, f);
                    }
                });
            }
        }).start();
    }

    private void showInstallFailure(final AdbManager.InstallResult r, final File f) {
        String detail = r.summary;
        if (r.raw != null && r.raw.length() > 0) detail += "\n\n" + r.raw;
        new MiuixDialog.Builder(this)
                .setTitle("ADB 安装未成功")
                .setMessage(detail)
                .setNegative("关闭", null)
                .setPositive("用系统安装界面", new MiuixDialog.OnActionListener() {
                    public void onAction(MiuixDialog dialog) {
                        dialog.dismiss();
                        installViaSystem(f);
                    }
                })
                .show();
    }

    private void installViaSystem(File f) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            toast("请先允许安装未知应用");
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Log.w(TAG, "unknown sources settings", e);
            }
            return;
        }
        try {
            Uri uri = LocalFileProvider.uriFor(f.getName());
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            startActivity(i);
        } catch (Exception e) {
            toast("无法启动安装界面");
        }
    }

    // ------------------------------------------------------------------ helpers

    private String deviceName() {
        String m = Build.MODEL;
        return (m == null || m.trim().isEmpty()) ? "车机" : m.trim();
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "read asset " + name, e);
            return "<html><body>资源缺失: " + name + "</body></html>";
        }
    }

    private static String fmtSize(long b) {
        if (b < 1024L) return b + " B";
        if (b < 1024L * 1024L) return new DecimalFormat("#.#").format(b / 1024.0) + " KB";
        if (b < 1024L * 1024L * 1024L) {
            return new DecimalFormat("#.#").format(b / 1048576.0) + " MB";
        }
        return new DecimalFormat("#.##").format(b / 1073741824.0) + " GB";
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (tabRow != null && tabRow.getSelected() != 0) {
            tabRow.setSelected(0);
            showPage(0);
            return;
        }
        super.onBackPressed();
    }
}
