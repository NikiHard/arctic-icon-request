package com.afollestad.iconrequest;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.text.Html;

import com.afollestad.bridge.Bridge;
import com.afollestad.bridge.MultipartForm;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;

/**
 * @author Aidan Follestad (afollestad)
 */
public final class IconRequest {

    private static final Object LOCK = new Object();

    private Builder mBuilder;
    private ArrayList<App> mApps;
    private ArrayList<App> mSelectedApps;
    private transient Handler mHandler;

    private static IconRequest mRequest;

    private IconRequest() {
        mSelectedApps = new ArrayList<>();
    }

    private IconRequest(Builder builder) {
        this();
        mBuilder = builder;
        mRequest = this;
    }

    public static class Builder implements Serializable {

        protected transient Context mContext;
        protected File mSaveDir = null;
        protected String mFilterName = "appfilter.xml";
        protected String mEmail = null;
        protected String mSubject = "Icon Request";
        protected String mHeader = "These apps aren't themed on my device, theme them please!";
        protected String mFooter = null;
        protected boolean mIncludeDeviceInfo = true;
        protected boolean mGenerateAppFilterXml = true;
        protected boolean mGenerateAppFilterJson;
        protected boolean mErrorOnInvalidAppFilterDrawable = true;
        protected RemoteConfig mRemoteConfig = null;

        protected transient AppsLoadCallback mLoadCallback;
        protected transient RequestSendCallback mSendCallback;
        protected transient AppsSelectionListener mSelectionCallback;

        public Builder() {
        }

        public Builder(@NonNull Context context) {
            mContext = context;
            mSaveDir = new File(context.getExternalCacheDir(), "icon_requests");
            FileUtil.wipe(mSaveDir);
        }

        public Builder filterName(@NonNull String filterName) {
            mFilterName = filterName;
            return this;
        }

        public Builder filterOff() {
            mFilterName = null;
            return this;
        }

        public Builder toEmail(@NonNull String email) {
            mEmail = email;
            return this;
        }

        public Builder withSubject(@Nullable String subject, @Nullable Object... args) {
            if (args != null && subject != null)
                subject = String.format(subject, args);
            mSubject = subject;
            return this;
        }

        public Builder withHeader(@Nullable String header, @Nullable Object... args) {
            if (args != null && header != null)
                header = String.format(header, args);
            mHeader = header;
            return this;
        }

        public Builder withFooter(@Nullable String footer, @Nullable Object... args) {
            if (args != null && footer != null)
                footer = String.format(footer, args);
            mFooter = footer;
            return this;
        }

        public Builder includeDeviceInfo(boolean include) {
            mIncludeDeviceInfo = include;
            return this;
        }

        public Builder loadCallback(@Nullable AppsLoadCallback cb) {
            mLoadCallback = cb;
            return this;
        }

        public Builder sendCallback(@Nullable RequestSendCallback cb) {
            mSendCallback = cb;
            return this;
        }

        public Builder selectionCallback(@Nullable AppsSelectionListener cb) {
            mSelectionCallback = cb;
            return this;
        }

        public Builder generateAppFilterXml(boolean generate) {
            mGenerateAppFilterXml = generate;
            return this;
        }

        public Builder generateAppFilterJson(boolean generate) {
            mGenerateAppFilterJson = generate;
            return this;
        }

        public Builder errorOnInvalidFilterDrawable(boolean error) {
            mErrorOnInvalidAppFilterDrawable = error;
            return this;
        }

        public Builder remoteConfig(@Nullable RemoteConfig config) {
            mRemoteConfig = config;
            return this;
        }

        public IconRequest build() {
            return new IconRequest(this);
        }
    }

    public static Builder start(Context context) {
        return new Builder(context);
    }

    public static IconRequest get() {
        return mRequest;
    }

    private StringBuilder mInvalidDrawables;

