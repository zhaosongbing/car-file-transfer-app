package com.zsb.carfiletransfer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zsb.carfiletransfer.miuix.MiuixButton;
import com.zsb.carfiletransfer.miuix.MiuixCard;
import com.zsb.carfiletransfer.miuix.MiuixDialog;
import com.zsb.carfiletransfer.miuix.MiuixListItem;
import com.zsb.carfiletransfer.miuix.MiuixQrView;
import com.zsb.carfiletransfer.miuix.MiuixTabRow;
import com.zsb.carfiletransfer.miuix.MiuixText;
import com.zsb.carfiletransfer.miuix.MiuixTextField;
import com.zsb.carfiletransfer.miuix.MiuixTheme;
import com.zsb.carfiletransfer.miuix.MiuixTopAppBar;
import com.zsb.carfiletransfer.miuix.MiuixWindowSizeClass;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Car-side UI.
 *
 * <p>Built exclusively from MIUIX components - no WebView is used anywhere in
 * the app. The layout is responsive: every spacing / size token is derived from
 * {@link MiuixWindowSizeClass}, and at the EXPANDED break point the values fall
 * back to the exact numbers of the design draft (1194 x 834).</p>
 */
public class MainActivity extends Activity {

    // ---- design draft tokens (EXPANDED, 1194 x 834) ----
    private static final float BAR_H_DRAFT = 92f;
    private static final float PAD_DRAFT = 48f;
    private static final float QR_CARD_W_DRAFT = 460f;
    private static final float QR_CARD_PAD_DRAFT = 40f;
    private static final float QR_SIZE_DRAFT = 300f;
    private static final float GAP_MAIN_DRAFT = 40f;
    private static final float GAP_CARD_DRAFT = 20f;
    /** Design draft "文件列表区" gap between list rows. */
    private static final float ROW_GAP_DRAFT = 12f;

    private static final float R_QR_CARD = 32f;
    private static final float R_CARD = 24f;
    private static final float R_ROW = 16f;
    private static final float R_BLOCK = 12f;
    private static final float R_PILL = 9999f;
    private static final float R_BTN = 100f;

    private static final int SERVER_PORT = 8899;
    private static final String PREF = "carfile";
    private static final int REQ_PERMISSIONS = 2101;
    private static final int REQ_LOCATION = 2102;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_LIST = 1;
    private static final int PAGE_DETAIL = 2;

    // ---- responsive tokens ----
    private MiuixWindowSizeClass wsc;
    private float pad = PAD_DRAFT;
    private float barH = BAR_H_DRAFT;
    private float qrCardW = QR_CARD_W_DRAFT;
    private float qrCardPad = QR_CARD_PAD_DRAFT;
    private float qrSize = QR_SIZE_DRAFT;
    private float gapMain = GAP_MAIN_DRAFT;
    private float gapCard = GAP_CARD_DRAFT;
    private boolean twoPane = true;

    private FrameLayout root;
    private LinearLayout pageHome;
    private LinearLayout pageList;
    private LinearLayout pageDetail;
    private int currentPage = PAGE_HOME;

    // home
    private MiuixText qrTitle;
    private MiuixText qrDesc;
    private FrameLayout qrSlot;
    private MiuixQrView qrView;
    private MiuixText devAddrValue;
    private TextView statusDot;
    private MiuixText statusTitle;
    private MiuixText statusSub;
    private MiuixText statCount;
    private MiuixText statSize;
    private MiuixText statUnit;
    private LinearLayout recentBox;
    private TextView adbDot;
    private MiuixText adbText;
    private MiuixTabRow qrModeRow;
    private MiuixButton btnHotspot;
    private MiuixText apDetail;

    // list
    private MiuixText countBadge;
    private LinearLayout listBox;
    private MiuixTabRow filterRow;

    // detail
    private LinearLayout detailBox;

    private FileRepository repo;
    private SoftApManager softAp;
    private SoftApManager.ApConfig apConfig;
    private boolean hotspotUp = false;
    /** Guards the hotspot toggle while an open / close request is in flight. */
    private boolean hotspotBusy = false;

    /** Bridges background transfer events back into the UI thread. */
    private final HttpFileServer.ReceiveListener receiveListener =
            new HttpFileServer.ReceiveListener() {
                public void onFileReceived(final String name, final long size) {
                    ui.post(new Runnable() {
                        public void run() {
                            refreshHome();
                            if (currentPage == PAGE_LIST) renderList();
                        }
                    });
                }
            };
    private boolean linked = false;
    private int qrMode = 0;
    private int connectOption = 0;
    private String detailFile;
    private int filterIndex = 0;
    private boolean connectDialogShown = false;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // must run before setContentView: makes the layout draw behind the
        // system bars, and tells the window how to treat display cut-outs
        setupWindow();

        repo = new FileRepository(this);
        softAp = new SoftApManager(this);
        apConfig = softAp.readSystemConfig();
        if (apConfig == null) apConfig = loadManualConfig();

        startTransferService();
        requestRuntimePermissions();

