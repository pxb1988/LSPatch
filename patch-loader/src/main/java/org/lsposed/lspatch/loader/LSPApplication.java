package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.app.ResourcesManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.CompatibilityInfo;
import android.content.res.Resources;
import android.os.Build;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.system.Os;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import org.lsposed.lspatch.loader.util.FileUtils;
import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.service.LocalApplicationService;
import org.lsposed.lspatch.service.NullApplicationService;
import org.lsposed.lspatch.service.RemoteApplicationService;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.share.PatchConfig;
import org.lsposed.lspd.core.Startup;
import org.lsposed.lspd.nativebridge.SigBypass;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 */
@SuppressWarnings("unused")
public class LSPApplication {

    public static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    public static final int PER_USER_RANGE = 100000;
    private static final String TAG = "LSPatch";

    private static ActivityThread activityThread;

    private static PatchConfig config;
    private static final Map<String, String> signatures = new HashMap<>();

    public static boolean isIsolated() {
        return (android.os.Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    public static void onLoad() {
        activityThread = ActivityThread.currentActivityThread();
        Log.i(TAG, "Create stub Context");
        var stubContext = createStubContext();
        if (stubContext == null) {
            XLog.e(TAG, "Error when creating context");
            return;
        }

        Log.i(TAG, "Load config");
        config = loadConfig(stubContext);
        if (config == null) {
            Log.e(TAG, "Failed to load config file");
            return;
        }
        Log.i(TAG, "Use manager: " + config.useManager);
        Log.i(TAG, "Signature bypass level: " + config.sigBypassLevel);
        signatures.put(stubContext.getPackageName(), config.originalSignature);

        try {
            Log.d(TAG, "Initialize service client");
            ILSPApplicationService service;
            if (isIsolated()) {
                // not enable RemoteApplicationService in isolated process
                // Caused by: java.lang.SecurityException: Isolated process not allowed to call getContentProvider
                service = new NullApplicationService();
            } else {
                if (config.useManager) {
                    service = new RemoteApplicationService(stubContext);
                } else {
                    service = new LocalApplicationService(stubContext);
                }
                disableProfile(stubContext);
            }

            // start Main.forkCommon
            Startup.initXposed(false, ActivityThread.currentProcessName(), service);
            Log.i(TAG, "Bootstrap Xposed");
            Startup.bootstrapXposed();
            Log.i(TAG, "Xposed ready");
            // end Main.forkCommon

            Log.i(TAG, "Prepare cache apk");
            String cacheApk = prepareCacheApk(stubContext);

            Log.i(TAG, "SigBypass");
            doSigBypass(stubContext);

            Log.i(TAG, "Switch to new LoadedApk");
            // load xposed modules same as lsposed with zygisk
            switchLoadedApk(stubContext, cacheApk);

        } catch (Throwable e) {
            throw new RuntimeException("Do hook", e);
        }
        Log.i(TAG, "LSPatch bootstrap completed");
    }

    private static String prepareCacheApk(Context stubContext) throws IOException {
        if (isIsolated()) {
            SigBypass.enhanceOpenat();
            return stubContext.getApplicationInfo().sourceDir + "!/" + ORIGINAL_APK_ASSET_PATH;
        }
        String cacheApk;
        try (ZipFile sourceFile = new ZipFile(stubContext.getApplicationInfo().sourceDir)) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            Path originPath = Paths.get(stubContext.getApplicationInfo().dataDir, "cache/lspatch/origin/");
            Path cacheApkPath = originPath.resolve(entry.getCrc() + ".apk");
            cacheApk = cacheApkPath.toString();
            if (!Files.exists(cacheApkPath)) {
                Log.i(TAG, "Extract original apk");
                FileUtils.deleteFolderIfExists(originPath);
                Files.createDirectories(originPath);
                try (InputStream is = sourceFile.getInputStream(entry)) {
                    Files.copy(is, cacheApkPath);
                }
            }
        }
        return cacheApk;
    }

    private static PatchConfig loadConfig(Context context) {
        try (var is = context.getClassLoader().getResourceAsStream(CONFIG_ASSET_PATH)) {
            BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            return new Gson().fromJson(streamReader, PatchConfig.class);
        } catch (IOException e) {
            return null;
        }
    }

    private static Context createStubContext() {
        try {
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");
            var stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");

            // clear appComponentFactory to prevent loop
            appInfo.appComponentFactory = null;
            stubLoadedApk.getClassLoader(); // trigger getClassLoader()
            return (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, stubLoadedApk);
        } catch (Throwable e) {
            Log.e(TAG, "createStubContext", e);
            return null;
        }
    }

    private static void switchLoadedApk(Context stubContext, String cacheApk) {
        try {
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");
            var stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            var compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");

            String sourceDir = appInfo.sourceDir;

            // update existing ApplicationInfo
            updateApplicationInfo(appInfo, cacheApk);
            updateApplicationInfoInMessageQueue(appInfo.packageName, cacheApk);
            // update future ApplicationInfo
            proxyApplicationInfoCreator(appInfo.packageName, cacheApk);

            // share dex&resources to save memory
            shareApk(sourceDir, cacheApk);

            // clear cache before getPackageInfoNoCheck
            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);

            // create a fresh LoadedApk and trigger LoadedApkCtorHooker
            LoadedApk appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);

            // start restore reference to appLoadedApk
            XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);
            XposedHelpers.setObjectField(stubContext, "mPackageInfo", appLoadedApk);