    @CheckResult
    @Nullable
    @WorkerThread
    private HashSet<String> loadFilterApps() {
        final HashSet<String> defined = new HashSet<>();
        if (IRUtils.isEmpty(mBuilder.mFilterName))
            return defined;

        InputStream is;
        try {
            final AssetManager am = mBuilder.mContext.getAssets();
            IRLog.log("IconRequestFilter", "Loading your appfilter, opening: %s", mBuilder.mFilterName);
            is = am.open(mBuilder.mFilterName);
        } catch (final Throwable e) {
            e.printStackTrace();
            if (mBuilder.mLoadCallback != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        mBuilder.mLoadCallback.onAppsLoaded(null, new Exception("Failed to open your filter: " + e.getLocalizedMessage(), e));
                    }
                });
            }
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        try {
            final String itemEndStr = "/>";
            final String componentStartStr = "component=\"ComponentInfo";
            final String drawableStartStr = "drawable=\"";
            final String endStr = "\"";
            final String commentStart = "<!--";
            final String commentEnd = "-->";

            String component = null;
            String drawable = null;

            String line;
            boolean inComment = false;

            while ((line = reader.readLine()) != null) {
                final String trimmedLine = line.trim();
                if (!inComment && trimmedLine.startsWith(commentStart)) {
                    inComment = true;
                }
                if (inComment && trimmedLine.endsWith(commentEnd)) {
                    inComment = false;
                    continue;
                }

                if (inComment) continue;
                int start;
                int end;

                start = line.indexOf(componentStartStr);
                if (start != -1) {
                    start += componentStartStr.length();
                    end = line.indexOf(endStr, start);
                    String ci = line.substring(start, end);
                    if (ci.startsWith("{"))
                        ci = ci.substring(1);
                    if (ci.endsWith("}"))
                        ci = ci.substring(0, ci.length() - 1);
                    component = ci;
                }

                start = line.indexOf(drawableStartStr);
                if (start != -1) {
                    start += drawableStartStr.length();
                    end = line.indexOf(endStr, start);
                    drawable = line.substring(start, end);
                }

                start = line.indexOf(itemEndStr);
                if (start != -1 && (component != null || drawable != null)) {
                    IRLog.log("IconRequestFilter", "Found: %s (%s)", component, drawable);
                    if (drawable == null || drawable.trim().isEmpty()) {
                        IRLog.log("IconRequestFilter", "WARNING: Drawable shouldn't be null.");
                        if (mBuilder.mErrorOnInvalidAppFilterDrawable) {
                            if (mInvalidDrawables == null)
                                mInvalidDrawables = new StringBuilder();
                            if (mInvalidDrawables.length() > 0) mInvalidDrawables.append("\n");
                            mInvalidDrawables.append(String.format("Drawable for %s was null or empty.\n", component));
                        }
                    } else if (mBuilder.mContext != null) {
                        final Resources r = mBuilder.mContext.getResources();
                        int identifier;
                        try {
                            identifier = r.getIdentifier(drawable, "drawable", mBuilder.mContext.getPackageName());
                        } catch (Throwable t) {
                            identifier = 0;
                        }
                        if (identifier == 0) {
                            IRLog.log("IconRequestFilter", "WARNING: Drawable %s (for %s) doesn't match up with a resource.", drawable, component);
                            if (mBuilder.mErrorOnInvalidAppFilterDrawable) {
                                if (mInvalidDrawables == null)
                                    mInvalidDrawables = new StringBuilder();
                                if (mInvalidDrawables.length() > 0) mInvalidDrawables.append("\n");
                                mInvalidDrawables.append(String.format("Drawable %s (for %s) doesn't match up with a resource.\n", drawable, component));
                            }
                        }
                    }
                    defined.add(component);
                }
            }

            if (mInvalidDrawables != null && mInvalidDrawables.length() > 0 &&
                    mBuilder.mErrorOnInvalidAppFilterDrawable && mBuilder.mLoadCallback != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        mBuilder.mLoadCallback.onAppsLoaded(null, new Exception(mInvalidDrawables.toString()));
                        mInvalidDrawables.setLength(0);
                        mInvalidDrawables.trimToSize();
                        mInvalidDrawables = null;
                    }
                });
            }
            IRLog.log("IconRequestFilter", "Found %d total app(s) in your appfilter.", defined.size());
        } catch (final Throwable e) {
            e.printStackTrace();
            if (mBuilder.mLoadCallback != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        mBuilder.mLoadCallback.onAppsLoaded(null, new Exception("Failed to read your filter: " + e.getMessage(), e));
                    }
                });
            }
            return null;
        } finally {
            FileUtil.closeQuietely(reader);
            FileUtil.closeQuietely(is);
        }

        return defined;
    }

    @UiThread
    public void loadApps() {
        synchronized (LOCK) {
            if (mBuilder.mLoadCallback == null)
                throw new IllegalStateException("No load callback has been set.");
            if (mHandler == null)
                mHandler = new Handler();

            PerfUtil.begin("loading filter");
            mBuilder.mLoadCallback.onLoadingFilter();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    final HashSet<String> filter = loadFilterApps();
                    PerfUtil.end();
                    if (filter == null) {
                        // If this is the case, an error was already posted
                        return;
                    }

                    PerfUtil.begin("loading unthemed installed apps");
                    IRLog.log("IconRequestApps", "Loading unthemed installed apps...");
                    mApps = ComponentInfoUtil.getInstalledApps(mBuilder.mContext,
                            filter, mBuilder.mLoadCallback, mHandler);
                    PerfUtil.end();

                    post(new Runnable() {
                        @Override
                        public void run() {
                            mBuilder.mLoadCallback.onAppsLoaded(mApps, null);
                        }
                    });
                }
            }).start();
        }
    }

    @SuppressWarnings("MalformedFormatString")
    private String getBody() {
        StringBuilder sb = new StringBuilder();
        if (!IRUtils.isEmpty(mBuilder.mHeader)) {
            sb.append(mBuilder.mHeader.replace("\n", "<br/>"));
            sb.append("<br/><br/>");
        }

        for (int i = 0; i < mSelectedApps.size(); i++) {
            if (i > 0) sb.append("<br/><br/>");
            final App app = mSelectedApps.get(i);
            sb.append(String.format("Name: <b>%s</b><br/>", app.getName()));
            sb.append(String.format("Code: <b>%s</b><br/>", app.getCode()));
            sb.append(String.format("Link: https://play.google.com/store/apps/details?id=%s<br/>", app.getPackage()));
        }

        if (mBuilder.mIncludeDeviceInfo) {
            sb.append(String.format(Locale.getDefault(),
                    "<br/><br/>OS: %s %s<br/>Device: %s %s (%s)",
                    Build.VERSION.RELEASE, IRUtils.getOSVersionName(Build.VERSION.SDK_INT),
                    Build.MANUFACTURER, Build.MODEL, Build.PRODUCT));
            if (mBuilder.mFooter != null) {
                sb.append("<br/>");
                sb.append(mBuilder.mFooter.replace("\n", "<br/>"));
            }
        } else {
            sb.append("<br/><br/>");
            sb.append(mBuilder.mFooter.replace("\n", "<br/>"));
        }
        return sb.toString();
    }

    @UiThread
    public boolean selectApp(@NonNull App app) {
        synchronized (LOCK) {
            if (!mSelectedApps.contains(app)) {
                mSelectedApps.add(app);
                if (mBuilder.mSelectionCallback != null)
                    mBuilder.mSelectionCallback.onAppSelectionChanged(mSelectedApps.size());
                return true;
            }
            return false;
        }
    }

    @UiThread
    public boolean unselectApp(@NonNull App app) {
        synchronized (LOCK) {
            final boolean result = mSelectedApps.remove(app);
            if (result && mBuilder.mSelectionCallback != null)
                mBuilder.mSelectionCallback.onAppSelectionChanged(mSelectedApps.size());
            return result;
        }
    }

    @UiThread
    public boolean toggleAppSelected(@NonNull App app) {
        synchronized (LOCK) {
            final boolean result;
            if (isAppSelected(app))
                result = unselectApp(app);
            else result = selectApp(app);
            return result;
        }
    }

    public boolean isAppSelected(@NonNull App app) {
        synchronized (LOCK) {
            return mSelectedApps.contains(app);
        }
    }

    public IconRequest selectAllApps() {
        synchronized (LOCK) {
            if (mApps == null) return this;
            boolean changed = false;
            for (App app : mApps) {
                if (!mSelectedApps.contains(app)) {
                    changed = true;
                    mSelectedApps.add(app);
                }
            }
            if (changed && mBuilder.mSelectionCallback != null)
                mBuilder.mSelectionCallback.onAppSelectionChanged(mSelectedApps.size());
            return this;
        }
    }

    public void unselectAllApps() {
        synchronized (LOCK) {
            if (mSelectedApps == null || mSelectedApps.size() == 0) return;
            mSelectedApps.clear();
            if (mBuilder.mSelectionCallback != null)
                mBuilder.mSelectionCallback.onAppSelectionChanged(0);
        }
    }

    public boolean isAppsLoaded() {
        synchronized (LOCK) {
            return getApps() != null && getApps().size() > 0;
        }
    }

    @Nullable
    public ArrayList<App> getApps() {
        synchronized (LOCK) {
            return mApps;
        }
    }

    @NonNull
    public ArrayList<App> getSelectedApps() {
        synchronized (LOCK) {
            if (mSelectedApps == null)
                mSelectedApps = new ArrayList<>();
            return mSelectedApps;
        }
    }

    private void post(Runnable runnable) {
        if (mBuilder.mContext == null ||
                (mBuilder.mContext instanceof Activity && ((Activity) mBuilder.mContext).isFinishing())) {
            return;
        } else if (mHandler == null) {
            return;
        }
        mHandler.post(runnable);
    }

    private void postError(@NonNull final String msg, @Nullable final Exception baseError) {
        if (mBuilder.mSendCallback != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    mBuilder.mSendCallback.onRequestError(new Exception(msg, baseError));
                }
            });
        } else {
            throw new IllegalStateException(msg, baseError);
        }
    }

    @UiThread
    public void send() {
        synchronized (LOCK) {
            IRLog.log("IconRequestSend", "Preparing your request to send...");
            if (mBuilder.mSendCallback != null)
                mBuilder.mSendCallback.onRequestPreparing();
            if (mHandler == null)
                mHandler = new Handler();

            if (mApps == null) {
                postError("No apps were loaded from this device.", null);
                return;
            } else if (IRUtils.isEmpty(mBuilder.mEmail) && mBuilder.mRemoteConfig == null) {
                postError("The recipient email for the request cannot be empty.", null);
                return;
            } else if (mSelectedApps == null || mSelectedApps.size() == 0) {
                postError("No apps have been selected for sending in the request.", null);
                return;
            } else if (IRUtils.isEmpty(mBuilder.mSubject)) {
                IRLog.log("IconRequestSend", "Using default email subject.");
                mBuilder.mSubject = "Icon Request";
            }

            if (!mBuilder.mSaveDir.exists() && !mBuilder.mSaveDir.mkdir()) {
                postError("Unable to create folders: " + (mBuilder.mSaveDir != null ? mBuilder.mSaveDir.getAbsolutePath() : "(null)"), null);
                return;
            }

            new Thread(new Runnable() {
                @SuppressWarnings("ResultOfMethodCallIgnored")
                @Override
                public void run() {
                    final ArrayList<File> filesToZip = new ArrayList<>();

                    // Save app icons
                    IRLog.log("IconRequestSend", "Saving icons...");
                    PerfUtil.begin("saving icons");

                    for (App app : mSelectedApps) {
                        final Drawable drawable = app.getIcon(mBuilder.mContext);
                        if (!(drawable instanceof BitmapDrawable)) {
                            IRLog.log("IconRequestSend", "Icon for " + app.getCode() + " didn't return a BitmapDrawable.");
                            continue;
                        }
                        final BitmapDrawable bDrawable = (BitmapDrawable) drawable;
                        final Bitmap icon = bDrawable.getBitmap();
                        final File file = new File(mBuilder.mSaveDir,
                                String.format("%s.png", app.getPackage()));
                        filesToZip.add(file);
                        try {
                            FileUtil.writeIcon(file, icon);
                            IRLog.log("IconRequestSend", "Saved icon: " + file.getAbsolutePath());
                        } catch (final Exception e) {
                            e.printStackTrace();
                            postError("Failed to save an icon: " + e.getMessage(), e);
                            return;
                        }
                    }
                    PerfUtil.end();

                    // Create appfilter
                    IRLog.log("IconRequestSend", "Creating appfilter...");
                    PerfUtil.begin("creating appfilter");

                    StringBuilder xmlSb = null;
                    StringBuilder jsonSb = null;
                    if (mBuilder.mGenerateAppFilterXml && mBuilder.mRemoteConfig == null) {
                        xmlSb = new StringBuilder("<resources>\n" +
                                "    <iconback img1=\"iconback\" />\n" +
                                "    <iconmask img1=\"iconmask\" />\n" +
                                "    <iconupon img1=\"iconupon\" />\n" +
                                "    <scale factor=\"1.0\" />");
                    }
                    if (mBuilder.mGenerateAppFilterJson || mBuilder.mRemoteConfig != null) {
                        jsonSb = new StringBuilder("{\n" +
                                "    \"components\": [");
                    }
                    int index = 0;
                    for (App app : mSelectedApps) {
                        final String name = app.getName();
                        final String drawableName = IRUtils.drawableName(name);
                        if (xmlSb != null) {
                            xmlSb.append("\n\n    <!-- ");
                            xmlSb.append(name);
                            xmlSb.append(" -->\n");
                            xmlSb.append(String.format("    <item\n" +
                                            "        component=\"ComponentInfo{%s}\"\n" +
                                            "        drawable=\"%s\" />",
                                    app.getCode(), drawableName));
                        }
                        if (jsonSb != null) {
                            if (index > 0) jsonSb.append(",");
                            jsonSb.append("\n        {\n");
                            jsonSb.append(String.format("            \"%s\": \"%s\",\n", "name", name));
                            jsonSb.append(String.format("            \"%s\": \"%s\",\n", "pkg", app.getPackage()));
                            jsonSb.append(String.format("            \"%s\": \"%s\",\n", "componentInfo", app.getCode()));
                            jsonSb.append(String.format("            \"%s\": \"%s\"\n", "drawable", drawableName));
                            jsonSb.append("        }");
                        }
                        IRLog.log("IconRequestSend", "Added " + app.getCode() + " to the new generated appfilter file...");
                        index++;
                    }

                    if (xmlSb != null) {
                        xmlSb.append("\n\n</resources>");
                        final File newAppFilter = new File(mBuilder.mSaveDir, "appfilter.xml");
                        filesToZip.add(newAppFilter);
                        try {
                            FileUtil.writeAll(newAppFilter, xmlSb.toString());
                            IRLog.log("IconRequestSend", "Generated appfilter saved to " + newAppFilter.getAbsolutePath());
                        } catch (final Exception e) {
                            e.printStackTrace();
                            postError("Failed to write your request appfilter.xml file: " + e.getMessage(), e);
                            return;
                        }
                    }
                    if (jsonSb != null) {
                        jsonSb.append("\n    ]\n}");
                        if (mBuilder.mRemoteConfig == null) {
                            final File newAppFilter = new File(mBuilder.mSaveDir, "appfilter.json");
                            filesToZip.add(newAppFilter);
                            try {
                                FileUtil.writeAll(newAppFilter, jsonSb.toString());
                                IRLog.log("IconRequestSend", "Generated appfilter JSON saved to: " + newAppFilter.getAbsolutePath());
                            } catch (final Exception e) {
                                e.printStackTrace();
                                postError("Failed to write your request appfilter.json file: " + e.getMessage(), e);
                                return;
                            }
                        }
                    }
                    PerfUtil.end();

                    if (filesToZip.size() == 0) {
                        postError("There are no PNG files to put into the ZIP archive.", null);
                        return;
                    }

                    // Zip everything into an archive
                    IRLog.log("IconRequestSend", "Creating ZIP...");
                    PerfUtil.begin("creating ZIP");

                    final SimpleDateFormat df = new SimpleDateFormat("yyyy.MM.dd", Locale.getDefault());
                    final File zipFile = new File(mBuilder.mSaveDir,
                            String.format("IconRequest-%s.zip", df.format(new Date())));
                    try {
                        ZipUtil.zip(zipFile, filesToZip.toArray(new File[filesToZip.size()]));
                        IRLog.log("IconRequestSend", "ZIP created at " + zipFile.getAbsolutePath());
                    } catch (final Exception e) {
                        e.printStackTrace();
                        postError("Failed to create the request ZIP file: " + e.getMessage(), e);
                        return;
                    }
                    PerfUtil.end();

                    // Cleanup files
                    PerfUtil.begin("cleaning up files");
                    IRLog.log("IconRequestSend", "Cleaning up files...");
                    final File[] files = mBuilder.mSaveDir.listFiles();
                    for (File fi : files) {
                        if (!fi.isDirectory() && (fi.getName().endsWith(".png") || fi.getName().endsWith(".xml") || fi.getName().endsWith(".json"))) {
                            fi.delete();
                            IRLog.log("IconRequestSend", "Deleted: " + fi.getAbsolutePath());
                        }
                    }
                    PerfUtil.end();

                    // Send request to the backend server
                    final RemoteConfig config = mBuilder.mRemoteConfig;

                    if (config != null && jsonSb != null) {
                        PerfUtil.begin("uploading request");
                        Bridge.config()
                                .host(config.url)
                                .defaultHeader("TokenID", config.apiKey)
                                .defaultHeader("Accept", "application/json")
                                .defaultHeader("User-Agent", "afollestad/icon-request")
                                .validators(new RemoteValidator());
                        try {
                            MultipartForm form = new MultipartForm();
                            form.add("archive", zipFile);
                            form.add("requester", config.getSender());
                            form.add("apps", new JSONObject(jsonSb.toString()).toString());
                            Bridge.post("/v1/request")
                                    .throwIfNotSuccess()
                                    .body(form)
                                    .request();
                            IRLog.log("IconRequestSend", "Request uploaded to the server!");
                        } catch (Exception e) {
                            e.printStackTrace();

                            postError("Failed to send icons to the backend: " + e.getMessage(), e);
                            return;
                        }
                        PerfUtil.end();
                    }

                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (config == null) {
                                // Send email intent
                                IRLog.log("IconRequestSend", "Launching intent!");
                                final Uri zipUri = Uri.fromFile(zipFile);
                                final Intent emailIntent = new Intent(Intent.ACTION_SEND)
                                        .putExtra(Intent.EXTRA_EMAIL, new String[]{mBuilder.mEmail})
                                        .putExtra(Intent.EXTRA_SUBJECT, mBuilder.mSubject)
                                        .putExtra(Intent.EXTRA_TEXT, Html.fromHtml(getBody()))
                                        .putExtra(Intent.EXTRA_STREAM, mBuilder.mSendCallback.onRequestProcessUri(zipUri))
                                        .setType("application/zip");
                                mBuilder.mContext.startActivity(Intent.createChooser(
                                        emailIntent, mBuilder.mContext.getString(R.string.send_using)));
                            }
                            IRLog.log("IconRequestSend", "Done!");
                            if (mBuilder.mSendCallback != null)
                                mBuilder.mSendCallback.onRequestSent();
                        }
                    });
                }
            }).start();
        }
    }

    @UiThread
    public static void saveInstanceState(Bundle outState) {
        if (mRequest == null || outState == null) return;
        outState.putSerializable("builder", mRequest.mBuilder);
        outState.putSerializable("apps", mRequest.mApps);
        outState.putSerializable("selected_apps", mRequest.mSelectedApps);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @UiThread
    public static IconRequest restoreInstanceState(Context context, Bundle inState,
                                                   AppsLoadCallback loadCb,
                                                   RequestSendCallback sendCb,
                                                   AppsSelectionListener selectionCb) {
        if (inState == null)
            return null;
        mRequest = new IconRequest();
        if (inState.containsKey("builder")) {
            mRequest.mBuilder = (Builder) inState.getSerializable("builder");
            if (mRequest.mBuilder != null) {
                mRequest.mBuilder.mContext = context;
                mRequest.mBuilder.mLoadCallback = loadCb;
                mRequest.mBuilder.mSendCallback = sendCb;
                mRequest.mBuilder.mSelectionCallback = selectionCb;
            }
        }
        if (inState.containsKey("apps"))
            mRequest.mApps = (ArrayList<App>) inState.getSerializable("apps");
        if (inState.containsKey("selected_apps"))
            mRequest.mSelectedApps = (ArrayList<App>) inState.getSerializable("selected_apps");
        if (mRequest.mApps == null)
            mRequest.mApps = new ArrayList<>();
        if (mRequest.mSelectedApps == null)
            mRequest.mSelectedApps = new ArrayList<>();
        else if (mRequest.mSelectedApps.size() > 0 && selectionCb != null)
            selectionCb.onAppSelectionChanged(mRequest.mSelectedApps.size());
        return mRequest;
    }

    @UiThread
    public static void cleanup() {
        synchronized (LOCK) {
            if (mRequest == null) return;
            if (mRequest.mBuilder != null) {
                mRequest.mBuilder.mContext = null;
                mRequest.mBuilder = null;
            }
            mRequest.mHandler = null;
            if (mRequest.mApps != null) {
                mRequest.mApps.clear();
                mRequest.mApps = null;
            }
            if (mRequest.mSelectedApps != null) {
                mRequest.mSelectedApps.clear();
                mRequest.mSelectedApps = null;
            }
            mRequest = null;
        }
    }
}