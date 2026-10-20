package app.snapplication.launcher;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;
import kotlin.Unit;

/*
 * Emulator-only instrumentation exercises real remote dialog windows and recorded gesture delivery.
 * Uses MainActivity as a controlled tap target and RemoteActionService's actual recorder/replay pipeline.
 * No production test entry points are exported. Run on a disposable emulator with accessibility enabled.
 * Assertions count delivered clicks, ensure one-shot replay and verify detached mapping-window cleanup.
 */
public class RemoteLearningLifecycleTest extends Instrumentation {
    private MainActivity activity;
    private RemoteActionService service;
    private final AtomicInteger hits = new AtomicInteger();
    private Button target;
    private String savedMacros;
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args = args; start(); }

    // Runs sequentially so each physical tap has an observable, unambiguous click count.
    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        try {
            require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"), "Use a disposable emulator for this test");
            if (args != null && args.containsKey("reconnect")) {
                checkReconnect("seed".equals(args.getString("reconnect")));
                result.putString("reconnect", "PASS: persisted assignment resolves with changed descriptor");
                finish(Activity.RESULT_OK, result); return;
            }
            getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            getTargetContext().getSharedPreferences("launcher", 0).edit()
                .putBoolean("tutorialSeen", true).putBoolean("profileSetupComplete", true).commit();
            activity = (MainActivity) startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            checkLearning();
            result.putString("dialogs", "PASS: detached window and replacement owner");
            shell("settings delete secure enabled_accessibility_services");
            SystemClock.sleep(500);
            shell("settings put secure enabled_accessibility_services app.snapplication.launcher/app.snapplication.launcher.RemoteActionService");
            shell("settings put secure accessibility_enabled 1");
            for (int i=0; i<100 && RemoteActionService.Companion.getInstance()==null; i++) SystemClock.sleep(100);
            service = RemoteActionService.Companion.getInstance();
            require(service != null, "Enable accessibility support on the emulator first");
            SharedPreferences prefs = getTargetContext().getSharedPreferences("remoteMacros", 0);
            JSONObject original = new JSONObject(prefs.getString("macros", "{}"));
            original.remove("QA_TOUCH_ONCE"); savedMacros = original.toString();
            prefs.edit().putString("macros", savedMacros).remove("pendingAssignment").commit();
            ui(() -> {
                FrameLayout page = new FrameLayout(activity);
                target = new Button(activity); target.setId(87654); target.setText("Macro tap target");
                target.setOnClickListener(view -> hits.incrementAndGet());
                FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(500, 220, Gravity.CENTER);
                page.addView(target, position); activity.setContentView(page);
            });
            waitForIdleSync(); SystemClock.sleep(1200);
            ui(() -> require(service.beginRecording("QA_TOUCH_ONCE", "", true), "Recorder did not start"));
            SystemClock.sleep(800);
            int[] xy = new int[2];
            ui(() -> { target.getLocationOnScreen(xy); xy[0]+=target.getWidth()/2; xy[1]+=target.getHeight()/2; });
            tap(xy[0], xy[1]);
            awaitHits(1);
            ui(() -> service.stopRecording());
            SystemClock.sleep(400);
            JSONObject macros = new JSONObject(prefs.getString("macros", "{}"));
            JSONObject macro = macros.getJSONObject("QA_TOUCH_ONCE");
            JSONArray steps = macro.getJSONArray("steps");
            int gestures = 0;
            for (int i=0; i<steps.length(); i++) if ("gesture".equals(steps.getJSONObject(i).optString("kind"))) gestures++;
            require(gestures == 1, "Expected exactly one recorded gesture; got " + steps);
            macro.put("speed", 4.0);
            prefs.edit().putString("macros", macros.toString()).remove("pendingAssignment").commit();
            SystemClock.sleep(1000);
            ui(() -> service.replay("QA_TOUCH_ONCE"));
            awaitHits(2); SystemClock.sleep(5000);
            require(hits.get() == 2, "Macro repeated unexpectedly: " + hits.get());
            result.putString("touch", "PASS: recorded tap forwarded once, saved once, replayed once at 4x speed; no repeat after 5 seconds");
        } catch (Throwable error) {
            result.putString("failure", android.util.Log.getStackTraceString(error));
            code = Activity.RESULT_CANCELED;
        } finally {
            if (service != null) ui(() -> { service.stopRecording(); service.performMappedAction("Stop action"); });
            if (savedMacros != null) getTargetContext().getSharedPreferences("remoteMacros", 0).edit()
                .putString("macros", savedMacros).remove("pendingAssignment").commit();
        }
        finish(code, result);
    }

    // Only the disposable emulator test changes accessibility settings; no physical-device permission is automated.
    private void shell(String command) throws Exception {
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command))) {
            byte[] bytes = new byte[1024]; while (input.read(bytes) != -1) { }
        }
    }

    // Seed before an emulator reboot, then verify with a fresh process and the same saved hardware profile.
    private void checkReconnect(boolean seed) throws Exception {
        InputDevice chosen = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice candidate = InputDevice.getDevice(id);
            if (candidate == null || candidate.isVirtual()) continue;
            int matches = 0;
            for (int other : InputDevice.getDeviceIds()) {
                InputDevice live = InputDevice.getDevice(other);
                if (live != null && live.getName().equals(candidate.getName()) &&
                    live.getVendorId() == candidate.getVendorId() && live.getProductId() == candidate.getProductId()) matches++;
            }
            if (matches == 1) { chosen = candidate; break; }
        }
        require(chosen != null, "No unique emulator input device");
        SharedPreferences prefs = getTargetContext().getSharedPreferences("remoteControls", 0);
        if (seed) {
            JSONObject entry = new JSONObject().put("name", chosen.getName()).put("vendor", chosen.getVendorId())
                .put("product", chosen.getProductId()).put("action", "Home");
            JSONObject mappings = new JSONObject().put("qa-before-reconnect", new JSONObject().put("138", entry));
            require(prefs.edit().putString("mappings", mappings.toString()).commit(), "Could not save assignment");
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent key = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, 138, 0, 0, chosen.getId(), 0);
        require("Home".equals(new RemoteControls(getTargetContext()).action(key)), "Reconnected device lost its saved assignment");
    }

    // Simulates Android removing a mapping window before its 30-second timeout callback.
    private void checkLearning() {
        RemoteControls first = new RemoteControls(activity);
        RemoteControls second = new RemoteControls(activity);
        ui(() -> first.learn("Home", () -> Unit.INSTANCE));
        waitForIdleSync(); SystemClock.sleep(300);
        ui(() -> {
            try {
                Field field = RemoteControls.class.getDeclaredField("learningDialog"); field.setAccessible(true);
                AlertDialog dialog = (AlertDialog) field.get(first);
                require(dialog.getWindow().getDecorView().isAttachedToWindow(), "Test dialog has not attached");
                activity.getSystemService(WindowManager.class).removeViewImmediate(dialog.getWindow().getDecorView());
                require(!RemoteControls.Companion.getLearning(), "Detached dialog still owns keys");
                first.closeLearning(); first.closeLearning();
                first.learn("Home", () -> Unit.INSTANCE); second.learn("Back", () -> Unit.INSTANCE);
                first.closeLearning();
                require(RemoteControls.Companion.getLearning(), "Old controller cleared the new session");
                second.closeLearning(); require(!RemoteControls.Companion.getLearning(), "Learning did not close");
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    // Returns assertion failures to the runner instead of crashing the main thread before cleanup.
    private void ui(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch(Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    // Injects a user tap into the recording overlay, which must mirror it through the real service.
    private void tap(int x, int y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now+80, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN); up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(down, true); getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(up, true); }
        finally { down.recycle(); up.recycle(); }
    }

    // Stops on missing or duplicate delivery, rather than treating a completed gesture callback as success.
    private void awaitHits(int count) {
        for (int i=0; i<80 && hits.get()<count; i++) SystemClock.sleep(100);
        require(hits.get() == count, "Expected " + count + " delivered taps, got " + hits.get());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