        buildUi();
        showPage(PAGE_HOME);
        ui.post(adbPoll);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildUi();
        showPage(currentPage);
    }

    /** (Re)build the whole view tree - cheap enough to redo on rotation. */
    private void buildUi() {
        computeTokens();

        root = new FrameLayout(this);
        root.setBackgroundColor(MiuixTheme.colors().background);

        pageHome = buildHomePage();
        pageList = buildListPage();
        pageDetail = buildDetailPage();

        root.addView(pageHome, matchParent());
        root.addView(pageList, matchParent());
        root.addView(pageDetail, matchParent());

        setContentView(root);
        applySystemBars(root);

        hotspotUp = softAp.isHotspotUp();
        refreshHome();
        renderList();
    }

    private void computeTokens() {
        wsc = MiuixWindowSizeClass.current(this);
        twoPane = wsc.isTwoPane();
        MiuixWindowSizeClass.SizeClass sc = wsc.getSizeClass();

        if (sc == MiuixWindowSizeClass.SizeClass.EXPANDED) {
            pad = PAD_DRAFT;
            barH = BAR_H_DRAFT;
        } else if (sc == MiuixWindowSizeClass.SizeClass.MEDIUM) {
            pad = 32f;
            barH = 72f;
        } else {
            pad = 20f;
            barH = 56f;
        }

        float contentW = Math.max(280f, wsc.getWidthDp() - pad * 2f);
        if (twoPane) {
            qrCardW = Math.min(QR_CARD_W_DRAFT, contentW * 0.42f);
            gapMain = sc == MiuixWindowSizeClass.SizeClass.EXPANDED ? GAP_MAIN_DRAFT : 28f;
        } else {
            qrCardW = contentW;
            gapMain = sc == MiuixWindowSizeClass.SizeClass.COMPACT ? 16f : 24f;
        }

        qrCardPad = qrCardW >= 400f ? QR_CARD_PAD_DRAFT : (qrCardW >= 320f ? 28f : 20f);
        gapCard = qrCardW >= 400f ? GAP_CARD_DRAFT : 16f;

        float inner = Math.max(160f, qrCardW - qrCardPad * 2f);
        qrSize = Math.min(sc == MiuixWindowSizeClass.SizeClass.EXPANDED ? QR_SIZE_DRAFT : 260f, inner);
        qrSize = Math.min(qrSize, Math.max(160f, wsc.getHeightDp() * 0.40f));
    }

    @Override
    protected void onResume() {
        super.onResume();
        hotspotUp = softAp.isHotspotUp();
        refreshHome();
        if (!linked && !connectDialogShown) {
            connectDialogShown = true;
            showConnectDialog();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(adbPoll);
        // deliberately keeps the foreground service and the hotspot alive so the
        // transfer channel survives rotation / backgrounding
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentPage == PAGE_DETAIL) {
            showPage(PAGE_LIST);
        } else if (currentPage == PAGE_LIST) {
            showPage(PAGE_HOME);
        } else {
            super.onBackPressed();
        }
    }

    private void showPage(int page) {
        currentPage = page;
        pageHome.setVisibility(page == PAGE_HOME ? View.VISIBLE : View.GONE);
        pageList.setVisibility(page == PAGE_LIST ? View.VISIBLE : View.GONE);
        pageDetail.setVisibility(page == PAGE_DETAIL ? View.VISIBLE : View.GONE);
        if (page == PAGE_LIST) renderList();
        if (page == PAGE_HOME) refreshHome();
    }

    // ---------------------------------------------------------------- home page

    private LinearLayout buildHomePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(MiuixTheme.colors().background);

        MiuixTopAppBar bar = new MiuixTopAppBar(this, getString(R.string.app_title), null);
        bar.setHeightDp(barH).setPaddingDp(pad, 0f)
                .setTitleSizeSp(wsc.isCompact() ? 18f : 22f);
        bar.setBottomDivider(true, MiuixTheme.colors().outline, 1f);
        bar.setLeading(logoView());
        bar.addActionView(adbPill());
        page.addView(bar, wrapWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(twoPane ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(buildQrCard());

        LinearLayout.LayoutParams sideLp = twoPane
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : wrapWidth();
        if (twoPane) {
            sideLp.leftMargin = dp(gapMain);
        } else {
            sideLp.topMargin = dp(gapMain);
        }
        content.addView(buildSideColumn(), sideLp);

        return page;
    }

    private View logoView() {
        FrameLayout logo = new FrameLayout(this);
        int s = dp(wsc.isCompact() ? 36f : 44f);
        logo.setBackground(MiuixTheme.rounded(MiuixTheme.colors().primary, dp(R_BLOCK)));
        TextView arrow = new TextView(this);
        arrow.setText("↑");
        arrow.setTextColor(Color.WHITE);
        arrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, wsc.isCompact() ? 17f : 20f);
        arrow.setGravity(Gravity.CENTER);
        logo.addView(arrow, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        lp.rightMargin = dp(12f);
        logo.setLayoutParams(lp);
        return logo;
    }

    /** Top right: live ADB connection state (replaces the device name chip). */
    private View adbPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(MiuixTheme.rounded(
                MiuixTheme.colors().surfaceContainer, dp(R_PILL)));
        pill.setPadding(dp(16f), dp(10f), dp(16f), dp(10f));
        pill.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAdbDialog();
            }
        });

        adbDot = new TextView(this);
        adbDot.setBackground(MiuixTheme.rounded(MiuixTheme.colors().outline, dp(4f)));
        pill.addView(adbDot, new LinearLayout.LayoutParams(dp(8f), dp(8f)));

        adbText = new MiuixText(this, getString(R.string.adb_idle),
                MiuixText.Role.CAPTION, MiuixText.Tone.SECONDARY);
        adbText.setSizeSp(wsc.isCompact() ? 12f : 14f).setWeight(500);
        pill.addView(adbText, marginLeft(8f));
        return pill;
    }

    private View buildQrCard() {
        MiuixCard card = new MiuixCard(this, R_QR_CARD);
        card.setOutline(MiuixTheme.colors().outline, 1f);
        card.setContentPaddingDp(qrCardPad, qrCardPad, qrCardPad, qrCardPad);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                twoPane ? dp(qrCardW) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        qrTitle = new MiuixText(this, getString(R.string.qr_wait_title),
                MiuixText.Role.SUBTITLE);
        qrTitle.setSizeSp(wsc.isCompact() ? 17f : 20f).setWeight(700);
        qrTitle.setGravity(Gravity.CENTER_HORIZONTAL);
        qrTitle.setSingleLine(false);
        card.addView(qrTitle);

        qrDesc = new MiuixText(this, getString(R.string.qr_wait_desc),
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        qrDesc.setSizeSp(wsc.isCompact() ? 12f : 14f).setWeight(400);
        qrDesc.setSingleLine(false);
        qrDesc.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                dp(Math.min(360f, qrCardW - qrCardPad * 2f)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp(gapCard);
        dLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qrDesc, dLp);

        qrSlot = new FrameLayout(this);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                dp(qrSize), dp(qrSize));
        qLp.topMargin = dp(gapCard);
        qLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qrSlot, qLp);

        qrModeRow = new MiuixTabRow(this, new String[]{
                getString(R.string.qr_mode_wifi), getString(R.string.qr_mode_addr)}, 0);
        qrModeRow.setChipStyle(true);
        qrModeRow.setOnTabSelectedListener(new MiuixTabRow.OnTabSelectedListener() {
            public void onTabSelected(int index, String title) {
                qrMode = index;
                refreshQr();
            }
        });
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(gapCard);
        mLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qrModeRow, mLp);

        LinearLayout devBox = new LinearLayout(this);
        devBox.setOrientation(LinearLayout.VERTICAL);
        devBox.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bLp.topMargin = dp(gapCard);
        bLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(devBox, bLp);

        MiuixText devName = new MiuixText(this, getString(R.string.device_name_value),
                MiuixText.Role.BODY_SMALL);
        devName.setSizeSp(wsc.isCompact() ? 13f : 15f).setWeight(600);
        devName.setGravity(Gravity.CENTER_HORIZONTAL);
        devBox.addView(devName);

        devAddrValue = new MiuixText(this, addressText(), MiuixText.Role.BODY_SMALL);
        devAddrValue.setSizeSp(wsc.isCompact() ? 13f : 15f).setWeight(600);
        devAddrValue.setGravity(Gravity.CENTER_HORIZONTAL);
        devBox.addView(devAddrValue);

        apDetail = new MiuixText(this, apDetailText(), MiuixText.Role.MICRO,
                MiuixText.Tone.TERTIARY);
        apDetail.setSizeSp(12f).setWeight(400);
        apDetail.setGravity(Gravity.CENTER_HORIZONTAL);
        apDetail.setSingleLine(false);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        apLp.topMargin = dp(6f);
        apLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(apDetail, apLp);

        MiuixText tip = new MiuixText(this, getString(R.string.network_tip),
                MiuixText.Role.BODY_SMALL, MiuixText.Tone.TERTIARY);
        tip.setSizeSp(wsc.isCompact() ? 13f : 15f).setWeight(500);
        tip.setGravity(Gravity.CENTER);
        tip.setBackground(MiuixTheme.rounded(
                MiuixTheme.colors().surfaceContainer, dp(R_PILL)));
        tip.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = dp(gapCard);
        tLp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(tip, tLp);

        return card;
    }

    private View buildSideColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // 1 - connection status + hotspot button
        MiuixCard status = new MiuixCard(this, R_CARD);
        status.setCardBackground(MiuixTheme.colors().surfaceContainer);
        status.setContentPaddingDp(24f, 24f, 24f, 24f);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(status, wrapWidth());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        status.addView(left);

        statusDot = new TextView(this);
        statusDot.setBackground(MiuixTheme.rounded(MiuixTheme.colors().success, dp(5f)));
        left.addView(statusDot, new LinearLayout.LayoutParams(dp(10f), dp(10f)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        left.addView(textCol, marginLeft(12f));

        statusTitle = new MiuixText(this, getString(R.string.status_waiting),
                MiuixText.Role.BODY);
        statusTitle.setSizeSp(16f).setWeight(600);
        textCol.addView(statusTitle);

        statusSub = new MiuixText(this, getString(R.string.status_no_device),
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        statusSub.setSizeSp(13f).setWeight(400);
        textCol.addView(statusSub);

        btnHotspot = new MiuixButton(this, getString(R.string.open_hotspot),
                MiuixButton.Size.SMALL, MiuixButton.Color.PRIMARY);
        btnHotspot.setOutlined(true, MiuixTheme.colors().primary);
        btnHotspot.setRadiusDp(R_PILL).setPaddingDp(18f, 10f);
        btnHotspot.setLabelSizeSp(14f).setLabelWeight(600);
        btnHotspot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onHotspotClicked();
            }
        });
        // long press = force the car unit's own SSID / password by hand
        btnHotspot.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                showManualConfigDialog();
                return true;
            }
        });
        status.addView(btnHotspot, wrapContent());

        // 2 - statistics row
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sLp = wrapWidth();
        sLp.topMargin = dp(24f);
        col.addView(stats, sLp);

        MiuixCard cardCount = new MiuixCard(this, R_CARD);
        cardCount.setOutline(MiuixTheme.colors().outline, 1f);
        cardCount.setContentPaddingDp(24f, 24f, 24f, 24f);
        stats.addView(cardCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MiuixText cLabel = new MiuixText(this, getString(R.string.stat_received),
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        cLabel.setSizeSp(13f).setWeight(400);
        cardCount.addView(cLabel);

        statCount = new MiuixText(this, "0", MiuixText.Role.DISPLAY,
                MiuixText.Tone.BRAND);
        statCount.setSizeSp(32f).setWeight(700);
        cardCount.addView(statCount);

        MiuixText cUnit = new MiuixText(this, getString(R.string.stat_unit_files),
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        cUnit.setSizeSp(13f).setWeight(400);
        cardCount.addView(cUnit);

        MiuixCard cardSpace = new MiuixCard(this, R_CARD);
        cardSpace.setOutline(MiuixTheme.colors().outline, 1f);
        cardSpace.setContentPaddingDp(24f, 24f, 24f, 24f);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        spLp.leftMargin = dp(16f);
        stats.addView(cardSpace, spLp);

        MiuixText sLabel = new MiuixText(this, getString(R.string.stat_space),
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        sLabel.setSizeSp(13f).setWeight(400);
        cardSpace.addView(sLabel);

        statSize = new MiuixText(this, "0", MiuixText.Role.DISPLAY);
        statSize.setSizeSp(32f).setWeight(700);
        cardSpace.addView(statSize);

        statUnit = new MiuixText(this, "GB", MiuixText.Role.CAPTION,
                MiuixText.Tone.TERTIARY);
        statUnit.setSizeSp(13f).setWeight(400);
        cardSpace.addView(statUnit);

        // 3 - recently received card
        MiuixCard recent = new MiuixCard(this, R_CARD);
        recent.setOutline(MiuixTheme.colors().outline, 1f);
        recent.setContentPaddingDp(24f, 24f, 24f, 24f);
        LinearLayout.LayoutParams rLp = wrapWidth();
        rLp.topMargin = dp(24f);
        col.addView(recent, rLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        recent.addView(head, wrapWidth());

        MiuixText rTitle = new MiuixText(this, getString(R.string.recent_title),
                MiuixText.Role.BODY);
        rTitle.setSizeSp(16f).setWeight(700);
        rTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(rTitle);

        MiuixText rMore = new MiuixText(this, getString(R.string.see_all),
                MiuixText.Role.BODY_SMALL, MiuixText.Tone.BRAND);
        rMore.setSizeSp(14f).setWeight(600);
        rMore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPage(PAGE_LIST);
            }
        });
        head.addView(rMore);

        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rbLp = wrapWidth();
        rbLp.topMargin = dp(16f);
        recent.addView(recentBox, rbLp);

        return col;
    }

    // ---------------------------------------------------------------- list page

    private LinearLayout buildListPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(MiuixTheme.colors().background);

        MiuixTopAppBar bar = new MiuixTopAppBar(this, getString(R.string.list_title), null);
        bar.setHeightDp(barH).setPaddingDp(pad, 0f)
                .setTitleSizeSp(wsc.isCompact() ? 18f : 22f);
        bar.setBottomDivider(true, MiuixTheme.colors().outline, 1f);

        MiuixText back = new MiuixText(this, "‹", MiuixText.Role.DISPLAY);
        back.setSizeSp(26f).setWeight(400);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPage(PAGE_HOME);
            }
        });
        bar.setLeading(back);
        bar.addTitleSuffix(countChip());
        if (!wsc.isCompact()) bar.addActionView(searchField());
        bar.addActionView(cleanButton());
        page.addView(bar, wrapWidth());

        filterRow = new MiuixTabRow(this, new String[]{
                getString(R.string.filter_all),
                getString(R.string.filter_app),
                getString(R.string.filter_image),
                getString(R.string.filter_video),
                getString(R.string.filter_doc)}, 0);
        filterRow.setChipStyle(true);
        filterRow.setOnTabSelectedListener(new MiuixTabRow.OnTabSelectedListener() {
            public void onTabSelected(int index, String title) {
                filterIndex = index;
                renderList();
            }
        });
        LinearLayout fl = new LinearLayout(this);
        fl.setOrientation(LinearLayout.HORIZONTAL);
        fl.setPadding(dp(pad), dp(16f), dp(pad), dp(16f));
        fl.addView(filterRow, wrapContent());
        page.addView(fl, wrapWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(pad), dp(20f), dp(pad), dp(20f));
        scroll.addView(listBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return page;
    }

    private View countChip() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(MiuixTheme.rounded(
                MiuixTheme.colors().surfaceContainer, dp(R_PILL)));
        pill.setPadding(dp(12f), dp(4f), dp(12f), dp(4f));
        countBadge = new MiuixText(this, "0", MiuixText.Role.CAPTION,
                MiuixText.Tone.TERTIARY);
        countBadge.setSizeSp(13f).setWeight(600);
        pill.addView(countBadge);
        return pill;
    }

    private View searchField() {
        return new MiuixTextField(this, null, getString(R.string.search_hint));
    }

    private View cleanButton() {
        MiuixButton b = new MiuixButton(this, getString(R.string.clean_all),
                MiuixButton.Size.SMALL, MiuixButton.Color.DANGER);
        b.setRadiusDp(R_BTN).setPaddingDp(14f, 8f);
        b.setLabelSizeSp(14f).setLabelWeight(500);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showCleanDialog();
            }
        });
        return b;
    }

    private void renderList() {
        if (listBox == null) return;
        JSONArray all = repo.listJson();
        countBadge.setText(String.valueOf(all.length()));
        listBox.removeAllViews();

        int shown = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name");
            String type = o.optString("type");
            if (!matchesFilter(type)) continue;
            // design draft "文件列表区": vertical gap 12 between rows
            LinearLayout.LayoutParams rowLp = wrapWidth();
            rowLp.topMargin = shown == 0 ? 0 : dp(ROW_GAP_DRAFT);
            listBox.addView(fileRow(o, name, type), rowLp);
            shown++;
        }
        if (shown == 0) {
            MiuixText empty = new MiuixText(this, getString(R.string.empty_list),
                    MiuixText.Role.BODY, MiuixText.Tone.TERTIARY);
            empty.setSizeSp(15f).setWeight(400);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            listBox.addView(empty);
        }
    }

    private boolean matchesFilter(String type) {
        switch (filterIndex) {
            case 1:
                return "APK".equals(type);
            case 2:
                return "PNG".equals(type);
            case 3:
                return "MP4".equals(type);
            case 4:
                return "DOC".equals(type) || "PDF".equals(type);
            default:
                return true;
        }
    }

    private View fileRow(JSONObject o, final String name, String type) {
        final File f = repo.get(name);
        MiuixListItem row = new MiuixListItem(this);
        row.setOutline(MiuixTheme.colors().outline, 1f, R_ROW);
        row.setItemPaddingDp(20f, 16f, 20f, 16f);
        row.setGapDp(16f);
        row.setBadge(type, MiuixTheme.fileTypeColor(type), 48f, R_BLOCK);
        row.setTitle(name).titleSizeSp(15f).titleWeight(600);
        row.textColumnWeight(1f);
        row.setSummary(human(o.optLong("size")) + " · " + o.optString("time"));
        row.summarySizeSp(13f);

        MiuixButton view = new MiuixButton(this, getString(R.string.view),
                MiuixButton.Size.SMALL, MiuixButton.Color.NEUTRAL);
        view.setOutlined(true, MiuixTheme.colors().outline);
        view.setRadiusDp(R_PILL).setPaddingDp(18f, 9f);
        view.setLabelSizeSp(14f).setLabelWeight(600);
        view.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                renderDetail(name);
                showPage(PAGE_DETAIL);
            }
        });
        row.addTrailingView(view);

        boolean apk = "APK".equals(type);
        MiuixButton action = new MiuixButton(this,
                apk ? getString(R.string.install) : getString(R.string.open),
                MiuixButton.Size.SMALL, MiuixButton.Color.PRIMARY);
        action.setRadiusDp(R_PILL).setPaddingDp(22f, 9f);
        action.setLabelSizeSp(14f).setLabelWeight(700);
        action.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (f == null || !f.exists()) return;
                if (f.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                    installFile(f);
                } else {
                    openFile(f);
                }
            }
        });
        action.setLayoutParams(marginLeft(12f));
        row.addTrailingView(action);

        row.setOnItemClick(new View.OnClickListener() {
            public void onClick(View v) {
                renderDetail(name);
                showPage(PAGE_DETAIL);
            }
        });
        return row;
    }

    private void renderRecent() {
        if (recentBox == null) return;
        recentBox.removeAllViews();
        JSONArray all = repo.listJson();
        int n = Math.min(3, all.length());
        for (int i = 0; i < n; i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            final String name = o.optString("name");
            String type = o.optString("type");

            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = wrapWidth();
            lp.topMargin = i == 0 ? 0 : dp(ROW_GAP_DRAFT);
            recentBox.addView(line, lp);

            TextView badge = new TextView(this);
            badge.setText(type);
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            badge.setTypeface(android.graphics.Typeface.create("sans-serif",
                    android.graphics.Typeface.BOLD));
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(MiuixTheme.rounded(
                    MiuixTheme.fileTypeColor(type), dp(R_BLOCK)));
            line.addView(badge, new LinearLayout.LayoutParams(dp(44f), dp(44f)));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            iLp.leftMargin = dp(12f);
            line.addView(info, iLp);

            MiuixText nm = new MiuixText(this, name, MiuixText.Role.BODY_SMALL);
            nm.setSizeSp(14f).setWeight(600);
            nm.setSingleLine(true);
            info.addView(nm);

            MiuixText meta = new MiuixText(this,
                    human(o.optLong("size")) + " · " + o.optString("time"),
                    MiuixText.Role.MICRO, MiuixText.Tone.TERTIARY);
            meta.setSizeSp(12f).setWeight(400);
            meta.setSingleLine(true);
            info.addView(meta);

            MiuixText chevron = new MiuixText(this, "›", MiuixText.Role.SUBTITLE,
                    MiuixText.Tone.TERTIARY);
            chevron.setSizeSp(20f).setWeight(400);
            line.addView(chevron);

            line.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    renderDetail(name);
                    showPage(PAGE_DETAIL);
                }
            });
        }
        if (n == 0) {
            MiuixText empty = new MiuixText(this, getString(R.string.empty_recent),
                    MiuixText.Role.BODY_SMALL, MiuixText.Tone.TERTIARY);
            empty.setSizeSp(13f).setWeight(400);
            recentBox.addView(empty);
        }
    }

    // ---------------------------------------------------------------- detail page

    private LinearLayout buildDetailPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(MiuixTheme.colors().background);
        detailBox = page;
        return page;
    }

    private void renderDetail(String name) {
        detailFile = name;
        detailBox.removeAllViews();
        final File f = repo.get(name);
        String type = FileRepository.typeOf(name);

        MiuixTopAppBar bar = new MiuixTopAppBar(this, name, null);
        bar.setHeightDp(barH).setPaddingDp(pad, 0f)
                .setTitleSizeSp(wsc.isCompact() ? 18f : 22f);
        bar.setBottomDivider(true, MiuixTheme.colors().outline, 1f);

        MiuixText back = new MiuixText(this, "‹", MiuixText.Role.DISPLAY);
        back.setSizeSp(26f).setWeight(400);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showPage(PAGE_LIST);
            }
        });
        bar.setLeading(back);
        bar.addTitleSuffix(typeChip(type));

        MiuixButton share = new MiuixButton(this, getString(R.string.share),
                MiuixButton.Size.SMALL, MiuixButton.Color.NEUTRAL);
        share.setOutlined(true, MiuixTheme.colors().outline);
        share.setRadiusDp(R_PILL).setPaddingDp(20f, 10f);
        share.setLabelSizeSp(14f).setLabelWeight(600);
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareCurrent();
            }
        });
        bar.addAction(share);
        detailBox.addView(bar, wrapWidth());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        detailBox.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(twoPane ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        scroll.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // preview card
        MiuixCard preview = new MiuixCard(this, R_CARD);
        preview.setCardBackground(MiuixTheme.colors().surfaceContainer);
        preview.setContentPaddingDp(32f, 40f, 32f, 40f);
        preview.setGravity(Gravity.CENTER_HORIZONTAL);
        content.addView(preview, new LinearLayout.LayoutParams(
                twoPane ? dp(420f) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout icon = new FrameLayout(this);
        icon.setBackground(MiuixTheme.rounded(MiuixTheme.colors().primary, dp(28f)));
        TextView iconText = new TextView(this);
        iconText.setText(type);
        iconText.setTextColor(Color.WHITE);
        iconText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f);
        iconText.setGravity(Gravity.CENTER);
        icon.addView(iconText, new FrameLayout.LayoutParams(
                dp(120f), dp(120f), Gravity.CENTER));
        preview.addView(icon, new LinearLayout.LayoutParams(dp(120f), dp(120f)));

        MiuixText typeLabel = new MiuixText(this, typeLabel(type),
                MiuixText.Role.BODY_SMALL, MiuixText.Tone.TERTIARY);
        typeLabel.setSizeSp(14f).setWeight(500);
        LinearLayout.LayoutParams tlLp = wrapContent();
        tlLp.topMargin = dp(20f);
        tlLp.gravity = Gravity.CENTER_HORIZONTAL;
        preview.addView(typeLabel, tlLp);

        MiuixText safe = new MiuixText(this, getString(R.string.safe_scanned),
                MiuixText.Role.MICRO, MiuixText.Tone.SUCCESS);
        safe.setSizeSp(12f).setWeight(500);
        safe.setGravity(Gravity.CENTER);
        safe.setBackground(MiuixTheme.rounded(MiuixTheme.colors().surface, dp(R_PILL)));
        safe.setPadding(dp(14f), dp(8f), dp(14f), dp(8f));
        LinearLayout.LayoutParams sfLp = wrapContent();
        sfLp.topMargin = dp(20f);
        sfLp.gravity = Gravity.CENTER_HORIZONTAL;
        preview.addView(safe, sfLp);

        // info column
        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams icLp = twoPane
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                : wrapWidth();
        if (twoPane) {
            icLp.leftMargin = dp(gapMain);
        } else {
            icLp.topMargin = dp(gapMain);
        }
        content.addView(infoCol, icLp);

        MiuixText bigTitle = new MiuixText(this, name, MiuixText.Role.DISPLAY);
        bigTitle.setSizeSp(wsc.isCompact() ? 20f : 24f).setWeight(700);
        bigTitle.setSingleLine(false);
        infoCol.addView(bigTitle);

        MiuixCard detailCard = new MiuixCard(this, R_ROW);
        detailCard.setOutline(MiuixTheme.colors().outline, 1f);
        detailCard.setContentPaddingDp(24f, 20f, 24f, 20f);
        LinearLayout.LayoutParams dcLp = wrapWidth();
        dcLp.topMargin = dp(20f);
        infoCol.addView(detailCard, dcLp);

        addDetailRow(detailCard, getString(R.string.detail_size),
                human(f != null ? f.length() : 0));
        addDetailRow(detailCard, getString(R.string.detail_type), typeLabel(type));
        addDetailRow(detailCard, getString(R.string.detail_time), timeOf(f));
        addDetailRow(detailCard, getString(R.string.detail_source),
                getString(R.string.device_phone));
        addDetailRow(detailCard, getString(R.string.detail_path),
                FileRepository.getStorageDir(this).getAbsolutePath() + "/" + name);

        MiuixCard permCard = new MiuixCard(this, R_ROW);
        permCard.setCardBackground(MiuixTheme.colors().surfaceContainer);
        permCard.setContentPaddingDp(24f, 20f, 24f, 20f);
        LinearLayout.LayoutParams pcLp = wrapWidth();
        pcLp.topMargin = dp(20f);
        infoCol.addView(permCard, pcLp);

        MiuixText permTitle = new MiuixText(this, getString(R.string.permission_title),
                MiuixText.Role.BODY_SMALL);
        permTitle.setSizeSp(14f).setWeight(600);
        permCard.addView(permTitle);

        LinearLayout permRow = new LinearLayout(this);
        permRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = wrapWidth();
        prLp.topMargin = dp(12f);
        permCard.addView(permRow, prLp);
        permRow.addView(permChip(getString(R.string.perm_storage)));
        permRow.addView(permChip(getString(R.string.perm_contacts)));
        permRow.addView(permChip(getString(R.string.perm_notify)));

        // action row
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aLp = wrapWidth();
        aLp.topMargin = dp(20f);
        infoCol.addView(actions, aLp);

        if ("APK".equals(type)) {
            MiuixButton install = new MiuixButton(this, getString(R.string.install),
                    MiuixButton.Size.SMALL, MiuixButton.Color.PRIMARY);
            install.setRadiusDp(R_PILL).setPaddingDp(32f, 14f);
            install.setLabelSizeSp(15f).setLabelWeight(700);
            install.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    installFile(f);
                }
            });
            actions.addView(install);
        }

        MiuixButton open = new MiuixButton(this, getString(R.string.open),
                MiuixButton.Size.SMALL, MiuixButton.Color.NEUTRAL);
        open.setOutlined(true, MiuixTheme.colors().outline);
        open.setRadiusDp(R_PILL).setPaddingDp(28f, 14f);
        open.setLabelSizeSp(15f).setLabelWeight(600);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openFile(f);
            }
        });
        open.setLayoutParams(marginLeft(16f));
        actions.addView(open);

        MiuixText del = new MiuixText(this, getString(R.string.delete),
                MiuixText.Role.BODY_SMALL, MiuixText.Tone.ERROR);
        del.setSizeSp(14f).setWeight(600);
        del.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                confirmDelete(f);
            }
        });
        actions.addView(del, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View typeChip(String type) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(MiuixTheme.rounded(
                MiuixTheme.colors().surfaceContainer, dp(R_PILL)));
        pill.setPadding(dp(12f), dp(4f), dp(12f), dp(4f));
        MiuixText t = new MiuixText(this, typeLabel(type),
                MiuixText.Role.MICRO, MiuixText.Tone.TERTIARY);
        t.setSizeSp(12f).setWeight(600);
        pill.addView(t);
        return pill;
    }

    private View permChip(String text) {
        MiuixText pill = new MiuixText(this, text, MiuixText.Role.MICRO,
                MiuixText.Tone.TERTIARY);
        pill.setSizeSp(12f).setWeight(500);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(MiuixTheme.outlined(MiuixTheme.colors().surface,
                MiuixTheme.colors().outline, dp(R_PILL), dp(1f)));
        pill.setPadding(dp(12f), dp(6f), dp(12f), dp(6f));
        pill.setLayoutParams(marginLeft(8f));
        return pill;
    }

    private void addDetailRow(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = wrapWidth();
        lp.topMargin = parent.getChildCount() == 0 ? 0 : dp(14f);
        row.setLayoutParams(lp);

        MiuixText k = new MiuixText(this, key, MiuixText.Role.CAPTION,
                MiuixText.Tone.TERTIARY);
        k.setSizeSp(13f).setWeight(400);
        k.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(k);

        MiuixText v = new MiuixText(this, value, MiuixText.Role.BODY_SMALL);
        v.setSizeSp(14f).setWeight(600);
        v.setSingleLine(false);
        row.addView(v);
        parent.addView(row);
    }

    // ---------------------------------------------------------------- dialogs

    private void showConnectDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);

        body.addView(connectIcon());

        MiuixText title = new MiuixText(this, getString(R.string.connect_title),
                MiuixText.Role.TITLE);
        title.setSizeSp(22f).setWeight(700);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(title, marginTop(18f));

        MiuixText desc = new MiuixText(this, getString(R.string.connect_desc),
                MiuixText.Role.BODY_SMALL, MiuixText.Tone.TERTIARY);
        desc.setSizeSp(14f).setWeight(400);
        desc.setSingleLine(false);
        desc.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(desc, marginTop(8f));

        final MiuixCard[] options = new MiuixCard[2];
        final int[] selected = new int[]{connectOption};
        options[0] = optionCard(getString(R.string.opt_phone_title),
                getString(R.string.opt_phone_desc), connectOption == 0);
        options[1] = optionCard(getString(R.string.opt_car_title),
                getString(R.string.opt_car_desc), connectOption == 1);

        for (int i = 0; i < 2; i++) {
            final int index = i;
            options[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    selected[0] = index;
                    for (int k = 0; k < 2; k++) {
                        boolean on = k == index;
                        options[k].setBackground(MiuixTheme.outlined(
                                on ? MiuixTheme.colors().primaryContainer
                                        : MiuixTheme.colors().surface,
                                on ? MiuixTheme.colors().primary
                                        : MiuixTheme.colors().outline,
                                dp(R_ROW), dp(1f)));
                        View radio = options[k].findViewWithTag("radio");
                        if (radio != null) radio.setBackground(radioDrawable(on));
                    }
                }
            });
            body.addView(options[i], marginTop(12f));
        }

        MiuixText tip = new MiuixText(this, getString(R.string.connect_tip),
                MiuixText.Role.MICRO, MiuixText.Tone.TERTIARY);
        tip.setSizeSp(12f).setWeight(400);
        tip.setSingleLine(false);
        body.addView(tip, marginTop(12f));

        new MiuixDialog.Builder(this)
                .setContent(body)
                .setWidthDp(Math.min(540f, wsc.getWidthDp() - 32f))
                .setRadiusDp(24f)
                .setPaddingDp(32f)
                .setCancelable(false)
                .setPositive(getString(R.string.connect_confirm),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                connectOption = selected[0];
                                linked = true;
                                d.dismiss();
                                onConnected();
                            }
                        })
                .show();
    }

    private MiuixCard optionCard(String title, String desc, boolean checked) {
        MiuixCard card = new MiuixCard(this, R_ROW);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setContentPaddingDp(16f, 16f, 16f, 16f);
        card.setBackground(MiuixTheme.outlined(
                checked ? MiuixTheme.colors().primaryContainer : MiuixTheme.colors().surface,
                checked ? MiuixTheme.colors().primary : MiuixTheme.colors().outline,
                dp(R_ROW), dp(1f)));

        View glyph = new View(this);
        glyph.setBackground(MiuixTheme.rounded(MiuixTheme.colors().primary, dp(6f)));
        card.addView(glyph, new LinearLayout.LayoutParams(dp(36f), dp(36f)));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcLp.leftMargin = dp(12f);
        card.addView(textCol, tcLp);

        MiuixText t = new MiuixText(this, title, MiuixText.Role.BODY_SMALL);
        t.setSizeSp(15f).setWeight(600);
        t.setSingleLine(false);
        textCol.addView(t);

        MiuixText d = new MiuixText(this, desc, MiuixText.Role.MICRO,
                MiuixText.Tone.TERTIARY);
        d.setSizeSp(12f).setWeight(400);
        d.setSingleLine(false);
        textCol.addView(d);

        View radio = new View(this);
        radio.setTag("radio");
        radio.setBackground(radioDrawable(checked));
        card.addView(radio, new LinearLayout.LayoutParams(dp(22f), dp(22f)));
        return card;
    }

    private android.graphics.drawable.Drawable radioDrawable(boolean checked) {
        if (checked) {
            return MiuixTheme.rounded(MiuixTheme.colors().primary, dp(11f));
        }
        return MiuixTheme.outlined(MiuixTheme.colors().surface,
                MiuixTheme.colors().outline, dp(11f), dp(2f));
    }

    private View connectIcon() {
        return new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float r = Math.min(getWidth(), getHeight()) / 2f;
                p.setColor(MiuixTheme.colors().primary);
                canvas.drawCircle(cx, cy, r, p);
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, r * 0.10f));
                canvas.drawArc(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f,
                        200f, 140f, false, p);
                p.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy + r * 0.32f, r * 0.10f, p);
            }

            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                setMeasuredDimension(dp(48f), dp(48f));
            }
        };
    }

    /** ADB state detail, opened from the top-right pill. */
    private void showAdbDialog() {
        final AdbManager.AdbStatus s = AdbManager.query();

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        addInfoRow(body, getString(R.string.adb_daemon),
                s.daemonRunning ? getString(R.string.adb_state_running)
                        : getString(R.string.adb_state_stopped));
        addInfoRow(body, getString(R.string.adb_port_label), String.valueOf(s.tcpPort));
        addInfoRow(body, getString(R.string.adb_listen_label),
                s.listening ? getString(R.string.adb_yes) : getString(R.string.adb_no));
        addInfoRow(body, getString(R.string.adb_clients_label),
                s.clients.isEmpty() ? getString(R.string.adb_client_none)
                        : joinClients(s.clients));

        MiuixButton enable = new MiuixButton(this, getString(R.string.adb_enable_tcp),
                MiuixButton.Size.MEDIUM, MiuixButton.Color.PRIMARY);
        enable.setRadiusDp(R_BTN);
        LinearLayout.LayoutParams eLp = wrapWidth();
        eLp.topMargin = dp(16f);
        enable.setLayoutParams(eLp);
        enable.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run() {
                        final AdbManager.InstallResult r =
                                AdbManager.enableTcp(AdbManager.DEFAULT_TCP_PORT);
                        ui.post(new Runnable() {
                            public void run() {
                                toast(r.summary);
                            }
                        });
                    }
                }).start();
            }
        });
        body.addView(enable);

        MiuixText tip = new MiuixText(this, getString(R.string.adb_enable_tip),
                MiuixText.Role.MICRO, MiuixText.Tone.TERTIARY);
        tip.setSizeSp(12f).setWeight(400);
        tip.setSingleLine(false);
        LinearLayout.LayoutParams tLp = wrapWidth();
        tLp.topMargin = dp(8f);
        body.addView(tip, tLp);

        new MiuixDialog.Builder(this)
                .setTitle(getString(R.string.adb_title))
                .setContent(body)
                .setWidthDp(Math.min(460f, wsc.getWidthDp() - 32f))
                .setPositive(getString(R.string.ok), null)
                .show();
    }

    private void addInfoRow(LinearLayout parent, String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = wrapWidth();
        lp.topMargin = parent.getChildCount() == 0 ? 0 : dp(10f);
        row.setLayoutParams(lp);

        MiuixText k = new MiuixText(this, key, MiuixText.Role.CAPTION,
                MiuixText.Tone.TERTIARY);
        k.setSizeSp(13f).setWeight(400);
        k.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(k);

        MiuixText v = new MiuixText(this, value, MiuixText.Role.BODY_SMALL);
        v.setSizeSp(14f).setWeight(600);
        row.addView(v);
        parent.addView(row);
    }

    private String joinClients(java.util.List<String> clients) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clients.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(clients.get(i));
        }
        return sb.toString();
    }

    private void showCleanDialog() {
        final int count = repo.listJson().length();
        final String size = human(repo.totalSize());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        MiuixButton confirm = new MiuixButton(this, getString(R.string.clean_confirm),
                MiuixButton.Size.MEDIUM, MiuixButton.Color.DANGER);
        confirm.setRadiusDp(R_BTN);
        buttons.addView(confirm, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MiuixButton cancel = new MiuixButton(this, getString(R.string.cancel),
                MiuixButton.Size.MEDIUM, MiuixButton.Color.NEUTRAL);
        cancel.setOutlined(true, MiuixTheme.colors().outline);
        cancel.setRadiusDp(R_BTN);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cancelLp.leftMargin = dp(12f);
        buttons.addView(cancel, cancelLp);

        final MiuixDialog[] ref = new MiuixDialog[1];
        confirm.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = repo.deleteAll();
                if (ref[0] != null) ref[0].dismiss();
                toast(getString(R.string.cleaned, n));
                refreshHome();
                renderList();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (ref[0] != null) ref[0].dismiss();
            }
        });

        ref[0] = new MiuixDialog.Builder(this)
                .setTitle(getString(R.string.clean_title))
                .setMessage(getString(R.string.clean_message, count, size))
                .setContent(buttons)
                .setWidthDp(Math.min(480f, wsc.getWidthDp() - 32f))
                .setRadiusDp(24f)
                .setPaddingDp(28f)
                .show();
    }

    private void confirmDelete(final File f) {
        if (f == null) return;
        new MiuixDialog.Builder(this)
                .setTitle(getString(R.string.delete_title))
                .setMessage(f.getName())
                .setPositive(getString(R.string.delete),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                f.delete();
                                d.dismiss();
                                showPage(PAGE_LIST);
                            }
                        })
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private void showManualConfigDialog() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        final MiuixTextField ssid = new MiuixTextField(this,
                getString(R.string.ap_ssid_label), getString(R.string.ap_ssid_hint));
        body.addView(ssid, wrapWidth());

        final MiuixTextField pass = new MiuixTextField(this,
                getString(R.string.ap_pass_label), getString(R.string.ap_pass_hint));
        body.addView(pass, marginTop(12f));

        if (apConfig != null && apConfig.ssid != null) ssid.setText(apConfig.ssid);

        new MiuixDialog.Builder(this)
                .setTitle(getString(R.string.ap_manual_title))
                .setMessage(getString(R.string.ap_manual_desc))
                .setContent(body)
                .setWidthDp(Math.min(480f, wsc.getWidthDp() - 32f))
                .setPositive(getString(R.string.save_and_start),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                saveManualConfig(ssid.getText(), pass.getText());
                                d.dismiss();
                                openHotspot();
                            }
                        })
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private void showHotspotFailedDialog(String reason) {
        showHotspotDialog(getString(R.string.hotspot_failed_title),
                getString(R.string.hotspot_failed, reason));
    }

    private void showHotspotCloseFailedDialog(String reason) {
        showHotspotDialog(getString(R.string.hotspot_close_failed_title),
                getString(R.string.hotspot_failed, reason));
    }

    /**
     * Any hotspot failure ends here: the only path that always works on a
     * non-system build is the system's own hotspot page, so offer it directly.
     */
    private void showHotspotDialog(String title, String message) {
        new MiuixDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositive(getString(R.string.open_ap_settings),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                d.dismiss();
                                openTetherSettings();
                            }
                        })
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    /** Open the system hotspot / tethering page, walking every known entry. */
    private void openTetherSettings() {
        String[] actions = {
                "android.settings.TETHER_SETTINGS",
                "com.android.settings.TETHER_SETTINGS",
                "android.settings.WIFI_AP_SETTINGS"
        };
        for (String action : actions) {
            try {
                Intent i = new Intent(action);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(getPackageManager()) != null) {
                    startActivity(i);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            return;
        } catch (Exception ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            toast(getString(R.string.no_settings));
        }
    }

    // ---------------------------------------------------------------- behaviour

    private void onConnected() {
        refreshHome();
        if (!hotspotUp && connectOption == 0) openHotspot();
    }

    /**
     * Hotspot toggle. The live state is re-read on every click (the cached flag
     * can go stale when the user flips the hotspot in the system settings), so
     * one control both opens and closes the device hotspot.
     */
    private void onHotspotClicked() {
        if (hotspotBusy) return;
        hotspotUp = softAp.isHotspotUp();
        if (hotspotUp) {
            closeHotspot();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOCATION);
            toast(getString(R.string.need_location_for_ap));
            return;
        }
        openHotspot();
    }

    private void setHotspotBusy(boolean busy) {
        hotspotBusy = busy;
        if (btnHotspot == null) return;
        btnHotspot.setEnabled(!busy);
        if (busy) return;
        btnHotspot.setText(hotspotUp ? getString(R.string.close_hotspot)
                : getString(R.string.open_hotspot));
    }

    /** Open the device hotspot with the car unit's own credentials. */
    private void openHotspot() {
        final SoftApManager.ApConfig desired =
                apConfig != null ? apConfig.copy() : new SoftApManager.ApConfig();
        setHotspotBusy(true);
        if (btnHotspot != null) btnHotspot.setText(getString(R.string.hotspot_opening));
        softAp.openHotspot(desired, new SoftApManager.Callback() {
            public void onStarted(SoftApManager.ApConfig config, String mode) {
                hotspotUp = true;
                SoftApManager.ApConfig live = config != null && config.isValid()
                        ? config : softAp.liveConfig();
                if (live != null && live.isValid()) apConfig = live;
                setHotspotBusy(false);
                refreshHome();
                toast(SoftApManager.MODE_SYSTEM.equals(mode)
                        ? getString(R.string.hotspot_on_system)
                        : getString(R.string.hotspot_on_local));
            }

            public void onFailed(String reason) {
                hotspotUp = softAp.isHotspotUp();
                setHotspotBusy(false);
                refreshHome();
                showHotspotFailedDialog(reason);
            }
        });
    }

    /** Close every hotspot this process can reach, then verify it really went down. */
    private void closeHotspot() {
        setHotspotBusy(true);
        if (btnHotspot != null) btnHotspot.setText(getString(R.string.hotspot_closing));
        softAp.closeHotspotAsync(new SoftApManager.Callback() {
            public void onStarted(SoftApManager.ApConfig config, String mode) {
                hotspotUp = false;
                apConfig = softAp.readSystemConfig();
                if (apConfig == null) apConfig = loadManualConfig();
                setHotspotBusy(false);
                refreshHome();
                toast(getString(R.string.hotspot_off));
            }

            public void onFailed(String reason) {
                hotspotUp = softAp.isHotspotUp();
                setHotspotBusy(false);
                refreshHome();
                showHotspotCloseFailedDialog(reason);
            }
        });
    }

    private void installFile(final File f) {
        if (f == null || !f.exists()) return;
        toast(getString(R.string.installing));
        new Thread(new Runnable() {
            public void run() {
                final AdbManager.InstallResult r = AdbManager.install(f);
                ui.post(new Runnable() {
                    public void run() {
                        showInstallResult(r, f);
                    }
                });
            }
        }).start();
    }

    private void showInstallResult(AdbManager.InstallResult r, final File f) {
        if (r != null && r.success) {
            new MiuixDialog.Builder(this)
                    .setTitle(getString(R.string.install_ok))
                    .setMessage(r.summary)
                    .setPositive(getString(R.string.ok), null)
                    .show();
            return;
        }
        String why = r != null && r.summary != null ? r.summary : "";
        new MiuixDialog.Builder(this)
                .setTitle(getString(R.string.install_failed))
                .setMessage(why)
                .setPositive(getString(R.string.use_system_installer),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                d.dismiss();
                                systemInstall(f);
                            }
                        })
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private void systemInstall(File f) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(LocalFileProvider.uriFor(f.getName()),
                    "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.no_installer));
        }
    }

    private void openFile(File f) {
        if (f == null || !f.exists()) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(LocalFileProvider.uriFor(f.getName()),
                    FileRepository.mimeOf(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.no_viewer));
        }
    }

    private void shareCurrent() {
        if (detailFile == null) return;
        File f = repo.get(detailFile);
        if (f == null || !f.exists()) return;
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(FileRepository.mimeOf(f.getName()));
            i.putExtra(Intent.EXTRA_STREAM, LocalFileProvider.uriFor(f.getName()));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, getString(R.string.share)));
        } catch (Exception e) {
            toast(getString(R.string.no_viewer));
        }
    }

    // ---------------------------------------------------------------- refresh

    private void refreshHome() {
        if (qrTitle == null) return;
        renderRecent();

        int count = repo.listJson().length();
        statCount.setText(String.valueOf(count));
        statSize.setText(new DecimalFormat("0.0").format(repo.totalSize() / 1073741824.0));
        statUnit.setText("GB");
        countBadge.setText(String.valueOf(count));
        devAddrValue.setText(addressText());
        apDetail.setText(apDetailText());

        boolean live = linked || hotspotUp;
        statusDot.setBackground(MiuixTheme.rounded(
                live ? MiuixTheme.colors().success : MiuixTheme.colors().outline, dp(5f)));
        statusTitle.setText(live ? getString(R.string.status_ready)
                : getString(R.string.status_waiting));
        statusSub.setText(live ? getString(R.string.status_waiting_desc)
                : getString(R.string.status_no_device));
        if (!hotspotBusy) {
            btnHotspot.setText(hotspotUp ? getString(R.string.close_hotspot)
                    : getString(R.string.open_hotspot));
        }
        qrTitle.setText(live ? getString(R.string.qr_ready_title)
                : getString(R.string.qr_wait_title));
        qrDesc.setText(live ? getString(R.string.qr_ready_desc)
                : getString(R.string.qr_wait_desc));
        qrModeRow.setVisibility(live ? View.VISIBLE : View.GONE);
        refreshQr();
    }

    private void refreshQr() {
        if (qrSlot == null) return;
        qrSlot.removeAllViews();
        boolean live = linked || hotspotUp;
        String payload = null;
        if (live) {
            payload = qrMode == 0
                    ? SoftApManager.wifiQrPayload(apConfig)
                    : "http://" + SoftApManager.getApIp() + ":" + SERVER_PORT;
        }
        if (payload == null || payload.length() == 0) {
            qrSlot.addView(placeholderCard());
            return;
        }
        qrView = new MiuixQrView(this, qrSize);
        qrView.setContent(payload);
        qrSlot.addView(qrView, new FrameLayout.LayoutParams(
                dp(qrSize), dp(qrSize), Gravity.CENTER));
    }

    private View placeholderCard() {
        MiuixCard ph = new MiuixCard(this, 24f);
        ph.setCardBackground(MiuixTheme.colors().surfaceContainer);
        ph.setBackground(MiuixTheme.outlined(MiuixTheme.colors().surfaceContainer,
                MiuixTheme.colors().outline, dp(24f), dp(1f)));
        ph.setGravity(Gravity.CENTER);
        ph.setLayoutParams(new FrameLayout.LayoutParams(
                dp(qrSize), dp(qrSize), Gravity.CENTER));

        View icon = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float r = Math.min(getWidth(), getHeight()) * 0.32f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(2f, getWidth() * 0.06f));
                p.setColor(MiuixTheme.colors().onSurfaceVariant);
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 200f, 140f, false, p);
                canvas.drawCircle(cx, cy + r * 0.62f, r * 0.14f, p);
                p.setColor(MiuixTheme.colors().error);
                canvas.drawLine(cx - r, cy + r, cx + r, cy - r, p);
            }

            @Override
            protected void onMeasure(int wSpec, int hSpec) {
                setMeasuredDimension(dp(64f), dp(64f));
            }
        };
        ph.addView(icon);

        MiuixText t1 = new MiuixText(this, getString(R.string.not_connected),
                MiuixText.Role.SUBTITLE);
        t1.setSizeSp(18f).setWeight(700);
        t1.setGravity(Gravity.CENTER_HORIZONTAL);
        ph.addView(t1, marginTop(12f));

        MiuixText t2 = new MiuixText(this, getString(R.string.not_connected_desc),
                MiuixText.Role.CAPTION, MiuixText.Tone.TERTIARY);
        t2.setSizeSp(13f).setWeight(400);
        t2.setGravity(Gravity.CENTER_HORIZONTAL);
        ph.addView(t2);
        return ph;
    }

    private String addressText() {
        String ip = SoftApManager.getApIp();
        if (ip == null) ip = "0.0.0.0";
        return ip + " : " + SERVER_PORT;
    }

    private String apDetailText() {
        if (apConfig == null || !apConfig.isValid()) {
            return getString(R.string.ap_unknown);
        }
        return getString(R.string.ap_detail, apConfig.ssid,
                apConfig.isOpen() ? getString(R.string.ap_open) : apConfig.passphrase);
    }

    private final Runnable adbPoll = new Runnable() {
        public void run() {
            new Thread(new Runnable() {
                public void run() {
                    final AdbManager.AdbStatus s = AdbManager.query();
                    final boolean ap = softAp.isHotspotUp();
                    ui.post(new Runnable() {
                        public void run() {
                            applyAdbStatus(s);
                            if (ap != hotspotUp) {
                                hotspotUp = ap;
                                SoftApManager.ApConfig live = softAp.liveConfig();
                                if (live != null && live.isValid()) apConfig = live;
                                refreshHome();
                            } else if (ap && currentPage == PAGE_HOME) {
                                devAddrValue.setText(addressText());
                            }
                        }
                    });
                }
            }).start();
            ui.postDelayed(adbPoll, 3000L);
        }
    };

    private void applyAdbStatus(AdbManager.AdbStatus s) {
        if (adbDot == null || adbText == null || s == null) return;
        boolean on = s.isConnected();
        adbDot.setBackground(MiuixTheme.rounded(
                on ? MiuixTheme.colors().success : MiuixTheme.colors().outline, dp(4f)));
        adbText.setText(on ? getString(R.string.adb_connected, s.clients.get(0))
                : (s.listening ? getString(R.string.adb_listening)
                        : getString(R.string.adb_idle)));
        adbText.setTone(on ? MiuixText.Tone.PRIMARY : MiuixText.Tone.SECONDARY);
    }

    // ---------------------------------------------------------------- infra

    private void startTransferService() {
        TransferService.addListener(receiveListener);
        TransferService.start(this);
    }

    // ------------------------------------------------------------ system bars

    /**
     * Edge-to-edge is enforced for apps targeting SDK 35+, which means the layout
     * is drawn behind the status / navigation bars. Turn it on explicitly for
     * older versions too so behaviour is identical everywhere, then let
     * {@link #applySystemBars} pad every page out of the way of those bars.
     */
    private void setupWindow() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().setAttributes(lp);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    /** Keeps every page clear of the status bar, gesture bar and display cut-out. */
    private void applySystemBars(final View root) {
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = 0, left = 0, right = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 29) {
                    Insets sys = insets.getInsets(WindowInsets.Type.systemBars());
                    Insets cut = insets.getInsets(WindowInsets.Type.displayCutout());
                    top = Math.max(sys.top, cut.top);
                    left = Math.max(sys.left, cut.left);
                    right = Math.max(sys.right, cut.right);
                    bottom = Math.max(sys.bottom, cut.bottom);
                } else {
                    top = insets.getSystemWindowInsetTop();
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });
        root.post(new Runnable() {
            public void run() {
                root.requestApplyInsets();
            }
        });
    }

    // ------------------------------------------------------------ permissions

    private void requestRuntimePermissions() {
        List<String> missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!granted(Manifest.permission.NEARBY_WIFI_DEVICES)) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        } else {
            maybeAskBatteryExemption();
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean ok = grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                openHotspot();
            } else {
                toast(getString(R.string.need_location_for_ap));
            }
            return;
        }
        if (requestCode != REQ_PERMISSIONS) return;
        boolean allGranted = true;
        if (grantResults != null) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
        }
        if (!allGranted) toast(getString(R.string.perm_missing));
        maybeAskBatteryExemption();
    }

    /** Whitelisting from battery optimisation keeps the service alive on screen-off. */
    private void maybeAskBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;

        new MiuixDialog.Builder(this)
                .setTitle(getString(R.string.battery_opt_title))
                .setMessage(getString(R.string.battery_opt_message))
                .setPositive(getString(R.string.battery_opt_confirm),
                        new MiuixDialog.OnActionListener() {
                            public void onAction(MiuixDialog d) {
                                d.dismiss();
                                try {
                                    Intent i = new Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                    i.setData(android.net.Uri.parse(
                                            "package:" + getPackageName()));
                                    startActivity(i);
                                } catch (Exception e) {
                                    toast(getString(R.string.no_settings));
                                }
                            }
                        })
                .setNegative(getString(R.string.cancel), null)
                .show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void saveManualConfig(String ssid, String pass) {
        prefs().edit().putString("ap_ssid", ssid).putString("ap_pass", pass).apply();
        SoftApManager.ApConfig c = new SoftApManager.ApConfig();
        c.ssid = ssid;
        c.passphrase = pass;
        c.fromSystem = false;
        apConfig = c;
    }

    private SoftApManager.ApConfig loadManualConfig() {
        String ssid = prefs().getString("ap_ssid", null);
        if (ssid == null || ssid.length() == 0) return null;
        SoftApManager.ApConfig c = new SoftApManager.ApConfig();
        c.ssid = ssid;
        c.passphrase = prefs().getString("ap_pass", "");
        c.fromSystem = false;
        return c;
    }

    // ---------------------------------------------------------------- helpers

    private int dp(float v) {
        return MiuixTheme.dp(this, v);
    }

    private FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrapWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginLeft(float dp) {
        LinearLayout.LayoutParams lp = wrapContent();
        lp.leftMargin = MiuixTheme.dp(this, dp);
        return lp;
    }

    private LinearLayout.LayoutParams marginTop(float dp) {
        LinearLayout.LayoutParams lp = wrapWidth();
        lp.topMargin = MiuixTheme.dp(this, dp);
        return lp;
    }

    private String timeOf(File f) {
        if (f == null) return "";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(f.lastModified()));
    }

    private static String human(long bytes) {
        if (bytes <= 0) return "0 B";
        DecimalFormat df = new DecimalFormat("0.0");
        if (bytes >= 1073741824L) return df.format(bytes / 1073741824.0) + " GB";
        if (bytes >= 1048576L) return df.format(bytes / 1048576.0) + " MB";
        if (bytes >= 1024L) return df.format(bytes / 1024.0) + " KB";
        return bytes + " B";
    }

    private static String typeLabel(String type) {
        if ("APK".equals(type)) return "Android 应用包";
        if ("ZIP".equals(type)) return "压缩包";
        if ("PDF".equals(type)) return "PDF 文档";
        if ("PNG".equals(type)) return "图片";
        if ("MP4".equals(type)) return "视频";
        if ("DOC".equals(type)) return "文档";
        return "文件";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