            var activityClientRecordClass = XposedHelpers.findClass("android.app.ActivityThread$ActivityClientRecord", ActivityThread.class.getClassLoader());
            var fixActivityClientRecord = (BiConsumer<Object, Object>) (k, v) -> {
                if (activityClientRecordClass.isInstance(v)) {
                    var pkgInfo = XposedHelpers.getObjectField(v, "packageInfo");
                    if (pkgInfo == stubLoadedApk) {
                        Log.d(TAG, "fix loadedapk from ActivityClientRecord");
                        XposedHelpers.setObjectField(v, "packageInfo", appLoadedApk);
                        updateApplicationInfoInObjectFields(v, appInfo.packageName, cacheApk);
                    }
                }
            };
            var mActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mActivities");
            mActivities.forEach(fixActivityClientRecord);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    var mLaunchingActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mLaunchingActivities");
                    mLaunchingActivities.forEach(fixActivityClientRecord);
                }
            } catch (Throwable ignored) {
            }
            // end restore reference to appLoadedApk

            // at org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub.<clinit>(Unknown Source:383)  <-- we are here
            // at android.app.LoadedApk.createAppFactory(LoadedApk.java:268)
            // at android.app.LoadedApk.createOrUpdateClassLoaderLocked(LoadedApk.java:910)
            // at android.app.LoadedApk.getClassLoader(LoadedApk.java:990)
            // at android.app.LoadedApk.getResources(LoadedApk.java:1222) <-- we are called by LoadedApk.getResources
            // at android.app.ContextImpl.createAppContext(ContextImpl.java:2663)
            // at android.app.ContextImpl.createAppContext(ContextImpl.java:2655)

            // call to appLoadedApk.getResources same as ContextImpl.createAppContext, and
            // trigger new AppComponentFactory().instantiateClassLoader()
            // trigger LoadedApkGetCLHooker to load xposed modules
            Resources appResources = (Resources) XposedHelpers.callMethod(appLoadedApk, "getResources");

            // replace ClassLoaders in stubLoadedApk
            switchAllClassLoader(stubLoadedApk, appLoadedApk);

            // at org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub.<clinit>(Unknown Source:383)  <-- we are here
            // at android.app.LoadedApk.createAppFactory(LoadedApk.java:268)
            // at android.app.LoadedApk.createOrUpdateClassLoaderLocked(LoadedApk.java:910)
            // at android.app.LoadedApk.getClassLoader(LoadedApk.java:990)
            // at android.app.LoadedApk.getResources(LoadedApk.java:1222)
            // at android.app.ContextImpl.createAppContext(ContextImpl.java:2663)
            // at android.app.ContextImpl.createAppContext(ContextImpl.java:2655) <- ContextImpl create here
            // at android.app.ActivityThread.handleBindApplication(ActivityThread.java:6602)

            // stubLoadedApk is still referenced by a ContextImpl,
            // and we can't get the ContextImpl by reflection.
            // hook ContextImpl methods to set appLoadedApk & appResources
            replaceLoadedApkInContextImpl(stubLoadedApk, appLoadedApk, appResources);

            Log.i(TAG, "hooked app initialized: " + appLoadedApk);

        } catch (Throwable e) {
            Log.e(TAG, "switchLoadedApk", e);
        }
    }

    private static void shareApk(String sourceDir, String cacheApk) {
        try { // share class loader
            Object applicationLoaders = XposedHelpers.callStaticMethod(Class.forName("android.app.ApplicationLoaders"), "getDefault");
            Map<String, Object> mLoaders = (Map<String, Object>) XposedHelpers.getObjectField(applicationLoaders, "mLoaders");
            Object cl = mLoaders.get(sourceDir);
            if (cl != null) {
                mLoaders.put(cacheApk, cl);
            }
        } catch (Throwable e) {
            Log.w(TAG, "ApplicationLoaders.mLoaders failed", e);
        }
        try {
            ResourcesManager resourcesManager = (ResourcesManager) XposedHelpers.callStaticMethod(ResourcesManager.class, "getInstance");
            try {
                // private final LruCache<ApkKey, ApkAssets> mLoadedApkAssets = NABLE_APK_ASSETS_CACHE) ? new LruCache<>(3) : null;
                LruCache<Object, Object> mLoadedApkAssets = (LruCache<Object, Object>) XposedHelpers.getObjectField(resourcesManager, "mLoadedApkAssets");
                if (mLoadedApkAssets != null) {
                    Map<Object, Object> snapshot = mLoadedApkAssets.snapshot();
                    for (Map.Entry<Object, Object> entry : snapshot.entrySet()) {
                        Object apkKey = entry.getKey();
                        String path = (String) XposedHelpers.getObjectField(apkKey, "path");
                        if (sourceDir.equals(path)) {
                            try {
                                Object newApkKey = XposedHelpers.newInstance(apkKey.getClass(),
                                        new Class[]{String.class, boolean.class, boolean.class},
                                        path, XposedHelpers.getBooleanField(apkKey, "sharedLib"), XposedHelpers.getBooleanField(apkKey, "overlay")
                                );
                                XposedHelpers.setObjectField(newApkKey, "path", cacheApk);
                                mLoadedApkAssets.put(newApkKey, entry.getValue());
                                Log.i(TAG, "copied in ResourcesManager.mLoadedApkAssets");
                            } catch (Throwable t) {
                                Log.w(TAG, "fail to dup entry ResourcesManager.mLoadedApkAssets", t);
                            }
                        }
                    }
                }
            } catch (NoSuchFieldError | NoSuchMethodError ignore) {
            }
            try {
                // private final ArrayMap<ApkKey, WeakReference<ApkAssets>> mCachedApkAssets = new ArrayMap<>();
                Map<Object, Object> mCachedApkAssets = (Map<Object, Object>) XposedHelpers.getObjectField(resourcesManager, "mCachedApkAssets");
                if (mCachedApkAssets != null) {
                    Map<Object, Object> snapshot = new HashMap<>(mCachedApkAssets);
                    for (Map.Entry<Object, Object> entry : snapshot.entrySet()) {
                        Object apkKey = entry.getKey();
                        String path = (String) XposedHelpers.getObjectField(apkKey, "path");
                        if (sourceDir.equals(path)) {
                            try {
                                Object newApkKey = XposedHelpers.newInstance(apkKey.getClass(),
                                        new Class[]{String.class, boolean.class, boolean.class},
                                        path, XposedHelpers.getBooleanField(apkKey, "sharedLib"), XposedHelpers.getBooleanField(apkKey, "overlay")
                                );
                                XposedHelpers.setObjectField(newApkKey, "path", cacheApk);
                                mCachedApkAssets.put(newApkKey, entry.getValue());
                                Log.i(TAG, "copied in ResourcesManager.mCachedApkAssets");
                            } catch (Throwable t) {
                                Log.w(TAG, "fail to dup entry ResourcesManager.mCachedApkAssets", t);
                            }
                        }
                    }
                }
            } catch (NoSuchFieldError | NoSuchMethodError ignore) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "ResourcesManager.mLoadedApkAssets failed", t);
        }
    }

    public static void disableProfile(Context context) {
        final ArrayList<String> codePaths = new ArrayList<>();
        var appInfo = context.getApplicationInfo();
        var pkgName = context.getPackageName();
        if (appInfo == null) return;
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, appInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        var profileDir = HiddenApiBridge.Environment_getDataProfilesDePackageDirectory(appInfo.uid / PER_USER_RANGE, pkgName);

        var attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"));

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File curProfileFile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof").getAbsoluteFile();
            Log.d(TAG, "Processing " + curProfileFile.getAbsolutePath());
            try {
                if (!curProfileFile.canWrite() && Files.size(curProfileFile.toPath()) == 0) {
                    Log.d(TAG, "Skip profile " + curProfileFile.getAbsolutePath());
                    continue;
                }
                if (curProfileFile.exists() && !curProfileFile.delete()) {
                    try (var writer = new FileOutputStream(curProfileFile)) {
                        Log.d(TAG, "Failed to delete, try to clear content " + curProfileFile.getAbsolutePath());
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to delete and clear profile file " + curProfileFile.getAbsolutePath(), e);
                    }
                    Os.chmod(curProfileFile.getAbsolutePath(), 00400);
                } else {
                    Files.createFile(curProfileFile.toPath(), attrs);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile file " + curProfileFile.getAbsolutePath(), e);
            }
        }

    }

    private static int getTranscationId(String clsName, String trasncationName) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        Field field = Class.forName(clsName).getDeclaredField(trasncationName);
        field.setAccessible(true);
        return field.getInt(null);
    }

    private static void proxyPackageInfoCreator(Context context) {
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> proxiedCreator = new Parcelable.Creator<>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0) || packageInfo.signingInfo != null;
                if (hasSignature) {
                    String packageName = packageInfo.packageName;
                    String replacement = signatures.get(packageName);
                    if (replacement == null && !signatures.containsKey(packageName)) {
                        try {
                            var metaData = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
                            String encoded = null;
                            if (metaData != null) encoded = metaData.getString("lspatch");
                            if (encoded != null) {
                                var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                                var patchConfig = new Gson().fromJson(json, PatchConfig.class);
                                replacement = patchConfig.originalSignature;
                            }
                        } catch (PackageManager.NameNotFoundException | JsonSyntaxException ignored) {
                        }
                        signatures.put(packageName, replacement);
                    }
                    if (replacement != null) {
                        if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 1)");
                            packageInfo.signatures[0] = new Signature(replacement);
                        }
                        if (packageInfo.signingInfo != null) {
                            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 2)");
                            Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                            if (signaturesArray != null && signaturesArray.length > 0) {
                                signaturesArray[0] = new Signature(replacement);
                            }
                        }
                    }
                }
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", proxiedCreator);
        clearCreatorCache();
    }

    private static void clearCreatorCache() {
        try {
            Map<?, ?> mCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "mCreators");
            mCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.mCreators", e);
        }
        try {
            Map<?, ?> sPairedCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "sPairedCreators");
            sPairedCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.sPairedCreators", e);
        }
    }

    private static void doSigBypass(Context context) throws IOException {
        if (config.sigBypassLevel >= Constants.SIGBYPASS_LV_PM) {
            XLog.d(TAG, "Original signature: " + config.originalSignature.substring(0, 16) + "...");
            proxyPackageInfoCreator(context);
        }
        // the ApplicationInfo from Parcel api always indicate cacheApk is the sourceDir,
        // so SIGBYPASS_LV_PM_OPENAT is not required any more
//        if (config.sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT) {
//            String cacheApkPath;
//            try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
//                cacheApkPath = context.getCacheDir() + "/lspatch/origin/" + sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc() + ".apk";
//            }
//            SigBypass.enableOpenatHook(context.getPackageResourcePath(), cacheApkPath);
//        }
    }

    private static void switchAllClassLoader(LoadedApk stubLoadedApk, LoadedApk appLoadedApk) {
        var fields = LoadedApk.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == ClassLoader.class) {
                var obj = XposedHelpers.getObjectField(appLoadedApk, field.getName());
                XposedHelpers.setObjectField(stubLoadedApk, field.getName(), obj);
            }
        }
    }

    private static void updateApplicationInfo(ApplicationInfo appInfo, String cacheApk) {
        appInfo.sourceDir = cacheApk;
        appInfo.publicSourceDir = cacheApk;
        appInfo.appComponentFactory = null;
        if (config.appComponentFactory != null && config.appComponentFactory.length() > 0) {
            if (config.appComponentFactory.startsWith(".")) {
                appInfo.appComponentFactory = appInfo.packageName + config.appComponentFactory;
            } else {
                appInfo.appComponentFactory = config.appComponentFactory;
            }
        }
    }

    private static void proxyApplicationInfoCreator(String packageName, String cacheApk) {
        Parcelable.Creator<ApplicationInfo> originalCreator = ApplicationInfo.CREATOR;
        Parcelable.Creator<ApplicationInfo> proxiedCreator = new Parcelable.Creator<ApplicationInfo>() {
            @Override
            public ApplicationInfo createFromParcel(Parcel source) {
                ApplicationInfo applicationInfo = originalCreator.createFromParcel(source);
                if (applicationInfo != null && applicationInfo.packageName.equals(packageName)) {
                    updateApplicationInfo(applicationInfo, cacheApk);
                }
                return applicationInfo;
            }

            @Override
            public ApplicationInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        XposedHelpers.setStaticObjectField(ApplicationInfo.class, "CREATOR", proxiedCreator);
        clearCreatorCache();
    }

    private static void updateApplicationInfoInMessageQueue(String packageName, String cacheApk) {
        try {
            // CreateServiceData may pending in MessageQueue
            // Looper (main, tid 2) {9408ff5}
            //   Message 0: { when=-153ms what=114 obj=CreateServiceData{...} target=android.app.ActivityThread$H }
            //   Message 1: { when=-153ms what=121 obj=BindServiceData{...} target=android.app.ActivityThread$H }
            //     (Total messages: 2, polling=false, quitting=false)

            Message msg = (Message) XposedHelpers.getObjectField(Looper.getMainLooper().getQueue(), "mMessages");
            while (msg != null) {
                if (msg.obj != null) {
                    if (msg.obj.getClass().getName().startsWith("android.app.ActivityThread$")) {
                        updateApplicationInfoInObjectFields(msg.obj, packageName, cacheApk);
                    }
                }
                msg = (Message) XposedHelpers.getObjectField(msg, "next");
            }
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear CreateServiceData", e);
        }
    }

    // android.app.ActivityThread$CreateServiceData.info
    // android.app.ActivityThread$ActivityClientRecord.activityInfo
    private static void updateApplicationInfoInObjectFields(Object obj, String packageName, String cacheApk) {
        if (obj == null) {
            return;
        }
        for (Field field : obj.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue; // skip static field
            }
            if (!ComponentInfo.class.isAssignableFrom(field.getType())) {
                continue; // skip unknown field
            }
            ComponentInfo componentInfo;
            try {
                field.setAccessible(true);
                componentInfo = (ComponentInfo) field.get(obj);
            } catch (SecurityException | IllegalArgumentException | IllegalAccessException ignore) {
                continue; // skip failed
            }
            if (componentInfo == null || componentInfo.applicationInfo == null) {
                continue; // skip null value
            }
            Log.d(TAG, "update " + field);
            if (packageName.equals(componentInfo.packageName)) {
                updateApplicationInfo(componentInfo.applicationInfo, cacheApk);
            }
        }
    }

    private static void replaceLoadedApkInContextImpl(LoadedApk stubLoadedApk, LoadedApk appLoadedApk, Resources appResources) {
        Class<?> cContextImpl = XposedHelpers.findClass("android.app.ContextImpl", ActivityThread.class.getClassLoader());
        List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();
        for (Method m : cContextImpl.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            unhooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // Android 11
                    // at LSPHooker_.getResources(Unknown Source:8) <-- beforeHookedMethod is called here
                    // at android.app.ActivityThread.updateLocaleListFromAppContext(ActivityThread.java:6363)
                    // at android.app.ActivityThread.handleBindApplication(ActivityThread.java:6603)

                    // Android 13
                    // at LSPHooker_.checkPermission(Unknown Source:25) <-- beforeHookedMethod is called here
                    // at android.app.ContextImpl.isSystemOrSystemUI(ContextImpl.java:2157)
                    // at android.app.ContextImpl.createAppContext(ContextImpl.java:3091)
                    // at android.app.ContextImpl.createAppContext(ContextImpl.java:3082)
                    // at android.app.ActivityThread.handleBindApplication(ActivityThread.java:6676)
                    if (XposedHelpers.getObjectField(param.thisObject, "mPackageInfo") == stubLoadedApk) {
                        XposedHelpers.setObjectField(param.thisObject, "mPackageInfo", appLoadedApk);
                        Log.i(TAG, "replaced mPackageInfo in " + param.thisObject);
                        for (Unhook unhook : unhooks) {
                            unhook.unhook();
                        }
                        if (appResources != null) {
                            if (param.method.getName().equals("setResources")) {
                                param.args[0] = appResources;
                            } else {
                                XposedHelpers.callMethod(param.thisObject, "setResources", appResources);
                            }
                        }
                    }
                }
            }));
        }
    }

}
